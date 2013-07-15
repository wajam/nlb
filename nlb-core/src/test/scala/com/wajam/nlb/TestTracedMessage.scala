package com.wajam.nlb

import org.scalatest.{BeforeAndAfter, FunSuite}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import spray.http.{HttpHeader, HttpRequest}
import spray.http.HttpHeaders.RawHeader
import com.wajam.nrv.service.TraceHeader
import com.wajam.nrv.tracing.TraceContext

/**
 * User: Clément
 * Date: 2013-07-11
 * Time: 09:54
 */

@RunWith(classOf[JUnitRunner])
class TestTracedMessage extends FunSuite with BeforeAndAfter {

  val traceId = "1234"
  val spanId = "5678"
  val parentId = Some("9012")
  val sampled = Some(true)

  val sampleContext = TraceContext(traceId, spanId, parentId, sampled)

  test("should extract trace context from request headers") {
    val request = new HttpRequest(headers = List(
      new RawHeader(TraceHeader.TraceId.toString, traceId),
      new RawHeader(TraceHeader.SpanId.toString, spanId),
      new RawHeader(TraceHeader.ParentId.toString, parentId.get),
      new RawHeader(TraceHeader.Sampled.toString, sampled.get.toString)
    ))

    val tracedRequest = TracedRequest(request)

    val expectedTraceContext = Some(sampleContext)

    tracedRequest.context should equal (expectedTraceContext)
  }

  test("should set trace context in request headers") {
    val request = new HttpRequest

    val tracedRequest = TracedRequest(request).withNewContext(Some(sampleContext))

    tracedRequest.get.headers should {
      contain (new RawHeader(TraceHeader.TraceId.toString, traceId).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.SpanId.toString, spanId).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.ParentId.toString, parentId.get).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.Sampled.toString, sampled.get.toString).asInstanceOf[HttpHeader])
    }
  }
}