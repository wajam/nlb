package com.wajam.nlb.forwarder

import java.net.InetSocketAddress
import scala.concurrent.duration.Duration
import akka.actor.{ReceiveTimeout, Terminated, Actor, ActorRef, ActorLogging, Props}
import spray.http._
import spray.http.HttpHeaders.Connection
import com.wajam.tracing.{RpcName, Annotation, Tracer}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.util.{Timing, Router, TracedRequest}
import com.wajam.nlb.util.SprayUtils.sanitizeHeaders

class ForwarderActor(
    pool: SprayConnectionPool,
    router: Router,
    idleTimeout: Duration,
    implicit val tracer: Tracer)
  extends Actor
  with ActorLogging
  with Timing {

  private val connectionFallbacksMeter = metrics.meter("forwarder-connection-fallbacks", "fallbacks")

  def receive = {
    case request: HttpRequest =>
      val client = sender

      log.debug("Starting forwarding response for {}...", request)

      val totalTimeTimer = timer("round-trip-total-time")

      val destination = router.resolve(request.uri.path.toString)

      val connection = pool.getConnection(destination)

      withConnection(connection, client) { connection =>
        val tracedRequest = TracedRequest(request, totalTimeTimer).withNewHost(destination)

        tracer.trace(tracedRequest.context) {
          tracer.record(Annotation.ServerRecv(RpcName("nlb", "http", tracedRequest.method, tracedRequest.path)))
          tracer.record(Annotation.ServerAddress(tracedRequest.address))
        }

        // Set an initial timeout
        setTimeout()

        context.watch(connection)

        log.debug("Routing to node {} using connection {}", destination, connection)

        connection ! tracedRequest

        context.become(
          waitForResponse(client, destination, tracedRequest, connection)
        )
      }

    case ReceiveTimeout =>
      log.warning("Forwarder initial timeout")
      context.stop(self)
  }

  def waitForResponse(client: ActorRef,
                      destination: InetSocketAddress,
                      tracedRequest: TracedRequest,
                      clientConnection: ActorRef): Receive = sanitizeHeaders andThen {
    case Terminated(_) =>
      /* When the connection from the pool dies (possible race),
         and we haven't transmitted anything yet,
         we fallback on a brand new connection */
      val fallbackClientConnection = pool.getNewConnection(destination)

      withConnection(fallbackClientConnection, client) { connection =>
        connection ! tracedRequest

        connectionFallbacksMeter.mark()

        context.become(
          waitForResponse(client, destination, tracedRequest, connection)
        )
      }

    case response: HttpResponse =>
      client ! response

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.ServerSend(Some(response.status.intValue)))
      }
      tracedRequest.timer.stop()

      if(!response.connectionCloseExpected) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientConnection)
      }
      context.stop(self)

    case responseStart: ChunkedResponseStart =>
      // Renew the idle timeout
      setTimeout()

      log.debug("Forwarder received ChunkedResponseStart")

      client ! responseStart

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.Message("First chunk sent"))
      }

      context.unwatch(clientConnection)
      context.become(
        streamResponse(client, destination, tracedRequest, clientConnection)
      )
  }

  def streamResponse(client: ActorRef,
                     destination: InetSocketAddress,
                     tracedRequest: TracedRequest,
                     clientConnection: ActorRef): Receive = sanitizeHeaders andThen {

    case chunkEnd: ChunkedMessageEnd =>
      client ! chunkEnd

      log.debug("Forwarder received ChunkedMessageEnd")

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.ServerSend(None))
      }

      if(!chunkEnd.trailer.exists { case x: Connection if x.hasClose => true; case _ => false }) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientConnection)
      }
      tracedRequest.timer.stop()
      context.stop(self)

    case responseStart: ChunkedResponseStart =>
      // Renew the idle timeout
      setTimeout()

      log.debug("Forwarder received ChunkedResponseStart")

      client ! responseStart

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.Message("First chunk sent"))
      }

    case chunk: MessageChunk =>
      // Renew the idle timeout
      setTimeout()

      log.debug("Forwarder received MessageChunk")

      client ! chunk

    case ReceiveTimeout =>
      log.debug("Forwarder idle timeout")
      context.stop(self)
  }

  def setTimeout() = {
    context.setReceiveTimeout(idleTimeout)
  }

  def withConnection[A](connection: Option[ActorRef], client: ActorRef)(block: (ActorRef) => A) = {
    connection match {
      case Some(connection) =>
        block(connection)
      case None =>
        client ! HttpResponse(status = 503, entity = HttpEntity("Could not connect to destination"))
    }
  }
}

object ForwarderActor {

  def props(
      pool: SprayConnectionPool,
      router: Router,
      idleTimeout: Duration)
      (implicit tracer: Tracer) = Props(classOf[ForwarderActor], pool, router, idleTimeout, tracer)
}
