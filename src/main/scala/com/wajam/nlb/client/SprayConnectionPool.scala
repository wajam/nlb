package com.wajam.nlb.client

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedDeque, ConcurrentHashMap}
import java.net.InetSocketAddress
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import akka.io.IO
import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.util.Timeout
import akka.pattern.{AskTimeoutException, ask}
import spray.can.Http
import com.yammer.metrics.scala.Instrumented
import com.wajam.tracing.Tracer
import com.wajam.commons.Logging
import com.wajam.nlb.client.ClientActor.{Connected, ConnectionFailed}
import PoolSupervisor._

/**
 * Supervisor of all connection actors.
 * Actors are killed as soon as they throw an exception, since they only throw exceptions when their HTTP connection dies.
 * They are watched at all times, even when they are not in the pool (in that case, pool.remove would have no effect).
 */
class PoolSupervisor(val pool: SprayConnectionPool) extends Actor with ActorLogging {
  val forwarderLookup = new collection.mutable.HashMap[ActorRef, ActorRef]()

  // Stop the actor on any exception
  override val supervisorStrategy = OneForOneStrategy(loggingEnabled = false) {
    case _: Exception =>
      Stop
  }

  def receive = {
    case p: Props =>
      // This is not the actual ForwarderActor ref, just a temporary ask actor.
      // Do not send anything else than the ClientActor ref.
      val forwarder = sender
      val client = context.actorOf(p)

      // Store the Forwarder associated with this connection
      forwarderLookup += (client -> forwarder)
      // Watch the connection
      context.watch(client)

    case Connected =>
      val client = sender

      forwarderLookup.get(client) match {
        case Some(forwarder) =>
          forwarder ! Some(client)
          forwarderLookup - client
        case None =>
          log.warning("Received a Connect message with no associated Forwarder")
      }

    case ConnectionFailed =>
      val client = sender

      forwarderLookup.get(client) match {
        case Some(forwarder) =>
          forwarder ! None
          forwarderLookup - client
        case None =>
          log.warning("Received a ConnectionFailed message with no associated Forwarder")
      }

    case Terminated(child) =>
      // Remove the actor when he's dead
      pool.remove(child)
  }
}

object PoolSupervisor {

  class ConnectionFailedException extends Exception("Could not obtain connection to endpoint")
}

/**
 * Connection pool
 *
 * @param maxSize the maximum amount of connections allowed in the pool
 */
class SprayConnectionPool(
    maxSize: Int,
    askTimeout: Duration)
    (implicit system: ActorSystem,
    tracer: Tracer)
  extends Instrumented
  with Logging {

  private val connectionMap = new ConcurrentHashMap[InetSocketAddress, ConcurrentLinkedDeque[ActorRef]] asScala
  private val currentNbPooledConnections = new AtomicInteger(0)

  private val poolHitMeter = metrics.meter("connection-pool-hit", "hits")
  private val poolMissMeter = metrics.meter("connection-pool-miss", "misses")
  private val poolAddsMeter = metrics.meter("connection-pool-adds", "additions")
  private val poolRemovesMeter = metrics.meter("connection-pool-removes", "removals")
  private val poolRejectionsMeter = metrics.meter("connection-pool-rejections", "rejections")
  private val connectionPoolAskTimeoutMeter = metrics.meter("connection-pool-ask-timeouts", "timeouts")
  private val connectionPooledDestinationsGauge = metrics.gauge("connection-pooled-destinations-size") {
    connectionMap.size
  }
  private val connectionPoolSizeGauge = metrics.gauge("connection-pool-size") {
    currentNbPooledConnections.longValue()
  }
  private val connectionPoolCreateSuccessMeter = metrics.meter("connection-pool-creates-success", "successes")
  private val connectionPoolCreateFailureMeter = metrics.meter("connection-pool-creates-failure", "failures")

  private val poolSupervisor = system.actorOf(Props(new PoolSupervisor(this)))

  // Insert a connection in the pool. This connection must be alive and not used by anyone else.
  def poolConnection(destination: InetSocketAddress, connection: ActorRef): Boolean = {
    if (currentNbPooledConnections.incrementAndGet() <= maxSize) {
      val queue = connectionMap.get(destination) match {
        case None =>
          val newQueue = new ConcurrentLinkedDeque[ActorRef]()
          connectionMap.putIfAbsent(destination, newQueue)
          newQueue
        case Some(queue) =>
          queue
      }
      val added = queue.offerFirst(connection)
      if(added) poolAddsMeter.mark()
      else currentNbPooledConnections.decrementAndGet()
      added
    }
    else {
      currentNbPooledConnections.decrementAndGet()
      poolRejectionsMeter.mark()
      false
    }
  }

  // Try to get a connection from the pool
  private[client] def getPooledConnection(destination: InetSocketAddress): Option[ActorRef] = {
    connectionMap.get(destination) match {
      case Some(queue) =>
        val maybeConnection = Option(queue.poll())
        maybeConnection match {
          case Some(connection) =>
            poolHitMeter.mark()
            markConnectionRemovedFromPool()
          case _ =>
            poolMissMeter.mark()
        }
        maybeConnection
      case _ =>
        poolMissMeter.mark()
        None
    }
  }

  // Get a new connection
  def getNewConnection(destination: InetSocketAddress): Future[ActorRef] = {
    val future = poolSupervisor.ask(ClientActor.props(destination, IO(Http)))(Timeout(askTimeout.toMillis)).mapTo[Option[ActorRef]]

    future.flatMap {
      case Some(_) =>
        connectionPoolCreateSuccessMeter.mark()
        future.map(_.get)
      case None =>
        connectionPoolCreateFailureMeter.mark()
        Future.failed[ActorRef](new ConnectionFailedException)
    } recoverWith {
      case e: AskTimeoutException =>
        connectionPoolAskTimeoutMeter.mark()
        Future.failed[ActorRef](new ConnectionFailedException)
      case e =>
        Future.failed[ActorRef](new ConnectionFailedException)
    }
  }

  // Get a pooled connection if available, otherwise get a new one
  def getConnection(destination: InetSocketAddress): Future[ActorRef] = {
    getPooledConnection(destination: InetSocketAddress) match {
      case Some(pooledConnection) =>
        log.debug("Using a pooled connection")
        Future.successful(pooledConnection)
      case None => {
        log.debug("Using a new connection")
        getNewConnection(destination)
      }
    }
  }

  // Remove an arbitrary connection from the pool
  private[client] def remove(connection: ActorRef) {
    connectionMap.find {
      case (address, queue) => queue.remove(connection)
    } match {
      case Some((_, _)) =>
        log.debug("Removed connection from pool")
        poolRemovesMeter.mark()
        markConnectionRemovedFromPool()
      case _ =>
        log.debug("Could not find connection in pool")
    }
  }

  private def markConnectionRemovedFromPool() {
    currentNbPooledConnections.decrementAndGet()
  }
}
