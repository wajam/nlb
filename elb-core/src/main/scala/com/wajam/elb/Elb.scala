package com.wajam.elb

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import spray.can.Http
import com.wajam.elb.server.ServerService

/**
 * User: Clément
 * Date: 2013-06-12
 */

object Elb extends App {

  implicit val system = ActorSystem("elb")

  implicit val config = ElbConfiguration.fromSystemProperties

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props(new ServerService), name = "ServerHandler")

  IO(Http) ! Http.Bind(handler, interface = "localhost", port = 8080)

}
