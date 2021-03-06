package com.wajam.nlb.client

import java.net.InetSocketAddress
import akka.actor._
import spray.can.Http
import spray.http._
import StatusCodes.{Success, ServerError, ClientError}
import spray.can.client.ClientConnectionSettings
import com.yammer.metrics.scala.Instrumented
import com.wajam.tracing.{TraceContext, RpcName, Annotation, Tracer}
import com.wajam.nlb.util.{TracedRequest, Timing}
import ClientActor._

/**
 * Actor handling an HTTP connection with a specific node.
 *
 * @param destination the destination node
 * @param IOconnector the actor handling the underlying connection (usually IO(Http), except when testing)
 */
class ClientActor(
    destination: InetSocketAddress,
    IOconnector: ActorRef,
    implicit val tracer: Tracer)
  extends Actor
  with ActorLogging
  with Instrumented
  with Timing {

  import context.system

  private val connectionFailedMeter =  metrics.meter("error-connection-failed", "fails")
  private val sendFailedMeter =        metrics.meter("error-send-failed", "fails")
  private val requestTimeoutMeter =    metrics.meter("error-request-timeout", "fails")
  private val connectionExpiredMeter = metrics.meter("error-connection-expired", "expirations")
  private val connectionClosedMeter =  metrics.meter("error-connection-closed", "closings")

  private val openConnectionsCounter =  metrics.counter("open-connections", "connections")
  private val chunkedResponsesMeter =   metrics.meter("chunk-responses", "responses")
  private val unchunkedResponsesMeter = metrics.meter("unchunk-responses", "responses")
  private val totalChunksMeter =        metrics.meter("total-chunks-transferred", "chunks")

  private val http2xxMeter = metrics.meter("http-responses-2xx", "responses")
  private val http4xxMeter = metrics.meter("http-responses-4xx", "responses")
  private val http5xxMeter = metrics.meter("http-responses-5xx", "responses")

  private val clusterReplyTimer =  timer("cluster-reply-time")
  private val chunkTransferTimer = timer("chunk-transfer-time")

  var forwarder: Option[ActorRef] = None
  var server: ActorRef = _

  // Open connection
  IOconnector ! Http.Connect(destination.getHostString, port = destination.getPort, settings = Some(ClientConnectionSettings(system).copy(responseChunkAggregationLimit = 0)))

  /**
   * Behaviours
   */

  def receive = {
    case _: Http.Connected =>
      // Once connected, keep a reference to the connector
      server = sender

      // Wait for a request to arrive
      context.become(waitForRequest)

      // Notify the PoolSupervisor that the connection is established
      context.parent ! Connected

      openConnectionsCounter += 1

      // watch the Spray connector to monitor connection lifecycle
      context.watch(server)

    case _: Http.CommandFailed =>
      connectionFailedMeter.mark()

      // Notify the PoolSupervisor that the connection has failed
      context.parent ! ConnectionFailed

      dispatchError(new CommandFailedException)
  }

  def waitForRequest: Receive = handleErrors orElse {
    // Already connected, new request to send
    case request: TracedRequest =>
      // Bind the new forwarder
      forwarder = Some(sender)

      implicit val subContext = request.context.map { context => tracer.createSubcontext(context) }

      tracer.trace(subContext) {
        tracer.record(Annotation.ClientSend(RpcName("nlb", "http", request.method, request.path)))
        tracer.record(Annotation.ClientAddress(request.address))
      }

      clusterReplyTimer.start()

      server ! request.withNewContext(subContext).get

      context.become(waitForResponse)
      log.debug("Received a new request to send")
  }

  def waitForResponse(implicit subContext: Option[TraceContext]): Receive = handleErrors orElse {
    // Chunked responses
    case responseStart: ChunkedResponseStart =>
      forward(responseStart)

      tracer.trace(subContext) {
        tracer.record(Annotation.Message("First chunk received"))
      }
      clusterReplyTimer.stop()
      chunkTransferTimer.start()

      context.become(streamResponse(responseStart.response.status.intValue))
      chunkedResponsesMeter.mark()
      log.debug("Received a chunked response start")

    // Unchunked responses
    case response @ HttpResponse(statusCode, _, _, _) =>
      val status = statusCode.intValue

      handleResponse(response, status)

      clusterReplyTimer.stop()
      unchunkedResponsesMeter.mark()

      log.debug("Received {} response", status)

    // Specific errors
    case _: Http.SendFailed =>
      sendFailedMeter.mark()

      dispatchError(new SendFailedException)
  }

  def streamResponse(status: Int)(implicit subContext: Option[TraceContext]): Receive = handleErrors orElse {
    case chunk: MessageChunk =>
      forward(chunk)
      log.debug("Received a chunk")
      totalChunksMeter.mark()

    case responseEnd: ChunkedMessageEnd =>
      handleResponse(responseEnd, status)

      chunkTransferTimer.stop()

      log.debug("Received a chunked response end")
  }

  // All types of connection closing are already handled in handleErrors
  def closeConnection: Receive = handleErrors

  // Common way of handling HttpResponse and ChunkedMessageEnd
  def handleResponse(response: HttpResponsePart, status: Int)(implicit subContext: Option[TraceContext]) = {
    tracer.trace(subContext) {
      tracer.record(Annotation.ClientRecv(Some(status)))
    }

    // The connection will potentially be placed back in the pool:
    // make it ready to accept new requests
    context.become(waitForRequest)

    forward(response)
  }

  // Connection failures that can arise anytime (except in initial and connect modes)
  def handleErrors: Receive = {
    case Terminated(actor) if actor == server =>
      connectionExpiredMeter.mark()

      dispatchError(new ConnectionExpiredException)

    case Timedout(_) =>
      requestTimeoutMeter.mark()

      dispatchError(new RequestTimeoutException)

    case c: Http.ConnectionClosed =>
      connectionClosedMeter.mark()

      dispatchError(new ConnectionClosedException)
  }

  def forward(msg: Any): Unit = {
    forwarder.map { forwarder =>
      forwarder ! msg

      val status = msg match {
        case responseStart: ChunkedResponseStart =>
          Some(responseStart.message.status)
        case response: HttpResponse =>
          Some(response.status)
        case _ =>
          None
      }

      status.foreach {
        case Success(_) =>
          http2xxMeter.mark()
        case ClientError(_) =>
          http4xxMeter.mark()
        case ServerError(_) =>
          http5xxMeter.mark()
        case _ =>
      }
    }
  }

  def dispatchError(e: Exception): Unit = {
    // Logging is done at the Forwarder level
    forward(e)
    throw e
  }

  override def postStop(): Unit = {
    openConnectionsCounter -= 1
  }
}

object ClientActor {
  def props(
      IOconnector: ActorRef,
      tracer: Tracer)(
      destination: InetSocketAddress
      ) = Props(classOf[ClientActor], destination, IOconnector, tracer)

  object Connected
  object ConnectionFailed

  abstract class ClientException(message: String) extends Exception(message)

  class ConnectionClosedException extends ClientException("Connection closed")

  // Thrown whenever the connection is closed by the Spray connector (usually when timing out)
  class ConnectionExpiredException extends ClientException("Connection expired")

  class CommandFailedException extends ClientException("Could not connect to server")

  class SendFailedException extends ClientException("Could not write on connection")

  class RequestTimeoutException extends ClientException("Request timed out")
}
