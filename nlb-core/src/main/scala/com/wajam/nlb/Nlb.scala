package com.wajam.nlb

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import akka.util.duration._
import spray.can.Http
import com.wajam.nlb.server.ServerActor
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nrv.tracing.{NullTraceRecorder, LoggingTraceRecorder, ConsoleTraceRecorder, Tracer}
import com.wajam.nrv.scribe.ScribeTraceRecorder

/**
 * User: Clément
 * Date: 2013-06-12
 */

object Nlb extends App {

  implicit val system = ActorSystem("nlb")

  implicit val config = NlbConfiguration.fromSystemProperties

  val traceRecorder = if(config.isTraceEnabled) {
    config.getTraceRecorder match {
      case "scribe" =>
        new ScribeTraceRecorder(config.getTraceScribeHost, config.getTraceScribePort, config.getTraceScribeSamplingRate)
      case "logger" =>
        LoggingTraceRecorder
      case "console" =>
        ConsoleTraceRecorder
      case "null" =>
        NullTraceRecorder
    }
  }
  else NullTraceRecorder

  implicit val tracer = new Tracer(traceRecorder)

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
