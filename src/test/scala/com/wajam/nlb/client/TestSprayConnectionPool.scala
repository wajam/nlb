package com.wajam.nlb.client

import java.net.InetSocketAddress
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.Await
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FlatSpecLike, BeforeAndAfter}
import org.scalatest.junit.JUnitRunner
import org.scalatest.Matchers._
import org.scalatest.mock.MockitoSugar
import akka.actor._
import akka.testkit.{ImplicitSender, TestKit, TestActorRef}
import akka.util.Timeout
import com.wajam.tracing.{NullTraceRecorder, Tracer}

class DummyActor extends Actor {
  def receive: Receive = {
    case _ =>
  }
}

@RunWith(classOf[JUnitRunner])
class TestSprayConnectionPool extends FlatSpecLike with BeforeAndAfter with MockitoSugar {
  implicit val tracer = new Tracer(NullTraceRecorder)

  implicit val system = ActorSystem("TestSprayConnectionPool")
  val destination = new InetSocketAddress("127.0.0.1", 9999)

  val connectionIdleTimeout = 5000

  trait Builder {
    val pool = new SprayConnectionPool(1, 1 second)

    val dummyConnectionRef = TestActorRef(new DummyActor)
    val dummyConnectionActor = dummyConnectionRef.underlyingActor
  }

  "The connection pool" should "pool connection" in new Builder {
    pool.poolConnection(destination, dummyConnectionRef)
    val connection = Await.result(pool.getConnection(destination), 100 milliseconds)

    connection should equal (dummyConnectionRef)
  }

  it should "be empty after removing only connection" in new Builder {
    pool.poolConnection(destination, dummyConnectionRef)

    pool.remove(dummyConnectionRef)

    pool.getPooledConnection(destination) should equal (None)
  }

  it should "reject if max size is reached" in new Builder {
    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)
  }

  it should "allow if size less than maximum" in new Builder {
    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)

    pool.getPooledConnection(destination)

    pool.poolConnection(destination, dummyConnectionRef) should be (true)
  }

  it should "return None if empty" in new Builder {
    pool.getPooledConnection(destination) should equal (None)
  }
}

@RunWith(classOf[JUnitRunner])
class TestPoolSupervisor(_system: ActorSystem)
  extends TestKit(_system)
  with ImplicitSender
  with FlatSpecLike
  with BeforeAndAfter
  with BeforeAndAfterAll {

  implicit val tracer = new Tracer(NullTraceRecorder)
  val destination = new InetSocketAddress("127.0.0.1", 9999)
  implicit val askTimeout: Timeout = 200 milliseconds

  def this() = this(ActorSystem("TestActorSystem"))

  trait Builder {
    class SuccessfulClient extends DummyActor {
      supervisor ! ClientActor.Connected
    }

    class FailingClient extends DummyActor {
      supervisor ! ClientActor.ConnectionFailed
    }

    val pool = new SprayConnectionPool(1, 1 second)
    val supervisor = TestActorRef(Props(new PoolSupervisor(pool)))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "The pool supervisor" should "respond with a client once it is connected" in new Builder {
    supervisor ! Props(new SuccessfulClient)

    expectMsgClass(classOf[Right[String, ActorRef]])
  }

  it should "respond with None if its connection fails" in new Builder {
    supervisor ! Props(new FailingClient)

    expectMsgClass(classOf[Left[String, ActorRef]])
  }

  it should "kill a connection and remove it from the pool once it dies" in new Builder {
    val client = TestActorRef(Props(new SuccessfulClient))

    supervisor.watch(client)
    pool.poolConnection(destination, client)

    client.stop()

    pool.getPooledConnection(destination) should equal (None)
  }
}
