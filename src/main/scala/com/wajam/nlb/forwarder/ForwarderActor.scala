package com.wajam.nlb.forwarder

import akka.actor.{ReceiveTimeout, Terminated, Actor, ActorRef}
import scala.concurrent.duration.Duration
import spray.http._
import spray.http.HttpHeaders.Connection
import spray.util.SprayActorLogging
import com.wajam.nrv.tracing.{RpcName, Annotation, Tracer}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.util.{Timing, Router, TracedRequest}
import com.wajam.nlb.util.SprayUtils.sanitizeHeaders

class ForwarderActor(pool: SprayConnectionPool,
                     client: ActorRef,
                     request: HttpRequest,
                     router: Router,
                     idleTimeout: Duration)(implicit tracer: Tracer)
    extends Actor
    with SprayActorLogging
    with Timing {

  log.debug("Starting forwarding response for {}...", request)

  val totalTimeTimer = timer("round-trip-total-time")

  val tracedRequest = TracedRequest(request, totalTimeTimer)

  tracer.trace(tracedRequest.context) {
    tracer.record(Annotation.ServerRecv(RpcName("nlb", "http", tracedRequest.method, tracedRequest.path)))
    tracer.record(Annotation.ServerAddress(tracedRequest.address))
  }

  val destination = tracer.trace(tracedRequest.context) {
    tracer.time("Resolving destination") {
      router.resolve(tracedRequest.path)
    }
  }

  // clientActor is the actor handling the connection with the server
  // Not to be mistaken with client, which is *our* client
  val clientActor = pool.getConnection(destination)

  // Set an initial timeout
  setTimeout()

  context.watch(clientActor)

  log.debug("Routing to node {} using connection {}", destination, clientActor)

  clientActor ! (self, tracedRequest)

  def receive = sanitizeHeaders andThen {
    case Terminated(_) =>
      /* When the connection from the pool dies (possible race),
         and we haven't transmitted anything yet,
         we fallback on a brand new connection */
      val fallbackClientActor = pool.getNewConnection(destination)
      fallbackClientActor ! (self, tracedRequest)

    case ReceiveTimeout =>
      log.warning("Forwarder initial timeout")
      context.stop(self)

    case response: HttpResponse =>
      client ! response

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.ServerSend(Some(response.status.intValue)))
      }
      tracedRequest.timer.stop()

      if(!response.connectionCloseExpected) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientActor)
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

      context.unwatch(clientActor)
      context.become(streaming)
  }

  def streaming: Receive = sanitizeHeaders andThen {

    case chunkEnd: ChunkedMessageEnd =>
      client ! chunkEnd

      log.debug("Forwarder received ChunkedMessageEnd")

      tracer.trace(tracedRequest.context) {
        tracer.record(Annotation.ServerSend(None))
      }

      if(!chunkEnd.trailer.exists { case x: Connection if x.hasClose => true; case _ => false }) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientActor)
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
}

object ForwarderActor {

  def apply(pool: SprayConnectionPool,
            client: ActorRef,
            request: HttpRequest,
            router: Router,
            idleTimeout: Duration)(implicit tracer: Tracer) = new ForwarderActor(pool, client, request, router, idleTimeout)
}
