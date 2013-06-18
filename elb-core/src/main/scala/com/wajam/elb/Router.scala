package com.wajam.elb

import com.wajam.nrv.cluster.{Cluster, LocalNode, Node}
import com.wajam.nrv.service.{Service, Resolver}
import com.wajam.nrv.zookeeper.cluster.ZookeeperClusterManager
import com.wajam.nrv.zookeeper.ZookeeperClient
import scala.util.Random
import org.slf4j._
import java.net.InetAddress

/**
 * User: Clément
 * Date: 2013-06-17
 * Time: 10:18
 */

object Router {

  private val logger = LoggerFactory.getLogger("elb.router.logger")

  val config = ElbConfiguration.fromSystemProperties

  val paths: List[String] = config.getKnownPaths.map { path =>
    val idMatcher = """:\w+"""

    // Map /foo/:id/bar to /foo/(\w+)/.+
    (idMatcher + """/.+""").r replaceFirstIn(path, """(\\w+)/.+""") match {
      // Map /foo/:id to /foo/(\w+)$
      case result if result.equals(path) => (idMatcher + """$""").r replaceFirstIn(path, """(\\w+)\$""") match {
        case result if result.equals(path) => throw new Exception("Unable to parse specified path: " + path)
        case result => result
      }
      case result => result
    }
  }.distinct

  val matchers = paths.map(_.r)

  lazy val zookeeper = new ZookeeperClient(config.getZookeeperServers)
  val clusterManager = new ZookeeperClusterManager(zookeeper)
  val node = new LocalNode(Map("nrv" -> 9701))
  val cluster = new Cluster(node, clusterManager)
  val service = new Service(config.getResolvingService)
  cluster.registerService(service)
  cluster.applySupport(resolver = Some(new Resolver()))
  cluster.start()

  def resolve(path: String): (InetAddress, Int) = {

    matchers.find { matcher =>
      !matcher.findFirstIn(path).isEmpty
    } match {
      case Some(matcher) => {
        val id = matcher.replaceFirstIn(path, "$1")
        logger.info("Extracted id "+ id +" from path "+ path +" with matcher " + matcher)

        val token = Resolver.hashData(id)
        logger.info("Generated token "+ token)

        cluster.resolver.resolve(service, token).selectedReplicas.headOption match {
          case Some(member) => (member.node.host, config.getHttpPort)
          case _ => throw new Exception("Could not find Service Member for token " + token)
        }
      }
      case _ => {
        logger.info("Couldn't extract id from path "+ path +", routing randomly")

        val members = service.members
        val randPos = Random.nextInt(members.size)

        (members.toList(randPos).node.host, config.getHttpPort)
      }
    }
  }
}
