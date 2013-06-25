package com.wajam.elb.server

import akka.util.Timeout
import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.wajam.elb.{ActorFactory, SprayConnectionPool, Router}


/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerService(pool: SprayConnectionPool,
                    router: Router,
                    implicit val timeout: Timeout) extends Actor with SprayActorLogging {

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case request: HttpRequest =>
      val peer = sender // since the Props creator is executed asyncly we need to save the sender ref
      context actorOf Props(ElbRouterActor(pool, peer, request, router))
  }
}

object ServerService extends ActorFactory {

  def apply(pool: SprayConnectionPool, router: Router) = new ServerService(pool, router, timeout)
}
