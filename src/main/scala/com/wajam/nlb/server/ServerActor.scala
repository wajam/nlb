package com.wajam.nlb.server

import akka.actor._
import akka.actor.SupervisorStrategy._
import spray.can.Http
import spray.http._
import HttpMethods.GET
import com.yammer.metrics.scala.Instrumented
import com.wajam.tracing.Tracer
import com.wajam.nlb.util.{ResolvingException, Router}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.Nlb.ForwarderFactory

class ServerActor(
    pool: SprayConnectionPool,
    router: Router,
    tracer: Tracer)
  extends Actor
  with ActorLogging
  with Instrumented {

  private val incomingRequestsMeter = metrics.meter("server-incoming-requests", "requests")
  private val timeoutMeter = metrics.meter("server-request-timeouts", "timeouts")

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
      val forwarder = context.actorOf(ForwarderFactory.apply)

      forwarder forward request

      incomingRequestsMeter.mark()

    case Timedout =>
      timeoutMeter.mark()
      sender ! HttpResponse(status = 500, entity = HttpEntity("Request timed out: couldn't get response in time"))
  }
}

object ServerActor {

  def props(
      pool: SprayConnectionPool,
      router: Router,
      tracer: Tracer) = Props(classOf[ServerActor], pool, router, tracer)
}
