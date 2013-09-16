package com.wajam.nlb.client

import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import com.typesafe.config.ConfigFactory
import akka.actor._
import scala.concurrent.duration._
import scala.concurrent.duration.Duration
import akka.util.Timeout
import akka.testkit.{TestActorRef, TestKit, ImplicitSender, EventFilter}
import spray.can.Http
import spray.http.{HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import com.wajam.nrv.tracing.{Tracer, NullTraceRecorder}
import com.wajam.nlb.util.{StartStopTimer, TracedRequest}
import com.wajam.nlb.test.ActorProxy
import org.scalatest.mock.MockitoSugar

@RunWith(classOf[JUnitRunner])
class TestClientActor(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with ActorProxy with FunSuite with BeforeAndAfter with BeforeAndAfterAll with MockitoSugar {

  implicit val askTimeout = Timeout(5 seconds)

  implicit val tracer = new Tracer(NullTraceRecorder)

  val clientInitialTimeout: Duration = 1 second

  val destination: InetSocketAddress = new InetSocketAddress("localhost", 9999)

  val timer = mock[StartStopTimer]

  val HTTP_CONNECTED = Http.Connected(destination, destination)
  val HTTP_REQUEST = new HttpRequest()
  val TRACED_REQUEST = TracedRequest(HTTP_REQUEST, timer)
  val HTTP_RESPONSE = new HttpResponse()

  var testId = 0

  var connectorRef: TestActorRef[Actor] = _
  var connector: Actor = _

  var clientRef: TestActorRef[ClientActor] = _
  var client: ClientActor = _

  var routerRef: TestActorRef[Actor] = _
  var router: Actor = _

  var serverRef: TestActorRef[Actor] = _
  var server: Actor = _

  def this() = this(ActorSystem("TestClientActor", ConfigFactory.parseString("""akka.loggers = ["akka.testkit.TestEventListener"]""")))

  class RouterProxyActor extends ProxyActor {
    def wrap(msg: Any) = RouterMessage(msg)
  }
  case class RouterMessage(msg: Any)

  class ServerProxyActor extends ProxyActor {
    def wrap(msg: Any) = ServerMessage(msg)
  }
  case class ServerMessage(msg: Any)

  class ConnectorProxyActor extends ProxyActor {
    def wrap(msg: Any) = ConnectorMessage(msg)
  }
  case class ConnectorMessage(msg: Any)

  before {
    connectorRef = TestActorRef(new ConnectorProxyActor, "connector" + testId)
    connector = connectorRef.underlyingActor

    clientRef = TestActorRef(ClientActor(destination, clientInitialTimeout, connectorRef), "client" + testId)
    client = clientRef.underlyingActor

    routerRef = TestActorRef(new RouterProxyActor, "router" + testId)
    router = routerRef.underlyingActor

    serverRef = TestActorRef(new ServerProxyActor, "server" + testId)
    server = serverRef.underlyingActor
  }

  after {
    testId = testId + 1

    _system.stop(connectorRef)
    _system.stop(clientRef)
    _system.stop(routerRef)
    _system.stop(serverRef)
  }

  override def afterAll {
    _system.shutdown()
    _system.awaitTermination()
  }

  test("should connect to the server") {
    routerRef ! TellTo(clientRef, TRACED_REQUEST)

    expectMsgPF() {
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] =>
    }
  }

  test("should send the appropriate request to the server") {
    routerRef ! TellTo(clientRef, TRACED_REQUEST)

    expectMsgPF() {
      // Send connection confirmation
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] =>
        serverRef ! TellTo(clientRef, Http.Connected(destination, destination))
    }
    expectMsgPF() {
      // Check that request is sent
      case ServerMessage(msg) if msg.isInstanceOf[HttpRequest] =>
    }
  }

  test("should forward the response to the router when receiving an HttpResponse from the server") {
    routerRef ! TellTo(clientRef, TRACED_REQUEST)

    expectMsgPF() {
      // Send connection ACK
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] => serverRef ! TellTo(clientRef, HTTP_CONNECTED)
    }
    expectMsgPF() {
      // Reply to the request
      case ServerMessage(msg) if msg.isInstanceOf[HttpRequest] => serverRef ! TellTo(clientRef, HTTP_RESPONSE)
    }
    expectMsgPF() {
      // Check that the response is forwarded
      case RouterMessage(msg) if msg.isInstanceOf[HttpResponse] =>
    }
  }

  test("should forward the response to the router when receiving ChunkedResponseStart, MessageChunk then ChunkedMessageEnd from the server") {
    val chunkStart = new ChunkedResponseStart(HTTP_RESPONSE)
    val messageChunk = new MessageChunk(Array[Byte](1, 0), "")
    val chunkEnd = ChunkedMessageEnd()

    routerRef ! TellTo(clientRef, TRACED_REQUEST)

    expectMsgPF() {
      // Send connection ACK
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] => serverRef ! TellTo(clientRef, HTTP_CONNECTED)
    }
    expectMsgPF() {
      // Reply to the request
      case ServerMessage(msg) if msg.isInstanceOf[HttpRequest] => {
        serverRef ! TellTo(clientRef, chunkStart)
        serverRef ! TellTo(clientRef, messageChunk)
        serverRef ! TellTo(clientRef, chunkEnd)
      }
    }
    // Check that the response is forwarded
    expectMsgPF() {
      case RouterMessage(msg) if msg.isInstanceOf[ChunkedResponseStart] =>
    }
    expectMsgPF() {
      case RouterMessage(msg) if msg.isInstanceOf[MessageChunk] =>
    }
    expectMsgPF() {
      case RouterMessage(msg) if msg.isInstanceOf[ChunkedMessageEnd] =>
    }
  }

  test("should throw an InitialTimeoutException when creating an actor without feeding him with a request") {
    EventFilter[InitialTimeoutException](occurrences = 1) intercept {}
  }
}
