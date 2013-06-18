package com.wajam.elb

import akka.util.duration._

/**
 * User: Clément
 * Date: 2013-06-18
 * Time: 17:41
 */

trait ActorFactory {
  var timeout = 30.seconds

  def setTimeOut(value: Int) {
    timeout = value.seconds
  }
}
