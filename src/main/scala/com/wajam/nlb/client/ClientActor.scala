package com.wajam.nlb.client

import java.net.InetSocketAddress
import akka.actor._
import scala.concurrent.duration._
import spray.can.Http
import spray.util.SprayActorLogging
import spray.http.{Timedout, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import spray.can.client.ClientConnectionSettings
import com.yammer.metrics.scala.Instrumented
import com.wajam.nrv.tracing.{TraceContext, RpcName, Annotation, Tracer}
import com.wajam.nlb.util.TracedRequest

/**
 * User: Clément
 * Date: 2013-06-12
 */

/**
 * Actor handling an HTTP connection with a specific node.
 *
 * @param destination the destination node
 * @param IOconnector the actor handling the underlying connection (usually IO(Http), except when testing)
 */
class ClientActor(destination: InetSocketAddress,
                  initialTimeout: Duration,
                  IOconnector: ActorRef)(implicit tracer: Tracer) extends Actor with SprayActorLogging with Instrumented {
  import context.system

  private val initialTimeoutsMeter = metrics.meter("client-initial-timeouts", "timeouts")
  private val connectionFailedMeter = metrics.meter("client-connection-failed", "fails")
  private val sendFailedMeter = metrics.meter("client-send-failed", "fails")
  private val requestTimeoutMeter = metrics.meter("client-request-timeout", "fails")
  private val connectionExpiredMeter = metrics.meter("client-connection-expired", "expirations")
  private val connectionClosedMeter = metrics.meter("client-connection-closed", "closings")
  private val openConnectionsCounter = metrics.counter("client-open-connections", "connections")
  private val chunkedResponsesMeter = metrics.meter("client-chunk-responses", "responses")
  private val unchunkedResponsesMeter = metrics.meter("client-unchunk-responses", "responses")

  var router: ActorRef = _
  var server: ActorRef = _
  var request: TracedRequest = _

  // Initial timeout
  context.setReceiveTimeout(initialTimeout)
  
  /**
   * Behaviours
   */

  def receive = {
    case (router: ActorRef, request: TracedRequest) =>
      // start by establishing a new HTTP connection
      this.router = router
      this.request = request

      context.become(connect)

      IOconnector ! Http.Connect(destination.getHostString, port = destination.getPort, settings = Some(ClientConnectionSettings(system).copy(responseChunkAggregationLimit = 0)))

    case ReceiveTimeout =>
      log.warning("Initial timeout")
      initialTimeoutsMeter.mark()
      throw new InitialTimeoutException
  }

  def connect: Receive = {
    case _: Http.Connected =>
      // once connected, we can send the request across the connection
      server = sender
      context.become(waitForRequest)
      self ! (router, request)
      openConnectionsCounter += 1

      // watch the Spray connector to monitor connection lifecycle
      context.watch(server)

    case ev: Http.CommandFailed =>
      log.warning("Command failed ({})", ev)
      connectionFailedMeter.mark()
      throw new CommandFailedException(ev)
  }

  def waitForRequest: Receive = handleErrors orElse {
    // Already connected, new request to send
    case (newRouter: ActorRef, request: TracedRequest) =>
      // Bind the new router
      router = newRouter

      val subContext = request.context.map { context => tracer.createSubcontext(context) }

      tracer.trace(subContext) {
        tracer.record(Annotation.ClientSend(RpcName("nlb", "http", request.method, request.path)))
        tracer.record(Annotation.ClientAddress(request.address))
      }

      request.timer.pause()

      server ! request.withNewContext(subContext).get

      context.become(waitForResponse(subContext))
      log.info("Received a new request to send")
  }

  def waitForResponse(subContext: Option[TraceContext]): Receive = handleErrors orElse {
    // Chunked responses
    case responseStart: ChunkedResponseStart =>
      router ! responseStart

      tracer.trace(subContext) {
        tracer.record(Annotation.Message("First chunk received"))
      }

      context.become(streamResponse(subContext, responseStart.response.status.intValue))
      chunkedResponsesMeter.mark()
      log.info("Received a chunked response start")

    // Unchunked responses
    case response@ HttpResponse(status, entity, _, _) =>
      router ! response

      tracer.trace(subContext) {
        tracer.record(Annotation.ClientRecv(Some(response.status.intValue)))
      }
      request.timer.start()

      context.become(waitForRequest)
      unchunkedResponsesMeter.mark()
      log.info("Received {} response with {} bytes", status, entity.buffer.length)

    // Specific errors
    case ev: Http.SendFailed =>
      log.warning("Send failed ({})", ev)
      sendFailedMeter.mark()
      throw new SendFailedException(ev)

    case ev: Timedout =>
      log.warning("Received Timedout ({})", ev)
      requestTimeoutMeter.mark()
      throw new RequestTimeoutException
  }

  def streamResponse(subContext: Option[TraceContext], statusCode: Int): Receive = handleErrors orElse {
    case chunk: MessageChunk =>
      router ! chunk
      log.info("Received a chunk")

    case responseEnd: ChunkedMessageEnd =>
      router ! responseEnd

      tracer.trace(subContext) {
        tracer.record(Annotation.ClientRecv(Some(statusCode)))
      }
      request.timer.start()

      context.become(waitForRequest)
      log.info("Received a chunked response end")
  }

  // All types of connection closing are already handled in handleErrors
  def closeConnection: Receive = handleErrors

  // Connection failures that can arise anytime (except in initial and connect modes)
  def handleErrors: Receive = {
    case Terminated(actor) if actor == server =>
      log.info("Connection expired ({})")
      connectionExpiredMeter.mark()
      throw new ConnectionExpiredException
    case ev: Http.ConnectionClosed =>
      log.info("Connection closed by server ({})", ev)
      connectionClosedMeter.mark()
      throw new ConnectionClosedException(ev)
  }
}

object ClientActor {
  def apply(destination: InetSocketAddress,
            initialTimeout: Duration,
            IOconnector: ActorRef)(implicit tracer: Tracer) = new ClientActor(destination, initialTimeout, IOconnector)
}

/**
 * Errors
 */

class InitialTimeoutException extends Exception {
  override def toString = "InitialTimeoutException"
}

// Thrown whenever the connection is closed by the server, intentionally or not
class ConnectionClosedException(command: Http.Event) extends Exception(command.toString)

// Thrown whenever the connection is closed by the Spray connector (usually when timing out)
class ConnectionExpiredException extends Exception

class CommandFailedException(event: Http.Event) extends Exception(event.toString)

class SendFailedException(event: Http.Event) extends Exception(event.toString)

class RequestTimeoutException extends Exception