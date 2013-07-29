package com.wajam.nlb.util

import com.yammer.metrics.scala.{Timer, Instrumented}
import java.util.concurrent.TimeUnit

class StartStopTimer(timer: Timer, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) {
  var startTime: Long = 0

  def start() {
    startTime = System.currentTimeMillis()
    this
  }

  def stop() {
    val elapsedTime = System.currentTimeMillis() - startTime

    timer.update(elapsedTime, timeUnit)
  }

  def update(duration: Long) = timer.update(duration, timeUnit)
}

trait Timing extends Instrumented {
  def timer(name: String, timeUnit: TimeUnit = TimeUnit.MILLISECONDS) = {
    val timer = metrics.timer(name)
    new StartStopTimer(timer, timeUnit)
  }
}
