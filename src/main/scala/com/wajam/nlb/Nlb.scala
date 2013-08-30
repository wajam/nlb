package com.wajam.nlb

import akka.actor.{ActorSystem, Props}
import akka.io.IO
import scala.concurrent.duration._
import spray.can.Http
import com.wajam.nlb.server.ServerActor
import com.wajam.nlb.client.SprayConnectionPool
import com.wajam.nlb.util.Router
import com.wajam.nrv.tracing.{NullTraceRecorder, LoggingTraceRecorder, ConsoleTraceRecorder, Tracer}
import com.wajam.nrv.scribe.ScribeTraceRecorder
import com.wajam.nrv.Logging
import com.typesafe.config.ConfigFactory
import com.yammer.metrics.reporting.GraphiteReporter
import java.util.concurrent.TimeUnit
import java.net.InetAddress
import spray.can.server.ServerSettings
import spray.http.HttpHeaders.Host

object Nlb extends App with Logging {

  implicit val system = ActorSystem("nlb")

  val defaultConfig = ConfigFactory.load("reference")
  val applicationConfig = ConfigFactory.load("application").withFallback(defaultConfig)

  val config = new NlbConfiguration(applicationConfig)

  val hostname = InetAddress.getLocalHost.getHostName

  val traceRecorder = if(config.isTraceEnabled) {
    config.getTraceRecorder match {
      case "scribe" =>
        new ScribeTraceRecorder(config.getTraceScribeHost, config.getTraceScribePort)
      case "logger" =>
        LoggingTraceRecorder
      case "console" =>
        ConsoleTraceRecorder
      case "null" =>
        NullTraceRecorder
    }
  }
  else NullTraceRecorder

  // Metrics binding to Graphite
  if (config.isGraphiteEnabled) {
    GraphiteReporter.enable(
      config.getGraphiteUpdatePeriodInSec, TimeUnit.SECONDS,
      config.getGraphiteServerAddress, config.getGraphiteServerPort,
      config.getEnvironment + ".nlb." +
        "%s_%d".format(hostname.replace(".", "-"), config.getServerListenPort))
  }

  implicit val tracer = new Tracer(traceRecorder, samplingRate = config.getTraceSamplingRate)

  val router = new Router(config.getKnownPaths,
                          config.getZookeeperServers,
                          config.getResolvingService,
                          config.getNodeHttpPort,
                          config.getLocalNodePort)

  val pool = new SprayConnectionPool(config.getClientInitialTimeout milliseconds, config.getConnectionPoolMaxSize, config.getClientInitialTimeout, system)

  val forwarderIdleTimeout = config.getForwarderIdleTimeout milliseconds

  // the handler actor replies to incoming HttpRequests
  val handler = system.actorOf(Props(ServerActor(pool, router, forwarderIdleTimeout)), name = "ServerHandler")

  log.info("Dynamically setting server hostname: " + hostname)

  val serverSettings = ServerSettings(system).copy(
    defaultHostHeader = Host(
      hostname,
      config.getServerListenPort
    )
  )

  IO(Http) ! Http.Bind(handler, interface = config.getServerListenInterface, port = config.getServerListenPort, settings = Some(serverSettings))

}
