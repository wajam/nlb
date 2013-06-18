package com.wajam.elb

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import com.wajam.elb.server.{ElbRouterActor, ServerService}
import com.wajam.elb.client.ElbClientActor

/**
 * User: Clément
 * Date: 2013-06-12
 */

object Elb extends App {

  implicit val system = ActorSystem("elb")

  implicit val config = ElbConfiguration.fromSystemProperties

  val router = new Router(config.getKnownPaths,
                          config.getZookeeperServers,
                          config.getResolvingService,
                          config.getHttpPort)

  ServerService.setTimeOut(config.getServerTimeout)
  ElbClientActor.setTimeOut(config.getClientTimeout)
  ElbRouterActor.setTimeOut(config.getRouterTimeout)

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props(ServerService(router)), name = "ServerHandler")

  IO(Http) ! Http.Bind(handler, interface = "localhost", port = 8080)

}
