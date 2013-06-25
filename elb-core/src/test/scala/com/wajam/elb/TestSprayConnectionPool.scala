package com.wajam.elb

import org.scalatest.{BeforeAndAfter, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import org.scalatest.mock.MockitoSugar
import java.net.InetSocketAddress
import akka.actor.{ActorSystem}
import akka.util.duration._
import akka.testkit.TestActorRef
import akka.io.Tcp
import akka.actor.Actor
import akka.util.Duration

/**
 * User: Clément
 * Date: 2013-06-20
 * Time: 08:35
 */

@RunWith(classOf[JUnitRunner])
class TestSprayConnectionPool extends FunSuite with BeforeAndAfter with MockitoSugar {
  implicit val system = ActorSystem("TestSprayConnectionPool")
  val destination = new InetSocketAddress("127.0.0.1", 9999)
  val timeout = 5000
  var pool: SprayConnectionPool = _
  var dummyConnectionActor: DummyConnection = _
  var dummyConnectionRef: TestActorRef[DummyConnection] = _
  var currentTime = 0L

  class DummyConnection extends Actor {
    def receive: Receive = {
      case _ =>
    }
  }

  class DummyConnectionWithTimeout(lifetime: Duration) extends DummyConnection {
    system.scheduler.scheduleOnce(lifetime, self, "die")

    override def receive: Receive = {
      case "die" =>
        context stop self
    }
  }

  before {
    pool = new SprayConnectionPool(timeout milliseconds, 100, system)

    dummyConnectionRef = TestActorRef(new DummyConnection)
    dummyConnectionActor = dummyConnectionRef.underlyingActor
  }

  test("should pool connection") {
    pool.poolConnection(destination, dummyConnectionRef)
    val connection = pool.getConnection(destination)

    connection should equal (dummyConnectionRef)
  }

  test("should reject if max size is reached") {
    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)
  }

  test("should allow if size less than maximum") {
    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.poolConnection(destination, dummyConnectionRef) should be (false)

    pool.getPooledConnection(destination)

    pool.poolConnection(destination, dummyConnectionRef) should be (true)
  }

  test("should return None if empty") {
    pool.getPooledConnection(destination) should equal (None)
  }

  test("should expire connection after timeout") {
    val timeout = 500

    dummyConnectionRef = TestActorRef(new DummyConnectionWithTimeout(timeout milliseconds))

    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRef)

    Thread.sleep(timeout * 2)

    pool.getPooledConnection(destination) should equal (None)
  }

  test("should free a space in the pool once a pool connection expires") {
    val timeout = 500

    val dummyConnectionRefWithTimeout = TestActorRef(new DummyConnectionWithTimeout(timeout milliseconds))

    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    pool.poolConnection(destination, dummyConnectionRefWithTimeout)

    Thread.sleep(timeout * 2)

    pool.getPooledConnection(destination) should equal (None)

    pool.poolConnection(destination, dummyConnectionRef)
    pool.getPooledConnection(destination) should equal (Some(dummyConnectionRef))
  }
}
