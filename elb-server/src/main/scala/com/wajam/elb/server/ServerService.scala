package com.wajam.elb.server

import akka.util.duration._
import akka.util.Timeout
import akka.actor._
import spray.can.Http
import spray.util._
import spray.http._
import com.wajam.elb.client.ElbClientActor
import com.wajam.elb.Router

/**
 * User: Clément
 * Date: 2013-06-12
 */

class ServerService extends Actor with SprayActorLogging {
  implicit val timeout: Timeout = 5.seconds // for the actor 'asks'

  def receive = {
    // when a new connection comes in we register ourselves as the connection handler
    case _: Http.Connected => sender ! Http.Register(self)

    case request: HttpRequest =>
      val peer = sender // since the Props creator is executed asyncly we need to save the sender ref
      context actorOf Props(new ElbRouterActor(peer, request)(context.system))
  }

  class ElbRouterActor(client: ActorRef, request: HttpRequest)(implicit system: ActorSystem) extends Actor with SprayActorLogging {
    implicit val timeout: Timeout = 5.seconds

    log.info("Starting forwarding response for {}...", request)

    val recipient = Router.resolve(request.uri.path.toString)

    val clientActor = ElbClientActor(recipient._1, recipient._2)

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
