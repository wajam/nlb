package com.wajam.nlb.util

import com.wajam.nrv.cluster.{Cluster, LocalNode, Node}
import com.wajam.nrv.service.{ServiceMember, MemberStatus, Service, Resolver}
import com.wajam.nrv.zookeeper.cluster.ZookeeperClusterManager
import com.wajam.nrv.zookeeper.ZookeeperClient
import com.wajam.nrv.Logging
import scala.util.Random
import java.net.{InetSocketAddress}
import scala.annotation.tailrec
import scala.util.matching.Regex
import scala.util.matching.Regex.Match
import com.yammer.metrics.scala.Instrumented

class Router(knownPaths: List[String],
             zookeeperServers: String,
             resolvingService: String,
             httpPort: Int,
             localNodePort: Int) extends Instrumented with Logging {

  private val resolvedUpMeter = metrics.meter("router-resolving-up", "resolvings")
  private val resolvedDownMeter = metrics.meter("router-resolving-down", "resolvings")
  private val allDownMeter = metrics.meter("router-all-down", "failures")
  private val noResolvingNeeded = metrics.meter("router-no-resolving-needed", "queries")

  val matchers = getMatchers(knownPaths)

  log.info("Initialized Router with matchers:")
  matchers.foreach { matcher =>
    log.info(matcher.toString)
  }

  lazy val zookeeper = new ZookeeperClient(zookeeperServers)
  val clusterManager = new ZookeeperClusterManager(zookeeper)
  val node = new LocalNode(Map("nrv" -> localNodePort))
  val cluster = new Cluster(node, clusterManager)
  val service = new Service(resolvingService)
  cluster.registerService(service)
  cluster.applySupport(resolver = Some(new Resolver()))
  cluster.start()

  private def getMatchers(paths: List[String]) = {
    paths.map { path =>
      val idMatcher = """:\w+"""

      // Map /foo/:id/bar to /foo/(\w+)/.+
      (idMatcher + """/.+""").r replaceFirstIn(path, """(\\w+)/.+""") match {
        // Map /foo/:id to /foo/(\w+)(?.+)?$
        case result if result.equals(path) => (idMatcher + """$""").r replaceFirstIn(path, """(\\w+)(\\?.+)\?\$""") match {
          case result if result.equals(path) => throw new Exception("Unable to parse specified path: " + path)
          case result => result
        }
        case result => result
      }
    }.distinct.map(_.r)
  }

  @tailrec
  private def getId(path: String, matchers: List[Regex] = matchers): Option[String] = {
    matchers match {
      case Nil => None
      case matcher :: _ => {
        matcher.findFirstMatchIn(path) match {
          case Some(m: Match) => {
            val id = m.group(1)
            log.debug("Extracted id "+ id +" with matcher "+ matcher)
            Some(id)
          }
          case _ => getId(path, matchers.tail)
        }
      }
    }
  }

  private def randomUpServiceMember: ServiceMember = {
    val members = service.members.filter(_.status == MemberStatus.Up)

    if(members.isEmpty) {
      allDownMeter.mark()
      throw new ResolvingException("No up service member")
    }

    val randPos = Random.nextInt(members.size)

    members.toList(randPos)
  }

  private def nodeToInet(node: Node) = {
    new InetSocketAddress(node.host, httpPort)
  }

  def resolve(path: String): InetSocketAddress = {
    val node = getId(path) match {
      case Some(id) => {
        val token = Resolver.hashData(id)
        log.debug("Generated token "+ token)

        cluster.resolver.resolve(service, token).selectedReplicas.headOption match {
          case Some(member) =>
            resolvedUpMeter.mark()
            member.node
          case _ =>
            resolvedDownMeter.mark()
            randomUpServiceMember.node
        }
      }
      case _ => {
        log.debug("Couldn't extract id from path "+ path +", routing randomly")
        noResolvingNeeded.mark()
        randomUpServiceMember.node
      }
    }
    nodeToInet(node)
  }
}

class ResolvingException(message: String) extends Exception(message)
