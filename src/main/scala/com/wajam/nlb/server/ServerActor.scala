package com.wajam.nlb.server

import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.yammer.metrics.scala.Instrumented
import com.wajam.nrv.tracing.{RpcName, Annotation, Tracer}
import com.wajam.nlb.util.{TracedRequest, Router}
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.forwarder.ForwarderActor

/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerActor(pool: SprayConnectionPool, router: Router)(implicit tracer: Tracer)
  extends Actor
  with SprayActorLogging
  with Instrumented {

  private val incomingRequestsMeter = metrics.meter("server-incoming-requests", "requests")

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case req: HttpRequest =>
      val client = sender
      val request = TracedRequest(req)

      context actorOf Props(ForwarderActor(pool, client, request, router))

      tracer.trace(request.context) {
        tracer.record(Annotation.ServerRecv(RpcName("nlb", "http", request.method, request.path)))
        tracer.record(Annotation.ServerAddress(request.address))
      }

      incomingRequestsMeter.mark()
  }
}

object ServerActor {

  def apply(pool: SprayConnectionPool, router: Router)(implicit tracer: Tracer) = new ServerActor(pool, router)
}
