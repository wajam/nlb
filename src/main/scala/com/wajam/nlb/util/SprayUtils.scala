package com.wajam.nlb.util

import java.net.InetSocketAddress
import spray.http._
import spray.http.HttpHeaders.{Connection, Host}
import spray.http.Uri.Authority

object SprayUtils {

  val CONTENT_TYPE = "content-type"
  val CONTENT_LENGTH = "content-length"
  val TRANSFER_ENCODING = "transfer-encoding"
  val USER_AGENT = "user-agent"
  val CONNECTION = "connection"
  val HOST = "host"

  val strippedRequestHeaders = Set(CONTENT_TYPE, CONTENT_LENGTH, TRANSFER_ENCODING, USER_AGENT, CONNECTION, HOST)
  val strippedResponseHeaders = Set(CONTENT_TYPE, CONTENT_LENGTH, TRANSFER_ENCODING, USER_AGENT, CONNECTION)
  val strippedResponseStartHeaders = Set(CONTENT_LENGTH, TRANSFER_ENCODING, USER_AGENT, CONNECTION)

  def prepareRequest(request: HttpRequest, destination: InetSocketAddress): HttpRequest = {
    // Add a Host header with the proper destination
    // Then add a `Connection: keep-alive` header to ensure keep-alive even when HTTP 1.0 is used
    // Then filter out headers that are handled by Spray + the Host header
    val headers = Host(destination) :: Connection("keep-alive") :: stripRequestHeaders(request.headers)

    request.copy(
      // Set a relative path as URI, discarding NLB's hostname and port
      uri = request.uri.copy(scheme = "", authority = Authority.Empty),
      headers = headers
    )
  }

  def stripRequestHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot(header => strippedRequestHeaders.contains(header.lowercaseName))
  }

  def prepareResponse(response: HttpResponse, connectionHeader: Option[Connection]): HttpResponse = {
    val headers = stripResponseHeaders(response.headers) ++ connectionHeader.toList
    response.withHeaders(headers)
  }

  def prepareResponseStart(responseStart: ChunkedResponseStart, connectionHeader: Option[Connection]): ChunkedResponseStart = {
    val headers = stripResponseStartHeaders(responseStart.response.headers) ++ connectionHeader.toList
    val response = responseStart.response.withHeaders(headers)

    ChunkedResponseStart(response)
  }

  def stripResponseHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot(header => strippedResponseHeaders.contains(header.lowercaseName))
  }

  def stripResponseStartHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    headers.filterNot(header => strippedResponseStartHeaders.contains(header.lowercaseName))
  }

  def hasConnectionClose(headers: List[HttpHeader]) = headers.exists {
    case x: Connection if x.hasClose => true
    case _ => false
  }

  def getConnectionHeader(request: HttpRequest): Option[Connection] = {
    request.headers.collectFirst {
      case x: Connection => x
    }
  }
}
