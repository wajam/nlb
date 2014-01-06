package com.wajam.nlb.forwarder

import java.net.InetSocketAddress
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, FlatSpec}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import com.typesafe.config.ConfigFactory
import akka.actor.{Props, ActorSystem}
import akka.testkit.{TestProbe, TestActorRef, TestKit}
import akka.util.Timeout
import akka.pattern.gracefulStop
import spray.http.HttpMethods.GET
import spray.http._
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.tracing.{NullTraceRecorder, Tracer}
import com.wajam.nlb.util.{TracedRequest, Router}

@RunWith(classOf[JUnitRunner])
class TestForwarderActor(_system: ActorSystem)
  extends TestKit(_system)
  with FlatSpec
  with BeforeAndAfterAll
  with MockitoSugar {

  def this() = this(ActorSystem("TestForwarderActor", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))

  implicit val askTimeout = Timeout(5 seconds)

  val idleTimeout = 3 seconds

  implicit val tracer = new Tracer(NullTraceRecorder)

  val destination: InetSocketAddress = new InetSocketAddress("localhost", 9999)
  val path = "/test"

  val router = mock[Router]
  when(router.resolve(path)).thenReturn(destination)

  val pool = mock[SprayConnectionPool]

  val HTTP_REQUEST = new HttpRequest(GET, Uri(path))
  val HTTP_RESPONSE = new HttpResponse()
  val HTTP_CHUNK_START = new ChunkedResponseStart(HTTP_RESPONSE)
  val HTTP_CHUNK_END = new ChunkedMessageEnd("")

  trait Builder {
    val client = new TestProbe(system)
    val server = new TestProbe(system)

    val forwarder = TestActorRef(Props(classOf[ForwarderActor], pool, router, idleTimeout, tracer))

    when(pool.getConnection(destination)).thenReturn(Future.successful(client.testActor))

    server.send(forwarder, HTTP_REQUEST)

    // Let the forwarder move to waitForResponse state
    Thread.sleep(200)
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "a ForwarderActor" should "pick a new ClientActor and re-send the request in case the initial ClientActor dies before transmitting" in new Builder {
    val newClient = new TestProbe(system)

    when(pool.getNewConnection(destination)).thenReturn(Future.successful(newClient.testActor))

    verify(pool).getConnection(destination)

    // Synchronously stop the client connection actor
    val stopped: Future[Boolean] = gracefulStop(client.testActor, 5 seconds)
    Await.result(stopped, 6 seconds)

    // Check that a new connection is requested
    verify(pool, timeout(500)).getNewConnection(destination)
    // Check that the new connection is used
    newClient.expectMsgClass(classOf[TracedRequest])
  }

  it should "forward a HttpResponse from client to server" in new Builder {
    client.send(forwarder, HTTP_RESPONSE)

    server.expectMsg(HTTP_RESPONSE)
  }

  it should "forward a ChunkedResponseStart from client to server" in new Builder {
    client.send(forwarder, HTTP_CHUNK_START)

    server.expectMsg(HTTP_CHUNK_START)
  }

  it should "forward a sequence of ChunkedResponseStart, MessageChunks, ChunkedMessageEnd to Client" in new Builder {
    val chunks = for(i <- 0 to 3) yield MessageChunk(i.toString)

    // Send a chunked message from client to forwarder
    client.send(forwarder, HTTP_CHUNK_START)
    chunks.map { chunk =>
      client.send(forwarder, chunk)
    }
    client.send(forwarder, HTTP_CHUNK_END)

    // Check that server receives everything in order
    server.expectMsg(HTTP_CHUNK_START)
    chunks.map { chunk =>
      server.expectMsg(chunk)
    }
    server.expectMsg(HTTP_CHUNK_END)
  }
}
