package com.twitter.finagle.zipkin.thrift

import com.twitter.conversions.time._
import com.twitter.finagle.service.TimeoutFilter
import com.twitter.finagle.stats.NullStatsReceiver
import com.twitter.finagle.tracing._
import com.twitter.finagle.zipkin.core.{BinaryAnnotation, Span, ZipkinAnnotation, Endpoint}
import com.twitter.finagle.zipkin.thriftscala.{Scribe, ResultCode, LogEntry}
import com.twitter.util._
import java.net.{InetSocketAddress, InetAddress}
import org.scalatest.FunSuite

class ScribeRawZipkinTracerTest extends FunSuite {

  val traceId = TraceId(Some(SpanId(123)), Some(SpanId(123)), SpanId(123), None, Flags().setDebug)

  /**
   * The ttl used to construct the DeadlineSpanMap in RawZipkinTracer. The value is not exposed in
   * ScribeRawZipkinTracer, but needed here to ensure that time is advanced enough to guarantee that
   *  the span is removed from the cache and logged.
   */
  val deadlineSpanMapTtl = 120.seconds

  class ScribeClient extends Scribe.FutureIface {
    var messages: Seq[LogEntry] = Seq.empty[LogEntry]
    var response: Future[ResultCode] = Future.value(ResultCode.Ok)
    def log(msgs: Seq[LogEntry]): Future[ResultCode] = {
      messages ++= msgs
      response
    }
  }

  test("formulate scribe log message correctly") {
    val scribe = new ScribeClient
    val tracer = new ScribeRawZipkinTracer(scribe, NullStatsReceiver)

    val localEndpoint = Endpoint(2323, 23)
    val remoteEndpoint = Endpoint(333, 22)

    val annotations = Seq(
      ZipkinAnnotation(Time.fromSeconds(123), "cs", localEndpoint),
      ZipkinAnnotation(Time.fromSeconds(126), "cr", localEndpoint),
      ZipkinAnnotation(Time.fromSeconds(123), "ss", remoteEndpoint),
      ZipkinAnnotation(Time.fromSeconds(124), "sr", remoteEndpoint),
      ZipkinAnnotation(Time.fromSeconds(123), "llamas", localEndpoint)
    )

    val span = Span(
      traceId = traceId,
      annotations = annotations,
      _serviceName = Some("hickupquail"),
      _name = Some("foo"),
      bAnnotations = Seq.empty[BinaryAnnotation],
      endpoint = localEndpoint
    )

    val expected = LogEntry(
      category = "zipkin",
      message = "CgABAAAAAAAAAHsLAAMAAAADZm9vCgAEAAAAAAAAAHsKAAUAAAAAAAAAe" +
        "w8ABgwAAAAFCgABAAAAAAdU1MALAAIAAAACY3MMAAMIAAEAAAkTBgACABcLAAMAAAA" +
        "LaGlja3VwcXVhaWwAAAoAAQAAAAAHgpuACwACAAAAAmNyDAADCAABAAAJEwYAAgAXC" +
        "wADAAAAC2hpY2t1cHF1YWlsAAAKAAEAAAAAB1TUwAsAAgAAAAJzcwwAAwgAAQAAAU0" +
        "GAAIAFgsAAwAAAAtoaWNrdXBxdWFpbAAACgABAAAAAAdkFwALAAIAAAACc3IMAAMIA" +
        "AEAAAFNBgACABYLAAMAAAALaGlja3VwcXVhaWwAAAoAAQAAAAAHVNTACwACAAAABmx" +
        "sYW1hcwwAAwgAAQAACRMGAAIAFwsAAwAAAAtoaWNrdXBxdWFpbAAAAgAJAQoACgAAAA" +
        "AHVNTAAA==\n"
    )

    tracer.sendSpans(Seq(span))
    assert(scribe.messages == Seq(expected))
  }

  test("send all traces to scribe") {
    Time.withCurrentTimeFrozen { tc =>
      val scribe = new ScribeClient
      val timer = new MockTimer
      val tracer = new ScribeRawZipkinTracer(scribe, NullStatsReceiver, timer = timer)

      val localAddress = InetAddress.getByAddress(Array.fill(4) {
        1
      })
      val remoteAddress = InetAddress.getByAddress(Array.fill(4) {
        10
      })
      val port1 = 80 // never bound
      val port2 = 53 // ditto
      tracer.record(
        Record(
          traceId,
          Time.now,
          Annotation.ClientAddr(new InetSocketAddress(localAddress, port1))
        )
      )
      tracer.record(
        Record(
          traceId,
          Time.now,
          Annotation.LocalAddr(new InetSocketAddress(localAddress, port1))
        )
      )
      tracer.record(
        Record(
          traceId,
          Time.now,
          Annotation.ServerAddr(new InetSocketAddress(remoteAddress, port2))
        )
      )
      tracer.record(Record(traceId, Time.now, Annotation.ServiceName("service")))
      tracer.record(Record(traceId, Time.now, Annotation.Rpc("method")))
      tracer.record(
        Record(traceId, Time.now, Annotation.BinaryAnnotation("i16", 16.toShort))
      )
      tracer.record(Record(traceId, Time.now, Annotation.BinaryAnnotation("i32", 32)))
      tracer.record(Record(traceId, Time.now, Annotation.BinaryAnnotation("i64", 64L)))
      tracer.record(
        Record(traceId, Time.now, Annotation.BinaryAnnotation("double", 123.3d))
      )
      tracer.record(
        Record(traceId, Time.now, Annotation.BinaryAnnotation("string", "woopie"))
      )
      tracer.record(Record(traceId, Time.now, Annotation.Message("boo")))
      tracer.record(
        Record(traceId, Time.now, Annotation.Message("boohoo"), Some(1.second))
      )
      tracer.record(Record(traceId, Time.now, Annotation.ClientSend))
      tracer.record(Record(traceId, Time.now, Annotation.ClientRecv))

      tc.advance(deadlineSpanMapTtl) // advance timer beyond the ttl to force DeadlineSpanMap flush
      timer.tick()

      // Note: Since ports are ephemeral, we can't hardcode expected message.
      assert(scribe.messages.size == 1)
    }
  }

  test("logSpan if a timeout occurs") {
    Time.withCurrentTimeFrozen { tc =>
      val ann1 = Annotation.Message("some_message")
      val ann2 = Annotation.ServiceName("some_service")
      val ann3 = Annotation.Rpc("rpc_name")
      val ann4 = Annotation.Message(TimeoutFilter.TimeoutAnnotation)

      val scribe = new ScribeClient
      val timer = new MockTimer
      val tracer = new ScribeRawZipkinTracer(scribe, NullStatsReceiver, timer = timer)

      tracer.record(Record(traceId, Time.fromSeconds(1), ann1))
      tracer.record(Record(traceId, Time.fromSeconds(2), ann2))
      tracer.record(Record(traceId, Time.fromSeconds(3), ann3))
      tracer.record(Record(traceId, Time.fromSeconds(3), ann4))

      tc.advance(deadlineSpanMapTtl) // advance timer beyond the ttl to force DeadlineSpanMap flush
      timer.tick()

      // scribe Log method is in java
      assert(scribe.messages.size == 1)
    }
  }
}
