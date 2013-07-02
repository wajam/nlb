package com.wajam.nlb.client

import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import org.scalatest.mock.MockitoSugar
import akka.actor.{Actor, ActorSystem}
import akka.util.duration._
import akka.testkit.TestActorRef

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
    val timeout = 250

    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    val connectionRef = pool.getNewConnection(destination)

    pool.poolConnection(destination, connectionRef)

    Thread.sleep(timeout + 1)

    pool.getPooledConnection(destination) should equal (None)
  }

  test("should free a space in the pool once a pool connection expires") {
    val timeout = 250

    pool = new SprayConnectionPool(timeout milliseconds, 1, system)

    var connectionRef = pool.getNewConnection(destination)

    pool.poolConnection(destination, connectionRef)

    Thread.sleep(timeout + 1)

    connectionRef = pool.getNewConnection(destination)

    pool.poolConnection(destination, connectionRef)

    pool.getPooledConnection(destination) should equal (Some(connectionRef))
  }
}
