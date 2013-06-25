package com.wajam.elb.client

import akka.actor._
import akka.io.IO
import spray.can.Http
import spray.util.SprayActorLogging
import spray.http.{Timedout, HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import akka.util.{Duration, Timeout}
import spray.can.client.ClientConnectionSettings
import java.net.{InetSocketAddress}
import com.wajam.elb.ActorFactory

/**
 * User: Clément
 * Date: 2013-06-12
 */

class ElbClientActor(destination: InetSocketAddress, lifetime: Duration, implicit val timeout: Timeout) extends Actor with SprayActorLogging {
  import context.system

  def receive: Receive = {
    case request: HttpRequest =>
      // start by establishing a new HTTP connection
      val peer = sender
      context.become(connecting(peer, request))
      IO(Http) ! Http.Connect(destination.getHostString, port = destination.getPort, settings = Some(ClientConnectionSettings(system).copy(responseChunkAggregationLimit = 0)))
  }

  def connecting(router: ActorRef, request: HttpRequest): Receive = {
    case _: Http.Connected =>
      // once connected, we can send the request across the connection
      val peer = sender
      switchToWaiting(peer)
      self ! (router, request)

    case Http.CommandFailed(Http.Connect(address, _, _, _)) =>
      router ! Status.Failure(new RuntimeException("Connection error"))
      context.stop(self)
      log.warning("Could not connect to {}", address)
  }

  def waiting(poison: Cancellable, server: ActorRef): Receive = {
    // Already connected, new request to send
    case (router: ActorRef, request: HttpRequest) =>
      poison.cancel()
      context.become(waitingForResponse(router, server))
      server ! request
      log.info("Received a new request to send")

    case ElbClientActor.Close =>
      context.become(closing)
      server ! Http.Close

    case ev: Http.ConnectionClosed =>
      log.debug("Connection closed ({})", ev)
      context.stop(self)
  }

  def waitingForResponse(router: ActorRef, server: ActorRef): Receive = {
    // Chunked responses
    case responseStart: ChunkedResponseStart =>
      context.become(streaming(router))
      router ! responseStart
      log.info("Received a chunked response start")

    // Unchunked responses
    case response@ HttpResponse(status, entity, _, _) =>
      switchToWaiting(server)
      router ! response
      log.info("Received {} response with {} bytes", status, entity.buffer.length)

    // Errors
    case ev@(Http.SendFailed(_) | Timedout(_))=>
      switchToWaiting(server)
      router ! Status.Failure(new RuntimeException("Request error"))
      log.warning("Received {}", ev)
  }

  def streaming(router: ActorRef): Receive = {
    case chunk: MessageChunk =>
      router ! chunk
      log.info("Received a chunk")

    case responseEnd: ChunkedMessageEnd =>
      val peer = sender
      router ! responseEnd
      log.info("Received a chunked response end")
      switchToWaiting(peer)
  }

  def closing: Receive = {
    case ev: Http.ConnectionClosed =>
      log.debug("Connection closed ({})", ev)
      context.stop(self)

    case Http.CommandFailed(Http.Close) =>
      log.warning("Could not close connection")
      context.stop(self)

    case _ =>
      context.stop(self)
  }

  private def switchToWaiting(server: ActorRef) {
    val poison = system.scheduler.scheduleOnce(lifetime, self, ElbClientActor.Close)
    context.become(waiting(poison, server))
  }
}

object ElbClientActor extends ActorFactory {
  def apply(destination: InetSocketAddress, lifetime: Duration) = new ElbClientActor(destination, lifetime, timeout)

  case class Close()
}
