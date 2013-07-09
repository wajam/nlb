package com.wajam.nlb.server

import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.wajam.nlb.Router
import com.wajam.nlb.client.SprayConnectionPool
import com.yammer.metrics.scala.Instrumented


/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerActor(pool: SprayConnectionPool, router: Router) extends Actor
                                                             with SprayActorLogging
                                                             with Instrumented {

  private val incomingRequestsTimer = metrics.meter("server-incoming-requests", "requests")

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case request: HttpRequest =>
      val client = sender
      context actorOf Props(ForwarderActor(pool, client, request, router))
      incomingRequestsTimer.mark()
  }
}

object ServerActor {

  def apply(pool: SprayConnectionPool, router: Router) = new ServerActor(pool, router)
}
