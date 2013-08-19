package com.wajam.nlb.util

import org.scalatest.FunSuite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers._
import spray.http.{Uri, HttpRequest}
import spray.http.HttpHeaders.RawHeader
import SprayUtils.HttpHeaders._
import java.net.InetSocketAddress

@RunWith(classOf[JUnitRunner])
class TestSprayUtils extends FunSuite {

  test("should strip all headers handled by Spray") {
    val forbiddenHeaders = List(
      new RawHeader(CONTENT_TYPE, "text/plain"),
      new RawHeader(CONTENT_LENGTH, "0"),
      new RawHeader(TRANSFER_ENCODING, "gzip"),
      new RawHeader(USER_AGENT, "spray-can/1.x"),
      new RawHeader(CONNECTION, "close")
    )

    val allowedHeaders = List(
      new RawHeader("nrv-method-header", "GET")
    )

    val allHeaders = forbiddenHeaders ++ allowedHeaders

    val request = new HttpRequest(headers = allHeaders)

    val stripedRequest = SprayUtils.withHeadersStripped(request)

    stripedRequest.headers should equal(allowedHeaders)
  }

  test("should set the destination's URI and Host header") {
    val relativePath = "/foo/bar"
    val request = new HttpRequest(
      uri = Uri("http://nlb.example.org:8080" + relativePath),
      headers = List(
        new RawHeader(HOST, "nlb.example.org")
      )
    )

    val destination = new InetSocketAddress("endpoint.example.org", 8899)

    val forwardedRequest = SprayUtils.withNewHost(request, destination)

    forwardedRequest.headers should equal(
      List(new RawHeader(HOST, destination.getHostName + ":" + destination.getPort))
    )
    forwardedRequest.uri should equal(
      Uri("http://" + destination.getHostName + ":" + destination.getPort + relativePath)
    )
  }
}
