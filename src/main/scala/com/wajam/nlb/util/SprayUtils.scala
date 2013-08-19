package com.wajam.nlb.util

import spray.http._
import java.net.InetSocketAddress
import spray.http.HttpHeaders.RawHeader

object SprayUtils {

  object HttpHeaders {
    val CONTENT_TYPE = "Content-Type"
    val CONTENT_LENGTH = "Content-Length"
    val TRANSFER_ENCODING = "Transfer-Encoding"
    val USER_AGENT = "User-Agent"
    val CONNECTION = "Connection"
    val HOST = "Host"

    val strippedHeaders = List(CONTENT_TYPE, CONTENT_LENGTH, TRANSFER_ENCODING, USER_AGENT, CONNECTION)
  }

  def sanitizeHeaders: PartialFunction[Any, Any] = {
    case response: HttpResponse =>
      SprayUtils.withHeadersStripped(response)

    case responseStart: ChunkedResponseStart =>
      SprayUtils.withHeadersStripped(responseStart)

    case chunkEnd: ChunkedMessageEnd =>
      SprayUtils.withHeadersStripped(chunkEnd)

    case (anything, request: TracedRequest) =>
      (anything, request.copy(get = withHeadersStripped(request.get)))

    case other =>
      other
  }

  def withHeadersStripped(response: HttpResponse): HttpResponse = {
    response.copy(headers = stripHeaders(response.headers))
  }

  def withHeadersStripped(chunkEnd: ChunkedMessageEnd): ChunkedMessageEnd = {
    chunkEnd.copy(trailer = stripHeaders(chunkEnd.trailer))
  }

  def withHeadersStripped(chunkStart: ChunkedResponseStart): ChunkedResponseStart = {
    chunkStart.copy(response = chunkStart.response.copy(headers = stripHeaders(chunkStart.response.headers)))
  }

  def withHeadersStripped(request: HttpRequest): HttpRequest = {
    request.copy(headers = stripHeaders(request.headers))
  }

  private def stripHeaders(headers: List[HttpHeader]): List[HttpHeader] = {
    import HttpHeaders.strippedHeaders

    headers.filterNot(header => strippedHeaders.map(_.toLowerCase).contains(header.lowercaseName))
  }

  def withNewHost(request: HttpRequest, destination: InetSocketAddress): HttpRequest = {
    import HttpHeaders.HOST

    val newAuthority = request.uri.authority.copy(
      host = Uri.Host(destination.getHostName),
      port = destination.getPort
    )
    val newUri = request.uri.copy(authority = newAuthority)
    val newHeaders = new RawHeader(HOST, destination.getHostName + ":" + destination.getPort) :: request.headers.filterNot(_.lowercaseName == HOST.toLowerCase)

    request.copy(
      uri = newUri,
      headers = newHeaders
    )
  }
}
