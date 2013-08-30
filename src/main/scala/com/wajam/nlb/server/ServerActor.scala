package com.wajam.nlb.server

import akka.actor._
import akka.actor.SupervisorStrategy._
import scala.concurrent.duration.Duration
import spray.can.Http
import spray.http._
import HttpMethods.GET
import com.yammer.metrics.scala.Instrumented
import com.wajam.nrv.tracing.Tracer
import com.wajam.nlb.util.{ResolvingException, Router}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.forwarder.ForwarderActor

class ServerActor(
    pool: SprayConnectionPool,
    router: Router,
    forwarderIdleTimeout: Duration)
    (implicit tracer: Tracer)
  extends Actor
  with ActorLogging
  with Instrumented {

  private val incomingRequestsMeter = metrics.meter("server-incoming-requests", "requests")

  override val supervisorStrategy =
    OneForOneStrategy(loggingEnabled = false) {
      case _: ResolvingException => Stop
      case _: Exception          => Stop
    }

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected =>
      sender ! Http.Register(self)

    // health check
    case HttpRequest(GET, Uri.Path("/health"), _, _, _) =>
      sender ! HttpResponse(entity = HttpEntity("Ok"))

    case request: HttpRequest =>
      val client = sender

      context actorOf Props(ForwarderActor(pool, client, request, router, forwarderIdleTimeout))

      incomingRequestsMeter.mark()
  }
}

object ServerActor {

  def apply(pool: SprayConnectionPool, router: Router, forwarderIdleTimeout: Duration)(implicit tracer: Tracer) = new ServerActor(pool, router, forwarderIdleTimeout)
}
