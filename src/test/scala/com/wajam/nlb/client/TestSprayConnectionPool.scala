package com.wajam.nlb.client

import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import org.scalatest.mock.MockitoSugar
import akka.actor._
import scala.concurrent.duration._
import akka.testkit.TestActorRef
import akka.util.Timeout
import com.wajam.tracing.{NullTraceRecorder, Tracer}

class DummyActor extends Actor {
  def receive: Receive = {
    case _ =>
  }
}

@RunWith(classOf[JUnitRunner])
class TestSprayConnectionPool extends FunSuite with BeforeAndAfter with MockitoSugar {
  implicit val tracer = new Tracer(NullTraceRecorder)

  implicit val system = ActorSystem("TestSprayConnectionPool")
  val destination = new InetSocketAddress("127.0.0.1", 9999)

  val connectionIdleTimeout = 5000
  val connectionInitialTimeout = 1000

  var pool: SprayConnectionPool = _
  var dummyConnectionActor: DummyActor = _
  var dummyConnectionRef: TestActorRef[DummyActor] = _
  var currentTime = 0L

  before {
    pool = new SprayConnectionPool(connectionInitialTimeout milliseconds, 100, 200)

    dummyConnectionRef = TestActorRef(new DummyActor)
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
    pool = new SprayConnectionPool(connectionInitialTimeout milliseconds, 1, 200)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)
  }

  test("should allow if size less than maximum") {
    pool = new SprayConnectionPool(connectionInitialTimeout milliseconds, 1, 200)

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
  implicit val tracer = new Tracer(NullTraceRecorder)
  implicit var system: ActorSystem = _
  val destination = new InetSocketAddress("127.0.0.1", 9999)
  implicit val askTimeout: Timeout = 200 milliseconds
  val connectionInitialTimeout = 1000
  var pool: SprayConnectionPool = _
  var connectionRef: TestActorRef[ClientActor] = _
  var poolSupervisorRef: TestActorRef[PoolSupervisor] = _
  var IOconnector: TestActorRef[DummyActor] = _

  before {
    system = ActorSystem("TestPoolSupervisor")
    pool = new SprayConnectionPool(connectionInitialTimeout milliseconds, 1, 200)
    poolSupervisorRef = TestActorRef(Props(new PoolSupervisor(pool)))
    IOconnector = TestActorRef(Props(new DummyActor()))
    connectionRef = TestActorRef(ClientActor.props(destination, IOconnector), poolSupervisorRef, "connection-mock-actor")
    poolSupervisorRef.watch(connectionRef)
    pool.poolConnection(destination, connectionRef)
  }

  after {
    system.shutdown()
    system.awaitTermination()
  }

  test("should kill a connection and remove it from the pool once it dies") {
    connectionRef.stop()

    pool.getPooledConnection(destination) should equal (None)
  }
}
