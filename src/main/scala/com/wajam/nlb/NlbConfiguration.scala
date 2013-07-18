package com.wajam.nlb

/**
 * User: Clément
 * Date: 2013-06-17
 * Time: 09:50
 */

import com.typesafe.config._
import scala.collection.JavaConversions._

class NlbConfiguration(config: Config) {
  def getEnvironment: String = {
    config.getString("nlb.environment")
  }

  def getZookeeperServers: String = {
    config.getString("nlb.resolving.zookeeper-servers")
  }

  def getResolvingService: String = {
    config.getString("nlb.resolving.service")
  }

  def getNodeHttpPort: Int = {
    config.getInt("nlb.node.http-port")
  }

  def getLocalNodePort: Int = {
    config.getInt("nlb.resolving.localnode-port")
  }

  def getAskTimeout: Long = {
    config.getMilliseconds("nlb.ask-timeout")
  }

  def getKnownPaths: List[String] = {
    config.getStringList("nlb.known-paths").toList
  }

  def getServerListenInterface: String = {
    config.getString("nlb.server.listen-interface")
  }

  def getServerListenPort: Int = {
    config.getInt("nlb.server.listen-port")
  }

  def getClientInitialTimeout: Long = {
    config.getMilliseconds("nlb.client.initial-timeout")
  }

  def getConnectionPoolMaxSize: Int = {
    config.getInt("nlb.connection-pool.max-size")
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
    config.getBoolean("nlb.graphite.enabled")
  }

  def isTraceEnabled: Boolean = {
    config.getBoolean("nlb.trace.enabled")
  }

  def getTraceRecorder: String = {
    config.getString("nlb.trace.recorder")
  }

  def getTraceScribeHost: String = {
    config.getString("nlb.trace.scribe.host")
  }

  def getTraceScribePort: Int = {
    config.getInt("nlb.trace.scribe.port")
  }

  def getTraceScribeSamplingRate: Int = {
    config.getInt("nlb.trace.scribe.sampling-rate")
  }
}
