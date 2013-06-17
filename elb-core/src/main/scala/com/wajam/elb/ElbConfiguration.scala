package com.wajam.elb

/**
 * User: Clément
 * Date: 2013-06-17
 * Time: 09:50
 */

import org.apache.commons.configuration.tree.OverrideCombiner
import org.apache.commons.configuration.{Configuration, PropertiesConfiguration, CombinedConfiguration}
import scala.collection.JavaConversions._

class ElbConfiguration(config: Configuration) {
  def getZookeeperServers: String = {
    config.getString("elb.zookeeper.servers")
  }

  def getKnownPaths: List[String] = {
    config.getList("elb.knownpaths").toList.asInstanceOf[List[String]]
  }
}

object ElbConfiguration {
  def fromSystemProperties: ElbConfiguration = {
    val confPath = System.getProperty("elb.config")

    val config = new CombinedConfiguration(new OverrideCombiner())

    val envConfig = new PropertiesConfiguration(confPath)
    config.addConfiguration(envConfig)

    val defaultConfig = new PropertiesConfiguration("etc/default.properties")
    config.addConfiguration(defaultConfig)
    new ElbConfiguration(config)
  }
}