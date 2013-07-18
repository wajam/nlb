package com.wajam.nlb.util

import com.yammer.metrics.scala.Instrumented

/**
 * Wrapper around a Histogram metric to measure time with start, pause and stop directives.
 */
class Timer(val metricName: String) extends Instrumented {
  private val histogram = metrics.histogram(metricName)

  var startTime: Long = 0
  var elapsedTime: Long = 0

  def start() = {
    startTime = getTime()
  }

  def pause() = {
    elapsedTime += getTime() - startTime
    startTime = 0
  }

  def stop() = {
    pause()
    histogram += elapsedTime
  }

  private def getTime() = {
    System.currentTimeMillis()
  }
}

object Timer {
  def apply(metricName: String) = {
    val timer = new Timer(metricName)
    timer.start()
    timer
  }
}
