package com.wajam.nlb.client

import java.net.InetSocketAddress
import scala.language.postfixOps
import scala.concurrent.duration._
import scala.util.Random
import org.junit.runner.RunWith
import org.scalatest.{FlatSpecLike, BeforeAndAfterAll, BeforeAndAfter}
import org.scalatest.junit.JUnitRunner
import org.scalatest.mock.MockitoSugar
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.util.Timeout
import akka.testkit._
import akka.io.Tcp.Event
import spray.can.Http
import spray.http.{HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import com.wajam.tracing.{Tracer, NullTraceRecorder}
import com.wajam.nlb.util.{StartStopTimer, TracedRequest}
import ClientActor._

@RunWith(classOf[JUnitRunner])
class TestClientActor(_system: ActorSystem)
  extends TestKit(_system)
  with FlatSpecLike
  with BeforeAndAfter
  with BeforeAndAfterAll
  with MockitoSugar {

  implicit val askTimeout = Timeout(5 seconds)

  implicit val tracer = new Tracer(NullTraceRecorder)

  val destination: InetSocketAddress = new InetSocketAddress("localhost", 9999)

  val timer = mock[StartStopTimer]

  val HTTP_CONNECTED = Http.Connected(destination, destination)
  val HTTP_REQUEST = new HttpRequest()
  val TRACED_REQUEST = TracedRequest(HTTP_REQUEST, timer)
  val HTTP_RESPONSE = new HttpResponse()

  var testId = 0

  def this() = this(ActorSystem("TestActorSystem", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))

  trait Builder {
    class ForwarderProbe extends TestProbe(system) {
      def sendRequest() = send(client, TRACED_REQUEST)
    }

    class ServerProbe extends TestProbe(system) {

      def sendConnectAck(ack: Event = Http.Connected(destination, destination)) = {
        send(client, ack)
      }

      def replyWithChunkedSequence() = {
        val chunkStart = new ChunkedResponseStart(HTTP_RESPONSE)
        val messageChunk = MessageChunk(Array[Byte](1, 0))
        val chunkEnd = ChunkedMessageEnd()

        send(client, chunkStart)
        send(client, messageChunk)
        send(client, chunkEnd)
      }
    }

    def replyToConnect(reply: Event = Http.Connected(destination, destination)) = {
      connector.expectMsgClass(classOf[Http.Connect])
      server.sendConnectAck(reply)
    }

    def onceConnected(block: => Unit) = {
      replyToConnect()
      expectMsg(Connected)
      block
    }

    val connector = TestProbe()

    val forwarder = new ForwarderProbe
    val server = new ServerProbe

    val client = TestActorRef(ClientActor.props(connector.ref, tracer)(destination), testActor, Random.nextString(1))
  }

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "a ClientActor" should "send a Http.Connect to the server" in new Builder {
    connector.expectMsgClass(classOf[Http.Connect])
  }

  it should "notify its parent when connected" in new Builder {
    replyToConnect()

    expectMsg(Connected)
  }

  it should "notify its parent when connection failed" in new Builder {
    replyToConnect(Http.CommandFailed(Http.Connect(destination.getHostName)))

    expectMsg(ConnectionFailed)
  }

  it should "send the appropriate request to the server" in new Builder {
    onceConnected {
      forwarder.sendRequest()
      server.expectMsgClass(classOf[HttpRequest])
    }
  }

  it should "forward the response to the router when receiving an HttpResponse from the server" in new Builder {
    onceConnected {
      forwarder.sendRequest()

      server.expectMsgClass(classOf[HttpRequest])
      server.reply(HTTP_RESPONSE)

      forwarder.expectMsgClass(classOf[HttpResponse])
    }
  }

  it should "forward the response to the router when receiving ChunkedResponseStart, MessageChunk then ChunkedMessageEnd from the server" in new Builder {
    onceConnected {
      forwarder.sendRequest()

      server.expectMsgClass(classOf[HttpRequest])
      server.replyWithChunkedSequence()

      // Check that the response is forwarded
      forwarder.expectMsgClass(classOf[ChunkedResponseStart])
      forwarder.expectMsgClass(classOf[MessageChunk])
      forwarder.expectMsgClass(classOf[ChunkedMessageEnd])
    }
  }
}
