package com.wajam.elb

/**
 * User: Clément
 * Date: 2013-06-19
 * Time: 10:02
 */

import com.wajam.elb.client.ElbClientActor

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
 * @param timeout the maximum amount of time a connection should wait in the pool
 * @param maxSize the maximum amount of connections allowed in the pool
 */
class SprayConnectionPool(timeout: Duration,
                          maxSize: Int,
                          implicit val system: ActorSystem) {
  private val logger = LoggerFactory.getLogger("elb.connectionpool.logger")

  implicit val askTimeout: Timeout = 200 milliseconds

  private val connectionMap = new ConcurrentHashMap[InetSocketAddress, ConcurrentLinkedQueue[ActorRef]] asScala
  private val currentNbPooledConnections = new AtomicInteger(0)

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
      queue.add(connection)
    }
    else {
      currentNbPooledConnections.decrementAndGet()
      false
    }
  }

  // Try to get a connection from the pool
  def getPooledConnection(destination: InetSocketAddress): Option[ActorRef] = {
    connectionMap.get(destination) match {
      case Some(queue) =>
        val maybeConnection = Option(queue.poll())
        if(!maybeConnection.isEmpty) markConnectionRemovedFromPool()
        maybeConnection
      case _ =>
        None
    }
  }

  // Get a new collection out of the pool
  def getNewConnection(destination: InetSocketAddress): ActorRef = {
    val future = poolSupervisor ? Props(ElbClientActor(destination, timeout, IO(Http)))
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
  protected[elb] def remove(connection: ActorRef) {
    connectionMap.find {
      case (address, queue) => queue.remove(connection)
    } match {
      case Some((_, _)) =>
        logger.info("Removed connection from pool")
        markConnectionRemovedFromPool()
      case _ =>
    }
  }

  private def markConnectionRemovedFromPool() {
    currentNbPooledConnections.decrementAndGet()
  }
}
