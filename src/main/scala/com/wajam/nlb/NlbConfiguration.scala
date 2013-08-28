package com.wajam.nlb

import com.typesafe.config._
import scala.collection.JavaConversions._
import com.typesafe.config.ConfigException.Missing

class NlbConfiguration(config: Config) {

  def optional[T](getter: => T, fallback: T): T = {
    try {
      getter
    }
    catch {
      case e: Missing   => fallback
      case e: Exception => throw e
    }
  }

  def getEnvironment: String = {
    optional(config.getString("nlb.environment"), "local")
  }

  def getZookeeperServers: String = {
    config.getString("nlb.resolving.zookeeper-servers")
  }

  def getResolvingService: String = {
    config.getString("nlb.resolving.service")
  }

  def getNodeHttpPort: Int = {
    optional(config.getInt("nlb.node.http-port"), 8899)
  }

  def getLocalNodePort: Int = {
    optional(config.getInt("nlb.resolving.localnode-port"), 9701)
  }

  def getAskTimeout: Long = {
    optional(config.getMilliseconds("nlb.ask-timeout"), 200)
  }

  def getKnownPaths: List[String] = {
    config.getStringList("nlb.known-paths").toList
  }

  def getServerListenInterface: String = {
    optional(config.getString("nlb.server.listen-interface"), "0.0.0.0")
  }

  def getServerListenPort: Int = {
    config.getInt("nlb.server.listen-port")
  }

  def getClientInitialTimeout: Long = {
    optional(config.getMilliseconds("nlb.client.initial-timeout"), 1000)
  }

  def getConnectionPoolMaxSize: Int = {
    optional(config.getInt("nlb.connection-pool.max-size"), 100)
  }

  def getGraphiteServerAddress: String = {
    config.getString("nlb.graphite.server-address")
  }

  def getGraphiteServerPort: Int = {
    config.getInt("nlb.graphite.server-port")
  }

  def getGraphiteUpdatePeriodInSec: Int = {
    config.getInt("nlb.graphite.update-period-sec")
  }

  def isGraphiteEnabled: Boolean = {
    optional(config.getBoolean("nlb.graphite.enabled"), false)
  }

  def isTraceEnabled: Boolean = {
    optional(config.getBoolean("nlb.trace.enabled"), true)
  }

  def getTraceRecorder: String = {
    optional(config.getString("nlb.trace.recorder"), "console")
  }

  def getTraceScribeHost: String = {
    config.getString("nlb.trace.scribe.host")
  }

  def getTraceScribePort: Int = {
    config.getInt("nlb.trace.scribe.port")
  }

  def getTraceSamplingRate: Int = {
    optional(config.getInt("nlb.trace.sampling-rate"), 1000)
  }

  def getForwarderIdleTimeout: Long = {
    optional(config.getMilliseconds("nlb.forwarder.idle-timeout"), 5000)
  }
}
