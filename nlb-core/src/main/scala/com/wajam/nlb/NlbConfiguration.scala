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

  def getConnectionPoolTimeOut: Int = {
    config.getInt("nlb.connectionpool.timeout")
  }

  def getConnectionPoolMaxSize: Int = {
    config.getInt("nlb.connectionpool.maxsize")
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