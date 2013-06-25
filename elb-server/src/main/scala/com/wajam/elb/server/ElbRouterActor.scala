package com.wajam.elb.server

import akka.util.duration._
import akka.util.{Timeout}
import akka.actor.{Actor, ActorRef, Status, Props}
import spray.http._
import spray.http.HttpHeaders.Connection
import spray.util.SprayActorLogging
import com.wajam.elb.client.ElbClientActor
import com.wajam.elb.{SprayConnectionPool, Router, ActorFactory}

/**
 * User: Clément
 * Date: 2013-06-19
 * Time: 16:52
 */

class ElbRouterActor(pool: SprayConnectionPool,
                     client: ActorRef,
                     request: HttpRequest,
                     router: Router,
                     implicit val timeout: Timeout)
  extends Actor with SprayActorLogging {

  log.info("Starting forwarding response for {}...", request)

  val destination = router.resolve(request.uri.path.toString)

  log.info("Routing to node {}...", destination.toString)

  // clientActor is the actor handling the connection with the server
  // Not to be mistaken with client, which is *our* client
  val clientActor = pool.getConnection(destination)

  clientActor ! request

  def receive = {
    // Transmission finished, stop the router and pool the connection
    case response: HttpResponse =>
      client ! response
      if(!response.connectionCloseExpected) {
        log.info("Pooling connection")
        pool.poolConnection(destination, clientActor)
      }
      context.stop(self)

    case chunkEnd: ChunkedMessageEnd =>
      client ! chunkEnd
      if(!chunkEnd.trailer.exists { case x: Connection if x.hasClose ⇒ true; case _ ⇒ false }) {
        log.info("Pooling connection")
        pool.poolConnection(destination, clientActor)
      }
      context.stop(self)

    // Error or connection successfully closed, stop the router without pooling the connection
    case Status.Failure | Status.Success =>
      context.stop(self)

    case response =>
      client ! response
  }
}

object ElbRouterActor extends ActorFactory {

  def apply(pool: SprayConnectionPool, client: ActorRef, request: HttpRequest, router: Router) = new ElbRouterActor(pool, client, request, router, timeout)
}
