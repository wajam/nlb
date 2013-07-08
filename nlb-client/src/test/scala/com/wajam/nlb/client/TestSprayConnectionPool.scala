package com.wajam.nlb.client

import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import org.scalatest.mock.MockitoSugar
import akka.actor._
import akka.util.duration._
import akka.testkit.TestActorRef
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Timeout

/**
 * User: Clément
 * Date: 2013-06-20
 * Time: 08:35
 */

@RunWith(classOf[JUnitRunner])
class TestSprayConnectionPool extends FunSuite with BeforeAndAfter with MockitoSugar {
  implicit val system = ActorSystem("TestSprayConnectionPool")
  val destination = new InetSocketAddress("127.0.0.1", 9999)

  val connectionIdleTimeout = 5000
  val connectionInitialTimeout = 1000

  var pool: SprayConnectionPool = _
  var dummyConnectionActor: DummyConnection = _
  var dummyConnectionRef: TestActorRef[DummyConnection] = _
  var currentTime = 0L

  class DummyConnection extends Actor {
    def receive: Receive = {
      case _ =>
    }
  }

  before {
    pool = new SprayConnectionPool(connectionIdleTimeout milliseconds, connectionInitialTimeout milliseconds, 100, system)

    dummyConnectionRef = TestActorRef(new DummyConnection)
    dummyConnectionActor = dummyConnectionRef.underlyingActor
  }

  test("should pool connection") {
    pool.poolConnection(destination, dummyConnectionRef)
    val connection = pool.getConnection(destination)

    connection should equal (dummyConnectionRef)
  }

  test("should be empty after removing only connection") {
    pool.poolConnection(destination, dummyConnectionRef)

    pool.remove(dummyConnectionRef)

    pool.getPooledConnection(destination) should equal (None)
  }

  test("should reject if max size is reached") {
    pool = new SprayConnectionPool(connectionIdleTimeout milliseconds, connectionInitialTimeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)
  }

  test("should allow if size less than maximum") {
    pool = new SprayConnectionPool(connectionIdleTimeout milliseconds, connectionInitialTimeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)

    pool.getPooledConnection(destination)

    pool.poolConnection(destination, dummyConnectionRef) should be (true)
  }

  test("should return None if empty") {
    pool.getPooledConnection(destination) should equal (None)
  }
}

@RunWith(classOf[JUnitRunner])
class TestPoolSupervisor extends FunSuite with BeforeAndAfter {

  implicit var system: ActorSystem = _
  val destination = new InetSocketAddress("127.0.0.1", 9999)

  implicit val askTimeout: Timeout = 200 milliseconds

  val connectionIdleTimeout = 5000
  val connectionInitialTimeout = 1000

  var pool: SprayConnectionPool = _
  var connectionActor: Actor = _

  var connectionRef: TestActorRef[ConnectionMockActor] = _

  var poolSupervisorRef: TestActorRef[PoolSupervisor] = _

  class ConnectionMockActor extends Actor {
    def receive: Receive = {
      case e: Exception =>
        throw e
    }
  }

  before {
    system = ActorSystem("TestPoolSupervisor")

    pool = new SprayConnectionPool(connectionIdleTimeout milliseconds, connectionInitialTimeout milliseconds, 1, system)

    poolSupervisorRef = TestActorRef(Props(new PoolSupervisor(pool)))

    connectionRef = TestActorRef(Props(new ConnectionMockActor), poolSupervisorRef, "connection-mock-actor")

    pool.poolConnection(destination, connectionRef)
  }

  after {
    system.shutdown()
    system.awaitTermination()
  }

  test("should kill a connection once it throws an exception") {
    connectionRef ! new Exception

    connectionRef.isTerminated should equal (true)
  }

  test("should remove a connection from the pool once it dies") {
    poolSupervisorRef.receive(Terminated(connectionRef))

    pool.getPooledConnection(destination) should equal (None)
  }
}
