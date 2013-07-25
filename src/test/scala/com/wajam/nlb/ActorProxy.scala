package com.wajam.nlb.test

import akka.actor.{ActorRef, ActorLogging, Actor, ActorSystem}
import com.typesafe.config.ConfigFactory
import akka.testkit.{ImplicitSender, TestKit}

/**
 * User: Clément
 * Date: 2013-07-24
 * Time: 09:45
 */
trait ActorProxy extends TestKit with ImplicitSender {

  /** Define proxy actors that will act as:
    * - the Router actor (defined at the application level, usually calling the Client actor),
    * - the Server actor (furnished by Spray and representing the node),
    * - the Connector actor (usually IO(Http))
    *
    * These proxies will send any message to any actor when receiving a TellTo message.
    *
    * They will also forward every message they receive to testActor, after wrapping them in a
    * specific case class so that testActor can check which proxy sent it.
    */
  abstract class ProxyActor extends Actor with ActorLogging {
    def receive: Receive = {
      case TellTo(recipient: ActorRef, msg: Any) =>
        recipient ! msg
        log.debug("Telling "+ msg +" to "+ recipient)
      case x =>
        testActor ! wrap(x)
        log.debug("Forwarding "+ x +" to testActor")
    }

    def wrap(msg: Any): Any
  }

  case class TellTo(recipient: ActorRef, msg: Any)
}
