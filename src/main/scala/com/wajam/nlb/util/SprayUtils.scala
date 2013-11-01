package com.wajam.nlb.util

import java.net.InetSocketAddress
import spray.http._
import spray.http.HttpHeaders.{Connection, Host}

object SprayUtils {

  val CONTENT_TYPE = "content-type"
  val CONTENT_LENGTH = "content-length"
  val TRANSFER_ENCODING = "transfer-encoding"
  val USER_AGENT = "user-agent"
  val CONNECTION = "connection"
  val HOST = "host"

  val strippedHeaders = Set(CONTENT_TYPE, CONTENT_LENGTH, TRANSFER_ENCODING, USER_AGENT, CONNECTION)

  def prepareRequest(request: HttpRequest, destination: InetSocketAddress): HttpRequest = {
    // Add a Host header with the proper destination
    // Then add a `Connection: keep-alive` header to ensure keep-alive even when HTTP 1.0 is used
    // Then filter out headers that are handled by Spray + the Host header
    val headers = Host(destination) :: Connection("keep-alive") :: stripRequestHeaders(request.headers)

    request.copy(
      // Set a relative path as URI, discarding NLB's hostname and port
      uri = Uri(request.uri.path.toString),
      headers = headers
    )
  }

  def stripRequestHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot { header =>
      (strippedHeaders + HOST).contains(header.lowercaseName)
    }
  }

  def prepareResponse(response: HttpResponse): HttpResponse =
    response.withHeaders(stripResponseHeaders(response.headers))

  def prepareResponseStart(responseStart: ChunkedResponseStart): ChunkedResponseStart = {
    val headers = stripResponseHeaders(responseStart.response.headers)
    val response = responseStart.response.withHeaders(headers)

    ChunkedResponseStart(response)
  }

  def stripResponseHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot(header => strippedHeaders.contains(header.lowercaseName))
  }

  def hasConnectionClose(headers: List[HttpHeader]) = headers.exists {
    case x: Connection if x.hasClose => true
    case _ => false
  }
}
