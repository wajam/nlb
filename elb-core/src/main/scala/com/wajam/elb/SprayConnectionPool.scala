package com.wajam.elb

/**
 * User: Clément
 * Date: 2013-06-19
 * Time: 10:02
 */

import java.util.concurrent.{ConcurrentLinkedQueue, ConcurrentHashMap}
import scala.collection.JavaConverters._
import java.util.concurrent.atomic.AtomicInteger
import java.net.InetSocketAddress
import akka.actor._
import akka.actor.Terminated
import akka.util.Duration
import org.slf4j.LoggerFactory
import com.wajam.elb.client.ElbClientActor
import akka.io.IO
import spray.can.Http

case class Watch(child: ActorRef)
case class UnWatch(child: ActorRef)

class Watcher(val pool: SprayConnectionPool) extends Actor {

  def receive = {
    case Watch(child) =>
      context.watch(child)
    case UnWatch(child) =>
      context.unwatch(child)
    case Terminated(child) =>
      pool.remove(child)
  }
}

class SprayConnectionPool(timeout: Duration,
                          maxSize: Int,
                          implicit val system: ActorSystem) {
  private val logger = LoggerFactory.getLogger("elb.connectionpool.logger")

  private val connectionMap = new ConcurrentHashMap[InetSocketAddress, ConcurrentLinkedQueue[ActorRef]] asScala
  private val currentNbPooledConnections = new AtomicInteger(0)

  private val watcher = system.actorOf(Props(new Watcher(this)))

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
      watcher ! Watch(connection)
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
    system actorOf Props(ElbClientActor(destination, timeout, IO(Http)))
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
  def remove(connection: ActorRef) {
    connectionMap.find {
      case (address, queue) => queue.remove(connection)
    } match {
      case Some((_, _)) =>
        logger.info("Removed connection from pool")
        watcher ! UnWatch(connection)
        markConnectionRemovedFromPool()
      case _ =>
    }
  }

  private def markConnectionRemovedFromPool() {
    currentNbPooledConnections.decrementAndGet()
  }
}
