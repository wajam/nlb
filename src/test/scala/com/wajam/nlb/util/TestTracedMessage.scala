package com.wajam.nlb.util

import org.scalatest.FlatSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import spray.http.{HttpHeader, HttpRequest}
import spray.http.HttpHeaders.RawHeader
import com.wajam.nrv.service.TraceHeader
import com.wajam.tracing.{ConsoleTraceRecorder, Tracer, TraceContext}
import org.scalatest.mock.MockitoSugar

@RunWith(classOf[JUnitRunner])
class TestTracedMessage extends FlatSpec with MockitoSugar {

  implicit val tracer = new Tracer(ConsoleTraceRecorder)

  val startStopTimer = mock[StartStopTimer]

  val traceId = "1234"
  val spanId = "5678"
  val parentId = Some("9012")
  val sampled = Some(true)

  val sampleContext = TraceContext(traceId, spanId, parentId, sampled)

  "a traced message" should "extract trace context from request headers" in {
    val request = new HttpRequest(headers = List(
      new RawHeader(TraceHeader.TraceId.toString, traceId),
      new RawHeader(TraceHeader.SpanId.toString, spanId),
      new RawHeader(TraceHeader.ParentId.toString, parentId.get),
      new RawHeader(TraceHeader.Sampled.toString, sampled.get.toString)
    ))

    val tracedRequest = TracedRequest(request, startStopTimer)

    val expectedTraceContext = Some(sampleContext)

    tracedRequest.context should equal (expectedTraceContext)
  }

  it should "should create a new context if context headers are not set" in {
    val request = new HttpRequest()

    val tracedRequest = TracedRequest(request, startStopTimer)

    tracedRequest.context should be ('defined)
  }

  it should "respect the sampled header if set" in {
    val request = new HttpRequest(headers = List(
      new RawHeader(TraceHeader.Sampled.toString, "true")
    ))

    val tracedRequest = TracedRequest(request, startStopTimer)

    tracedRequest.context.get.sampled should equal (Some(true))
  }

  it should "set trace context in request headers" in {
    val request = new HttpRequest

    val tracedRequest = TracedRequest(request, startStopTimer).withNewContext(Some(sampleContext))

    tracedRequest.get.headers should {
      contain (new RawHeader(TraceHeader.TraceId.toString, traceId).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.SpanId.toString, spanId).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.ParentId.toString, parentId.get).asInstanceOf[HttpHeader]) and
      contain (new RawHeader(TraceHeader.Sampled.toString, sampled.get.toString).asInstanceOf[HttpHeader])
    }
  }
}
