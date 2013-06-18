package com.wajam.elb.server

import akka.util.duration._
import akka.util.Timeout
import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.wajam.elb.client.ElbClientActor
import com.wajam.elb.{ElbConfiguration, Router}

/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerService(implicit config: ElbConfiguration) extends Actor with SprayActorLogging {
  implicit val timeout: Timeout = config.getServerTimeout.seconds

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case request: HttpRequest =>
      val peer = sender // since the Props creator is executed asyncly we need to save the sender ref
      context actorOf Props(new ElbRouterActor(peer, request, config.getRouterTimeout.seconds)(context.system))
  }

  class ElbRouterActor(client: ActorRef, request: HttpRequest, implicit val timeout: Timeout)(implicit system: ActorSystem) extends Actor with SprayActorLogging {

    log.info("Starting forwarding response for {}...", request)

    val recipient = Router.resolve(request.uri.path.toString)

    log.info("Routing to node {}:{}...", recipient._1.getHostAddress, recipient._2)

    val clientActor = ElbClientActor(recipient._1, recipient._2, config.getClientTimeout)

    clientActor ! request

    def receive = {
      // Client actor stopped, stop the router
      case Status.Success | Status.Failure =>
        context.stop(self)

      case response =>
        client ! response
    }
  }
}
