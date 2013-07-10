package com.wajam.nlb.client

/**
 * User: Clément
 * Date: 2013-06-19
 * Time: 10:02
 */

import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}
import java.net.InetSocketAddress
import akka.io.IO
import akka.actor._
import akka.actor.SupervisorStrategy._
import akka.util.{Timeout, Duration}
import akka.util.duration._
import akka.dispatch.Await
import akka.pattern.ask
import org.slf4j.LoggerFactory
import spray.can.Http
import com.yammer.metrics.scala.Instrumented
import com.wajam.nrv.tracing.Tracer

/**
 * Supervisor of all connection actors.
 * Actors are killed as soon as they throw an exception, since they only throw exceptions when their HTTP connection dies.
 * They are watched at all times, even when they are not in the pool (in that case, pool.remove would have no effect).
 */
class PoolSupervisor(val pool: SprayConnectionPool) extends Actor {

  // Stop the actor on any exception
  override val supervisorStrategy = OneForOneStrategy() {
    case _: Exception =>
      Stop
  }

  def receive = {
    case p: Props =>
      val child = context.actorOf(p)
      // Return a new actor
      sender ! child
      // Watch it
      context.watch(child)
    case Terminated(child) =>
      // Remove the actor when he's dead
      pool.remove(child)
  }
}

/**
 * Connection pool
 *
 * @param connectionIdleTimeout the maximum amount of time a connection should wait in the pool
 * @param maxSize the maximum amount of connections allowed in the pool
 */
class SprayConnectionPool(connectionIdleTimeout: Duration,
                          connectionInitialTimeout: Duration,
                          maxSize: Int,
                          implicit val system: ActorSystem)(implicit tracer: Tracer) extends Instrumented {
  private val logger = LoggerFactory.getLogger("nlb.connectionpool.logger")

  implicit val askTimeout: Timeout = 200 milliseconds

  private val connectionMap = new ConcurrentHashMap[InetSocketAddress, ConcurrentLinkedQueue[ActorRef]] asScala
  private val currentNbPooledConnections = new AtomicInteger(0)

  private val poolHitMeter = metrics.meter("connection-pool-hit", "hits")
  private val poolMissMeter = metrics.meter("connection-pool-miss", "misses")
  private val poolAddsMeter = metrics.meter("connection-pool-adds", "additions")
  private val poolRemovesMeter = metrics.meter("connection-pool-removes", "removals")
  private val connectionPooledDestinationsGauge = metrics.gauge("connection-pooled-destinations-size") {
    connectionMap.size
  }
  private val connectionPoolSizeGauge = metrics.gauge("connection-pool-size") {
    currentNbPooledConnections.longValue()
  }
  private val connectionPoolCreatesMeter = metrics.meter("connection-pool-creates", "creations")

  private val poolSupervisor = system.actorOf(Props(new PoolSupervisor(this)))

  // Insert a connection in the pool. This connection must be alive and not used by anyone else.
  def poolConnection(destination: InetSocketAddress, connection: ActorRef): Boolean = {
    if (currentNbPooledConnections.incrementAndGet() <= maxSize) {
      val queue = connectionMap.get(destination) match {
        case None =>
          val newQueue = new ConcurrentLinkedQueue[ActorRef]()
          connectionMap.putIfAbsent(destination, newQueue)
          newQueue
        case Some(queue) =>
          queue
      }
      val added = queue.add(connection)
      if(added) poolAddsMeter.mark()
      else currentNbPooledConnections.decrementAndGet()
      added
    }
    else {
      currentNbPooledConnections.decrementAndGet()
      false
    }
  }

  // Try to get a connection from the pool
  protected[client] def getPooledConnection(destination: InetSocketAddress): Option[ActorRef] = {
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

  // Get a new collection out of the pool
  protected[client] def getNewConnection(destination: InetSocketAddress): ActorRef = {
    val future = poolSupervisor ? Props(ClientActor(destination, connectionIdleTimeout, connectionInitialTimeout, IO(Http)))
    connectionPoolCreatesMeter.mark()
    Await.result(future, 200 milliseconds).asInstanceOf[ActorRef]
  }

  // Get a pooled connection if available, otherwise get a new one
  def getConnection(destination: InetSocketAddress): ActorRef = {
    getPooledConnection(destination: InetSocketAddress) match {
      case Some(pooledConnection) =>
        pooledConnection
      case None => {
        getNewConnection(destination)
      }
    }
  }

  // Remove an arbitrary connection from the pool
  protected[nlb] def remove(connection: ActorRef) {
    connectionMap.find {
      case (address, queue) => queue.remove(connection)
    } match {
      case Some((_, _)) =>
        logger.info("Removed connection from pool")
        poolRemovesMeter.mark()
        markConnectionRemovedFromPool()
      case _ =>
        logger.info("Could not find connection in pool")
    }
  }

  private def markConnectionRemovedFromPool() {
    currentNbPooledConnections.decrementAndGet()
  }
}
