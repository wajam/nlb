package com.wajam.nlb.client

import java.net.InetSocketAddress
import akka.actor._
import akka.util.duration._
import akka.util.Duration
import spray.can.Http
import spray.util.SprayActorLogging
import spray.http.{Timedout, HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import spray.can.client.ClientConnectionSettings

/**
 * User: Clément
 * Date: 2013-06-12
 */

/**
 * Actor handling an HTTP connection with a specific node.
 *
 * @param destination the destination node
 * @param idleTimeout the amount of time the connection is allowed to stay inactive
 * @param IOconnector the actor handling the underlying connection (usually IO(Http), except when testing)
 */
class ClientActor(destination: InetSocketAddress,
                  idleTimeout: Duration,
                  initialTimeout: Duration,
                  IOconnector: ActorRef) extends Actor with SprayActorLogging {
  import context.system

  var timeout: Cancellable = _
  val defaultTimeout = 3 seconds

  var router: ActorRef = _
  var server: ActorRef = _
  var request: HttpRequest = _

  // Initial timeout
  context.setReceiveTimeout(initialTimeout)
  
  /**
   * Behaviours
   */

  def receive =  {
    case req: HttpRequest =>
      // start by establishing a new HTTP connection
      router = sender
      request = req

      context.become(connect)

      IOconnector ! Http.Connect(destination.getHostString, port = destination.getPort, settings = Some(ClientConnectionSettings(system).copy(responseChunkAggregationLimit = 0)))

    case ReceiveTimeout =>
      log.warning("Initial timeout")
      throw new InitialTimeoutException
  }

  def connect: Receive = {
    case _: Http.Connected =>
      // once connected, we can send the request across the connection
      server = sender
      startTimeoutAndWaitForRequest
      self ! (router, request)

      // watch the Spray connector to monitor connection lifecycle
      context.watch(server)

    case ev: Http.CommandFailed =>
      log.warning("Command failed ({})", ev)
      throw new CommandFailedException(ev)
  }

  def waitForRequest: Receive = handleErrors orElse {
    // Already connected, new request to send
    case (newRouter: ActorRef, request: HttpRequest) =>
      clearTimeout()
      // Bind the new router
      router = newRouter
      server ! request
      context.become(waitForResponse)
      log.info("Received a new request to send")

    case poolTimeout: PoolTimeoutException =>
      server ! Http.Close
      log.warning("Closing connection and going out of the pool")
      throw poolTimeout
  }

  def waitForResponse: Receive = handleErrors orElse {
    // Chunked responses
    case responseStart: ChunkedResponseStart =>
      router ! responseStart
      context.become(streamResponse)
      log.info("Received a chunked response start")

    // Unchunked responses
    case response@ HttpResponse(status, entity, _, _) =>
      router ! response
      startTimeoutAndWaitForRequest
      log.info("Received {} response with {} bytes", status, entity.buffer.length)

    // Specific errors
    case ev: Http.SendFailed =>
      log.warning("Send failed ({})", ev)
      throw new SendFailedException(ev)

    case ev: Timedout =>
      log.warning("Received Timedout ({})", ev)
      throw new RequestTimeoutException
  }

  def streamResponse: Receive = handleErrors orElse {
    case chunk: MessageChunk =>
      router ! chunk
      log.info("Received a chunk")

    case responseEnd: ChunkedMessageEnd =>
      router ! responseEnd
      startTimeoutAndWaitForRequest
      log.info("Received a chunked response end")
  }

  // All types of connection closing are already handled in handleErrors
  def closeConnection: Receive = handleErrors

  // Connection failures that can arise anytime (except in initial and connect modes)
  def handleErrors: Receive = {
    case Terminated(actor) if actor eq server =>
      log.info("Connection expired ({})")
      throw new ConnectionExpiredException
    case ev: Http.ConnectionClosed =>
      log.info("Connection closed by server ({})", ev)
      throw new ConnectionClosedException(ev)
  }

  private def setTimeout(delay: Duration) {
    timeout = system.scheduler.scheduleOnce(delay, self, new PoolTimeoutException)
  }

  private def clearTimeout() {
    timeout.cancel()
  }

  private def startTimeoutAndWaitForRequest {
    setTimeout(idleTimeout)
    context.become(waitForRequest)
  }
}

object ClientActor {
  def apply(destination: InetSocketAddress,
            lifetime: Duration,
            initialTimeout: Duration,
            IOconnector: ActorRef) = new ClientActor(destination, lifetime, initialTimeout, IOconnector)
}

/**
 * Errors
 */

class InitialTimeoutException extends Exception {
  override def toString = "InitialTimeoutException"
}

// Thrown when the connection has been living too long and needs to be removed from pool
class PoolTimeoutException extends Exception {
  override def toString = "PoolTimeoutException"
}

// Thrown whenever the connection is closed by the server, intentionally or not
class ConnectionClosedException(command: Http.Event) extends Exception(command.toString)

// Thrown whenever the connection is closed by the Spray connector (usually when timing out)
class ConnectionExpiredException extends Exception

class CommandFailedException(event: Http.Event) extends Exception(event.toString)

class SendFailedException(event: Http.Event) extends Exception(event.toString)

class RequestTimeoutException extends Exception