package com.wajam.elb.client

import akka.actor._
import akka.io.IO
import spray.can.Http
import spray.util.SprayActorLogging
import akka.util.duration._
import spray.http.{Timedout, HttpRequest, HttpResponse, ChunkedResponseStart, MessageChunk, ChunkedMessageEnd}
import akka.util.Timeout
import spray.can.client.ClientConnectionSettings
import java.net.InetAddress

/**
 * User: Clément
 * Date: 2013-06-12
 */

class ElbClientActor(host: InetAddress, port: Int, implicit val timeout: Timeout) extends Actor with SprayActorLogging {
  import context.system

  def receive: Receive = {
    case request: HttpRequest =>
      // start by establishing a new HTTP connection
      IO(Http) ! Http.Connect(host.getHostAddress, port = port, settings = Some(ClientConnectionSettings(system).copy(responseChunkAggregationLimit = 0)))
      context.become(connecting(sender, request))
  }

  def connecting(router: ActorRef, request: HttpRequest): Receive = {
    case _: Http.Connected =>
      // once connected, we can send the request across the connection
      sender ! request
      context.become(waitingForResponse(router))

    case Http.CommandFailed(Http.Connect(address, _, _, _)) =>
      log.warning("Could not connect to {}", address)
      router ! Status.Failure(new RuntimeException("Connection error"))
      context.stop(self)
  }

  def waitingForResponse(router: ActorRef): Receive = {

    // Chunked responses
    case responseStart: ChunkedResponseStart =>
      router ! responseStart
      log.info("Received a chunked response start")

    case chunk: MessageChunk =>
      router ! chunk
      log.info("Received a chunk")

    case responseEnd: ChunkedMessageEnd =>
      log.info("Received a chunked response end")
      router ! responseEnd
      sender ! Http.Close
      context.become(waitingForClose(router))

    // Unchunked responses
    case response@ HttpResponse(status, entity, _, _) =>
      log.info("Received {} response with {} bytes", status, entity.buffer.length)
      router ! response
      sender ! Http.Close
      context.become(waitingForClose(router))

    // Errors
    case ev@(Http.SendFailed(_) | Timedout(_))=>
      log.warning("Received {}", ev)
      router ! Status.Failure(new RuntimeException("Request error"))
      context.stop(self)
  }

  def waitingForClose(commander: ActorRef): Receive = {
    case ev: Http.ConnectionClosed =>
      log.debug("Connection closed ({})", ev)
      commander ! Status.Success
      context.stop(self)

    case Http.CommandFailed(Http.Close) =>
      log.warning("Could not close connection")
      commander ! Status.Failure(new RuntimeException("Connection close error"))
      context.stop(self)
  }
}

object ElbClientActor {

  def apply(host: InetAddress, port: Int, timeout: Int)(implicit system: ActorSystem) = system.actorOf(Props(new ElbClientActor(host, port, timeout.seconds)))
}