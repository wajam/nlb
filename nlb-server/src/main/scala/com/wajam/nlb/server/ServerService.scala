package com.wajam.nlb.server

import akka.util.Timeout
import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.wajam.nlb.{Router}
import com.wajam.nlb.client.SprayConnectionPool


/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerService(pool: SprayConnectionPool,
                    router: Router) extends Actor with SprayActorLogging {

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case request: HttpRequest =>
      val peer = sender // since the Props creator is executed asyncly we need to save the sender ref
      context actorOf Props(ForwarderActor(pool, peer, request, router))
  }
}

object ServerService {

  def apply(pool: SprayConnectionPool, router: Router) = new ServerService(pool, router)
}
