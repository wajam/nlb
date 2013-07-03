package com.wajam.nlb.client

import java.net.InetSocketAddress
import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfter, FunSuite}
import org.scalatest.junit.JUnitRunner
import com.typesafe.config.ConfigFactory
import akka.actor._
import akka.util.duration._
import akka.util.{Timeout, Duration}
import akka.testkit.{TestActorRef, TestKit, ImplicitSender, EventFilter}
import spray.can.Http
import spray.http.{HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}

/**
 * User: Clément
 * Date: 2013-06-25
 * Time: 11:58
 */

@RunWith(classOf[JUnitRunner])
class TestClientActor(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with FunSuite with BeforeAndAfter with BeforeAndAfterAll {
  import system._

  implicit val askTimeout = Timeout(5 seconds)

  val clientIdleTimeout: Duration = 2 seconds
  val clientInitialTimeout: Duration = 1 second

  val maxAwaitDone = 3 seconds

  val destination: InetSocketAddress = new InetSocketAddress("localhost", 9999)

  val HTTP_CONNECTED = Http.Connected(destination, destination)
  val HTTP_REQUEST = new HttpRequest()
  val HTTP_RESPONSE = new HttpResponse()

  var connectorRef: TestActorRef[Actor] = _
  var connector: Actor = _

  var clientRef: TestActorRef[ClientActor] = _
  var client: ClientActor = _

  var routerRef: TestActorRef[Actor] = _
  var router: Actor = _

  var serverRef: TestActorRef[Actor] = _
  var server: Actor = _

  def this() = {
    this(ActorSystem("TestClientActor", ConfigFactory.parseString("""
     akka.loglevel = DEBUG
     akka.actor.debug {
       receive = on
       lifecycle = on
     }
     akka.event-handlers = ["akka.testkit.TestEventListener"]
     """)))
  }

  /** Define proxy actors that will act as:
    * - the Router actor (defined at the application level, usually calling the Client actor),
    * - the Server actor (furnished by Spray and representing the node),
    * - the Connector actor (usually IO(Http))
    *
    * These proxies will send any message to any actor when receiving a TellTo message.
    *
    * They will also forward every message they receive to testActor, after wrapping them in a
    * specific case class so that testActor can check which proxy sent it.
    */
  abstract class ProxyActor extends Actor with ActorLogging {
    def receive: Receive = {
      case TellTo(recipient: ActorRef, msg: Any) =>
        recipient ! msg
        log.info("Telling "+ msg +" to "+ recipient)
      case x =>
        testActor ! wrap(x)
        log.info("Forwarding "+ x +" to testActor")
    }

    def wrap(msg: Any): Any
  }

  case class TellTo(recipient: ActorRef, msg: Any)

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
    connectorRef = TestActorRef(new ConnectorProxyActor, "connector")
    connector = connectorRef.underlyingActor

    clientRef = TestActorRef(ClientActor(destination, clientIdleTimeout, clientInitialTimeout, connectorRef), "client")
    client = clientRef.underlyingActor

    routerRef = TestActorRef(new RouterProxyActor, "router")
    router = routerRef.underlyingActor

    serverRef = TestActorRef(new ServerProxyActor, "server")
    server = serverRef.underlyingActor
  }

  after {
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
    routerRef ! TellTo(clientRef, HTTP_REQUEST)

    expectMsgPF() {
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] =>
    }
  }

  test("should send the appropriate request to the server") {
    routerRef ! TellTo(clientRef, HTTP_REQUEST)

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
    routerRef ! TellTo(clientRef, HTTP_REQUEST)

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

    routerRef ! TellTo(clientRef, HTTP_REQUEST)

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
    expectMsgPF() {
      // Check that the response is forwarded
      case RouterMessage(msg) if msg.isInstanceOf[ChunkedResponseStart] =>
      case RouterMessage(msg) if msg.isInstanceOf[MessageChunk] =>
      case RouterMessage(msg) if msg.isInstanceOf[ChunkedMessageEnd] =>
    }
  }

  test("should throw an InitialTimeoutException when creating an actor without feeding him with a request") {
    EventFilter[InitialTimeoutException](occurrences = 1) intercept {
      Thread.sleep(1)
    }
  }

  test("should throw a PoolTimeoutException when waiting too long in waitingForRequest state") {
    routerRef ! TellTo(clientRef, HTTP_REQUEST)

    expectMsgPF() {
      // Send connection confirmation
      case ConnectorMessage(msg) if msg.isInstanceOf[Http.Connect] =>
        serverRef ! TellTo(clientRef, Http.Connected(destination, destination))
    }
    expectMsgPF() {
      // Reply to the request
      case ServerMessage(msg) if msg.isInstanceOf[HttpRequest] => serverRef ! TellTo(clientRef, HTTP_RESPONSE)
    }
    expectMsgPF() {
      // Check that the response is forwarded
      case RouterMessage(msg) if msg.isInstanceOf[HttpResponse] =>
    }

    EventFilter[PoolTimeoutException](occurrences = 1) intercept {
      Thread.sleep(1)
    }
  }
}
