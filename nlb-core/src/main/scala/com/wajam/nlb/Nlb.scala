package com.wajam.nlb

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.util.duration._
import spray.can.Http
import com.wajam.nlb.server.{ForwarderActor, ServerActor}
import com.wajam.nlb.client.{SprayConnectionPool, ClientActor}

/**
 * User: Clément
 * Date: 2013-06-12
 */

object Nlb extends App {

  implicit val system = ActorSystem("nlb")

  implicit val config = NlbConfiguration.fromSystemProperties

  val router = new Router(config.getKnownPaths,
                          config.getZookeeperServers,
                          config.getResolvingService,
                          config.getHttpPort,
                          config.getLocalNodePort)

  val pool = new SprayConnectionPool(config.getConnectionIdleTimeOut milliseconds, config.getConnectionInitialTimeOut milliseconds, config.getConnectionPoolMaxSize, system)

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props(ServerActor(pool, router)), name = "ServerHandler")

  IO(Http) ! Http.Bind(handler, interface = "localhost", port = 8080)

}
