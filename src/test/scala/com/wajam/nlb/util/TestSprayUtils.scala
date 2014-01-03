package com.wajam.nlb.util

import java.net.InetSocketAddress
import org.scalatest.FlatSpec
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import spray.http._
import spray.http.HttpHeaders._
import spray.http.ContentTypes.`text/plain`
import SprayUtils._

@RunWith(classOf[JUnitRunner])
class TestSprayUtils extends FlatSpec {

  val contentType = `Content-Type`(`text/plain`)

  val forbiddenHeaders = List(
    `Content-Length`(1234),
    contentType,
    `Transfer-Encoding`("gzip"),
    `User-Agent`("spray-can/1.x"),
    Connection("close")
  )

  val allowedHeaders = List(
    new RawHeader("nrv-method-header", "GET")
  )

  val destination = new InetSocketAddress("endpoint.example.org", 8899)

  trait WithRequest {
    val relativePath = "/foo/bar?arg1=foo&arg2=bar"
    val request = new HttpRequest(
      uri = Uri("http://nlb.example.org:8080" + relativePath),
      headers = List(
        Host("nlb.example.org", 8080)
      ) ++ forbiddenHeaders ++ allowedHeaders
    )

    val preparedRequest = prepareRequest(request, destination)
  }

  "SprayUtils" should "strip headers handled by Spray when preparing a request" in new WithRequest {
    forbiddenHeaders.foreach { header =>
      preparedRequest.headers should not contain(header)
    }
  }

  it should "set the proper Host header when preparing a request" in new WithRequest {
    preparedRequest.headers should contain(
      Host(destination).asInstanceOf[HttpHeader]
    )
  }

  it should "set the `Connection: keep-alive` header when preparing a request" in new WithRequest {
    preparedRequest.headers should contain(
      Connection("keep-alive").asInstanceOf[HttpHeader]
    )
  }

  it should "set the the URI as relative when preparing a request" in new WithRequest {
    preparedRequest.uri should equal(
      Uri(relativePath)
    )
  }

  trait WithResponse {
    val headers = forbiddenHeaders ++ allowedHeaders
    val keepaliveHeader = Connection("keep-alive")
    val response = HttpResponse(headers = headers)
  }

  it should "strip headers handled by Spray and respect original Connection header when preparing a HttpResponse" in new WithResponse {
    prepareResponse(response, Some(keepaliveHeader)).headers should equal(allowedHeaders :+ keepaliveHeader)
  }

  it should "strip headers handled by Spray, except Content-Type, and respect original Connection header when preparing a ChunkedResponseStart" in new WithResponse {
    val responseStart = ChunkedResponseStart(response)

    prepareResponseStart(responseStart, Some(keepaliveHeader)).response.headers should equal((contentType :: allowedHeaders) :+ keepaliveHeader)
  }
}
