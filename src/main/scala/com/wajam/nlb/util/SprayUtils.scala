package com.wajam.nlb.util

import spray.http._

object SprayUtils {

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
    headers.filterNot { header =>
      header.lowercaseName == "content-type" ||
        header.lowercaseName == "content-length" ||
        header.lowercaseName == "transfer-encoding" ||
        header.lowercaseName == "user-agent"
    }
  }
}
