package com.wajam.nlb.server

import akka.actor._
import akka.actor.SupervisorStrategy._
import spray.can.Http
import spray.http._
import com.yammer.metrics.scala.Instrumented
import com.wajam.nrv.tracing.{RpcName, Annotation, Tracer}
import com.wajam.nlb.util.{ResolvingException, TracedRequest, Router}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.forwarder.ForwarderActor
import spray.util.SprayActorLogging

class ServerActor(pool: SprayConnectionPool, router: Router)(implicit tracer: Tracer)
  extends Actor
  with SprayActorLogging
  with Instrumented {

  private val incomingRequestsMeter = metrics.meter("server-incoming-requests", "requests")

  override val supervisorStrategy =
    OneForOneStrategy(loggingEnabled = false) {
      case _: ResolvingException => Stop
      case _: Exception          => Stop
    }

  var client: Option[ActorRef] = None

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case req: HttpRequest =>
      client = Some(sender)

      val request = TracedRequest(req)

      val forwarder = context actorOf Props(ForwarderActor(pool, client.get, request, router))
      context.watch(forwarder)

      tracer.trace(request.context) {
        tracer.record(Annotation.ServerRecv(RpcName("nlb", "http", request.method, request.path)))
        tracer.record(Annotation.ServerAddress(request.address))
      }

      incomingRequestsMeter.mark()

    case Terminated(child) =>
      client.map { client =>
        log.debug("Forwarder died, closing connection")
        client ! Http.Close
      }
  }
}

object ServerActor {

  def apply(pool: SprayConnectionPool, router: Router)(implicit tracer: Tracer) = new ServerActor(pool, router)
}
