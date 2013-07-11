package com.wajam.nlb

import spray.http.{HttpRequest, HttpHeader, HttpMessage}
import com.wajam.nrv.tracing.TraceContext
import scala.annotation.tailrec
import com.wajam.nrv.service.TraceHeader
import spray.http.HttpHeaders.RawHeader
import java.net.InetSocketAddress

/**
 * User: Clément
 * Date: 2013-07-09
 * Time: 14:10
 */
abstract class TracedMessage[+T <: HttpMessage](message: T, context: Option[TraceContext]) {

  protected def getNewContextHeaders(newContext: Option[TraceContext]) = {

    val contextHeaders = newContext.map { context =>
      List(
        new RawHeader(TraceHeader.TraceId.toString, context.traceId),
        new RawHeader(TraceHeader.SpanId.toString, context.spanId)
      ) ++ context.parentId.map { parentId => new RawHeader(TraceHeader.ParentId.toString, parentId) }.toList ++
      context.sampled.map { sampled => new RawHeader(TraceHeader.Sampled.toString, sampled.toString) }.toList
    }.getOrElse(Nil)

    val contextHeaderNames = TraceHeader.values.map(_.toString)

    message.headers.filterNot {
      case HttpHeader(headerName, _) =>
        contextHeaderNames.contains(headerName)
      case _ =>
        false
    } ++ contextHeaders
  }

  override def toString = message.toString
}

trait TracedMessageFactory[T <: HttpMessage] {

  def getContextFromMessageHeaders(message: T): Option[TraceContext] = {
    val context = TraceContext("", "", None, None)

    @tailrec
    def extractContext(headers: List[HttpHeader], context: TraceContext): TraceContext = {
      val TraceId = TraceHeader.TraceId.toString.toLowerCase
      val SpanId = TraceHeader.SpanId.toString.toLowerCase
      val ParentId = TraceHeader.ParentId.toString.toLowerCase
      val Sampled = TraceHeader.Sampled.toString.toLowerCase

      headers match {
        case Nil =>
          context
        case head :: _ =>
          head match {
            case HttpHeader(TraceId, value: String) =>
              extractContext(headers.tail, context.copy(traceId = value))

            case HttpHeader(SpanId, value: String) =>
              extractContext(headers.tail, context.copy(spanId = value))

            case HttpHeader(ParentId, value: String) =>
              extractContext(headers.tail, context.copy(parentId = Some(value)))

            case HttpHeader(Sampled, value: String) =>
              extractContext(headers.tail, context.copy(sampled = Some(value.toBoolean)))

            case _ =>
              extractContext(headers.tail, context)
          }
      }
    }

    extractContext(message.headers, context) match {
      case TraceContext(traceId, spanId, _, _) if traceId.isEmpty || spanId.isEmpty =>
        None
      case context =>
        Some(context)
    }
  }
}

case class TracedRequest(get: HttpRequest, context: Option[TraceContext]) extends TracedMessage(get, context) {

  def path: String = get.uri.path.toString
  def method: String = get.method.toString
  def address = new InetSocketAddress(get.uri.authority.host.address, get.uri.authority.port)

  def withNewContext(context: Option[TraceContext]) = copy(get = get.withHeaders(getNewContextHeaders(context)))
}

object TracedRequest extends TracedMessageFactory[HttpRequest] {

  def apply(request: HttpRequest) = new TracedRequest(request, getContextFromMessageHeaders(request))
}
