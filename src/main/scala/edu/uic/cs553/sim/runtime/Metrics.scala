package edu.uic.cs553.sim.runtime

import edu.uic.cs553.sim.core.MsgKind

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder
import scala.jdk.CollectionConverters.*

object Metrics:
  private val sent = new ConcurrentHashMap[MsgKind, LongAdder]()
  private val received = new ConcurrentHashMap[MsgKind, LongAdder]()
  private val dropped = new ConcurrentHashMap[MsgKind, LongAdder]()
  private val externalInputs = new ConcurrentHashMap[MsgKind, LongAdder]()

  private def inc(m: ConcurrentHashMap[MsgKind, LongAdder], k: MsgKind): Unit =
    m.computeIfAbsent(k, _ => LongAdder()).increment()

  def recordSent(kind: MsgKind): Unit = inc(sent, kind)
  def recordReceived(kind: MsgKind): Unit = inc(received, kind)
  def recordDropped(kind: MsgKind): Unit = inc(dropped, kind)
  def recordExternalInput(kind: MsgKind): Unit = inc(externalInputs, kind)

  final case class Snapshot(
    sent: Map[String, Long],
    received: Map[String, Long],
    dropped: Map[String, Long],
    externalInputs: Map[String, Long]
  )

  def snapshot(): Snapshot =
    def snap(m: ConcurrentHashMap[MsgKind, LongAdder]): Map[String, Long] =
      m.asScala.toVector
        .sortBy(_._1.toString)
        .map { case (k, v) => k.toString -> v.longValue() }
        .toMap

    Snapshot(
      sent = snap(sent),
      received = snap(received),
      dropped = snap(dropped),
      externalInputs = snap(externalInputs)
    )

  def reset(): Unit =
    sent.clear(); received.clear(); dropped.clear(); externalInputs.clear()

