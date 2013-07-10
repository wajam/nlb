package com.wajam.nlb

/**
 * User: Clément
 * Date: 2013-06-17
 * Time: 09:50
 */

import org.apache.commons.configuration.tree.OverrideCombiner
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration, CombinedConfiguration}
import scala.collection.JavaConversions._

class NlbConfiguration(config: Configuration) {
  def getZookeeperServers: String = {
    config.getString("nlb.zookeeper.servers")
  }

  def getResolvingService: String = {
    config.getString("nlb.resolving.service")
  }

  def getHttpPort: Int = {
    config.getInt("nlb.http.port")
  }

  def getLocalNodePort: Int = {
    config.getInt("nlb.localnode.port")
  }

  def getKnownPaths: List[String] = {
    config.getList("nlb.knownpaths").toList.asInstanceOf[List[String]]
  }

  def getServerTimeout: Int = {
    config.getInt("nlb.server.timeout")
  }

  def getRouterTimeout: Int = {
    config.getInt("nlb.router.timeout")
  }

  def getClientTimeout: Int = {
    config.getInt("nlb.client.timeout")
  }

  def getConnectionIdleTimeOut: Int = {
    config.getInt("nlb.connection.idletimeout")
  }

  def getConnectionInitialTimeOut: Int = {
    config.getInt("nlb.connection.initialtimeout")
  }

  def getConnectionPoolMaxSize: Int = {
    config.getInt("nlb.connectionpool.maxsize")
  }

  def isTraceEnabled: Boolean = {
    config.getBoolean("nlb.trace.enabled", true)
  }

  def getTraceRecorder: String = {
    config.getString("nlb.trace.recorder", "console")
  }

  def getTraceScribeHost: String = {
    config.getString("nlb.trace.scribe.host", "localhost")
  }

  def getTraceScribePort: Int = {
    config.getInt("nlb.trace.scribe.port", 1463)
  }

  def getTraceScribeSamplingRate: Int = {
    config.getInt("nlb.trace.scribe.sampling_rate", 1000)
  }
}

object NlbConfiguration {
  def fromSystemProperties: NlbConfiguration = {
    val confPath = System.getProperty("nlb.config")

    val config = new CombinedConfiguration(new OverrideCombiner())

    if(confPath != null) {
      val envConfig = new PropertiesConfiguration(confPath)
      config.addConfiguration(envConfig)
    }

    val defaultConfig = new PropertiesConfiguration("etc/nlb.properties")
    config.addConfiguration(defaultConfig)
    new NlbConfiguration(config)
  }
}