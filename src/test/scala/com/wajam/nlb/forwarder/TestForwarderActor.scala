package com.wajam.nlb.forwarder

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import akka.actor.{Props, ActorRef, ActorSystem}
import scala.concurrent.duration._
import akka.testkit.{TestActorRef, ImplicitSender, TestKit}
import com.wajam.nlb.test.ActorProxy
import com.typesafe.config.ConfigFactory
import com.wajam.nlb.client.SprayConnectionPool
import akka.util.Timeout
import com.wajam.tracing.{NullTraceRecorder, Tracer}
import java.net.InetSocketAddress
import spray.http._
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import HttpMethods.GET
import com.wajam.nlb.util.{TracedRequest, Router}
import spray.http.HttpRequest
import spray.http.HttpResponse

@RunWith(classOf[JUnitRunner])
class TestForwarderActor(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with ActorProxy with FunSuite with BeforeAndAfter with BeforeAndAfterAll with MockitoSugar {

  def this() = this(ActorSystem("TestClientActor", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))

  implicit val askTimeout = Timeout(5 seconds)

  val idleTimeout = 1 second

  implicit val tracer = new Tracer(NullTraceRecorder)

  val destination: InetSocketAddress = new InetSocketAddress("localhost", 9999)
  val path = "/test"

  val router = mock[Router]
  when(router.resolve(path)).thenReturn(destination)

  val pool = mock[SprayConnectionPool]

  val request = new HttpRequest(GET, Uri(path))
  val response = new HttpResponse()

  var clientRef: TestActorRef[ClientProxyActor] = _
  var clientActorRef: TestActorRef[ClientActorProxyActor] = _
  var newClientActorRef: TestActorRef[ClientActorProxyActor] = _
  var forwarderRef: TestActorRef[ForwarderActor] = _

  class ClientProxyActor extends ProxyActor {
    def wrap(msg: Any) = ClientMessage(msg)
  }
  case class ClientMessage(msg: Any)

  class ClientActorProxyActor extends ProxyActor {
    def wrap(msg: Any) = ClientActorMessage(msg)
  }
  case class ClientActorMessage(msg: Any)

  before {
    clientRef = TestActorRef(Props(new ClientProxyActor()))
    clientActorRef = TestActorRef(Props(new ClientActorProxyActor()))
    newClientActorRef = TestActorRef(Props(new ClientActorProxyActor()))

    when(pool.getNewConnection(destination)).thenReturn(newClientActorRef)
    when(pool.getConnection(destination)).thenReturn(clientActorRef)

    forwarderRef = TestActorRef(Props(new ForwarderActor(pool, router, idleTimeout, tracer)))

    clientRef ! TellTo(forwarderRef, request)

    expectMsgPF() {
      // Check that the request is sent to ClientActor
      case ClientActorMessage(msg) if msg.isInstanceOf[TracedRequest] =>
    }
  }

  after {
    clientRef.stop()
    clientActorRef.stop()
    newClientActorRef.stop()
    forwarderRef.stop()
  }

  override def afterAll {
    _system.shutdown()
    _system.awaitTermination()
  }

  test("should pick a new ClientActor and re-send the request in case the initial ClientActor dies before transmitting") {
    _system.stop(clientActorRef)

    expectMsgPF() {
      // Check that the request is sent to the new ClientActor
      case ClientActorMessage(msg) if msg.isInstanceOf[TracedRequest] =>
        // Check that the ForwarderActor has asked for a new connection
        verify(pool).getNewConnection(destination)
    }
  }

  test("should forward a HttpResponse from ClientActor to Client") {
    clientActorRef ! TellTo(forwarderRef, response)

    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[HttpResponse] =>
    }
  }

  test("should forward a ChunkedResponseStart from ClientActor to Client") {
    clientActorRef ! TellTo(forwarderRef, new ChunkedResponseStart(response))

    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[ChunkedResponseStart] =>
    }
  }

  test("should forward a sequence of ChunkedResponseStart, MessageChunks, ChunkedMessageEnd to Client") {
    clientActorRef ! TellTo(forwarderRef, new ChunkedResponseStart(response))
    clientActorRef ! TellTo(forwarderRef, MessageChunk("0"))
    clientActorRef ! TellTo(forwarderRef, MessageChunk("1"))
    clientActorRef ! TellTo(forwarderRef, MessageChunk("2"))
    clientActorRef ! TellTo(forwarderRef, MessageChunk("3"))
    clientActorRef ! TellTo(forwarderRef, new ChunkedMessageEnd(""))

    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[ChunkedResponseStart] =>
    }
    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[MessageChunk] && msg.asInstanceOf[MessageChunk].bodyAsString == "0" =>
    }
    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[MessageChunk] && msg.asInstanceOf[MessageChunk].bodyAsString == "1" =>
    }
    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[MessageChunk] && msg.asInstanceOf[MessageChunk].bodyAsString == "2" =>
    }
    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[MessageChunk] && msg.asInstanceOf[MessageChunk].bodyAsString == "3" =>
    }
    expectMsgPF() {
      // Check that the response is forwarded to Client
      case ClientMessage(msg) if msg.isInstanceOf[ChunkedMessageEnd] =>
    }
  }

}
