package com.wajam.nlb.forwarder

import akka.actor.{Terminated, Actor, ActorRef, Status}
import spray.http._
import spray.http.HttpHeaders.Connection
import spray.util.SprayActorLogging
import com.wajam.nrv.tracing.{Annotation, Tracer}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.util.{Router, TracedRequest}

class ForwarderActor(pool: SprayConnectionPool,
                     client: ActorRef,
                     request: TracedRequest,
                     router: Router)(implicit tracer: Tracer)
    extends Actor
    with SprayActorLogging {

  log.debug("Starting forwarding response for {}...", request)

  val destination = tracer.trace(request.context) {
    tracer.time("Resolving destination") {
      router.resolve(request.path)
    }
  }

  // clientActor is the actor handling the connection with the server
  // Not to be mistaken with client, which is *our* client
  val clientActor = pool.getConnection(destination)

  context.watch(clientActor)

  log.debug("Routing to node {} using connection {}", destination, clientActor)

  clientActor ! (self, request)

  def receive = {
    case Terminated(_) =>
      /* When the connection from the pool dies (possible race),
         and we haven't transmitted anything yet,
         we fallback on a brand new connection */
      val fallbackClientActor = pool.getNewConnection(destination)
      fallbackClientActor ! (self, request)
      context.become(forwarding)
    case msg =>
      /* As soon as we receive something, we unwatch the connection.
         Further errors will be handled using Spray events */
      context.unwatch(clientActor)
      context.become(forwarding)
      self ! msg
  }

  def forwarding: Receive = {
    // Transmission finished, stop the router and pool the connection
    case response: HttpResponse =>
      client ! response

      tracer.trace(request.context) {
        tracer.record(Annotation.ServerSend(Some(response.status.intValue)))
      }

      if(!response.connectionCloseExpected) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientActor)
      }
      request.timer.stop()
      context.stop(self)

    case chunkEnd: ChunkedMessageEnd =>
      client ! chunkEnd

      tracer.trace(request.context) {
        tracer.record(Annotation.ServerSend(None))
      }

      if(!chunkEnd.trailer.exists { case x: Connection if x.hasClose => true; case _ => false }) {
        log.debug("Pooling connection")
        pool.poolConnection(destination, clientActor)
      }
      request.timer.stop()
      context.stop(self)

    case responseStart: ChunkedResponseStart =>
      client ! responseStart

      tracer.trace(request.context) {
        tracer.record(Annotation.Message("First chunk sent"))
      }

    // Error or connection successfully closed, stop the router without pooling the connection
    case Status.Failure | Status.Success =>
      context.stop(self)

    case response =>
      client ! response
  }
}

object ForwarderActor {

  def apply(pool: SprayConnectionPool, client: ActorRef, message: TracedRequest, router: Router)(implicit tracer: Tracer) = new ForwarderActor(pool, client, message, router)
}
