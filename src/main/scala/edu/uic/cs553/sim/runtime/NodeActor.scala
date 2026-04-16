package edu.uic.cs553.sim.runtime

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Timers}
import edu.uic.cs553.sim.core.*

import scala.concurrent.duration.*
import scala.util.Random

object NodeActor:
  def props(id: NodeId, algorithms: List[DistributedAlgorithm]): Props =
    Props(new NodeActor(id, algorithms))

  // lifecycle / control
  sealed trait Msg
  final case class Init(
    neighbors: Map[NodeId, ActorRef],
    allowedOnEdge: Map[NodeId, Set[MsgKind]],
    pdf: Pdf,
    timer: Option[FiniteDuration],
    timerMode: TimerMode,
    seed: Long
  ) extends Msg

  final case class ExternalInput(kind: MsgKind, payload: String) extends Msg
  final case class Inbound(env: Algorithm.Envelope) extends Msg
  private case object Tick extends Msg

final class NodeActor(id: NodeId, algorithms: List[DistributedAlgorithm])
    extends Actor
    with Timers
    with ActorLogging:

  import NodeActor.*

  private var nbrs: Map[NodeId, ActorRef] = Map.empty
  private var allowed: Map[NodeId, Set[MsgKind]] = Map.empty
  private var pdf: Pdf = Pdf(Map.empty)
  private var rng: Random = Random(0L)
  private var timerMode: TimerMode = TimerMode.Pdf

  private val ctxImpl: NodeContext = new NodeContext:
    override def selfId: NodeId = id
    override def neighbors: Map[NodeId, ActorRef] = nbrs
    override def send(to: NodeId, env: Algorithm.Envelope): Unit =
      val ok = allowed.getOrElse(to, Set.empty).contains(env.kind)
      if ok then
        Metrics.recordSent(env.kind)
        nbrs.get(to).foreach(_ ! Inbound(env))
      else
        Metrics.recordDropped(env.kind)
        log.debug(s"edge-filter drop from=${id.value} to=${to.value} kind=${env.kind}")

    override def logInfo(msg: String): Unit =
      log.info(msg)

  override def receive: Receive =
    case Init(neighbors0, allowedOnEdge0, pdf0, timer0, timerMode0, seed0) =>
      nbrs = neighbors0
      allowed = allowedOnEdge0
      pdf = pdf0.normalizedOrThrow()
      rng = Random(seed0 ^ id.value.toLong)
      timerMode = timerMode0

      timer0.foreach { every =>
        timers.startTimerAtFixedRate("tick", Tick, every)
      }

      algorithms.foreach(_.onStart(ctxImpl))

    case Tick =>
      algorithms.foreach(_.onTick(ctxImpl))
      // baseline background traffic
      timerMode match
        case TimerMode.Pdf =>
          sampleKindFromPdf(pdf).foreach { kind =>
            sendToOneEligibleNeighbor(kind, s"tick-from-${id.value}")
          }
        case TimerMode.Fixed(kind) =>
          sendToOneEligibleNeighbor(kind, s"tick-from-${id.value}")

    case ExternalInput(kind, payload) =>
      // treat injected messages like normal stimuli
      Metrics.recordExternalInput(kind)
      sendToOneEligibleNeighbor(kind, payload)

    case Inbound(env) =>
      Metrics.recordReceived(env.kind)
      algorithms.foreach(_.onMessage(ctxImpl, env))

  private def sampleKindFromPdf(pdf: Pdf): Option[MsgKind] =
    if pdf.p.isEmpty then None
    else
      val r = rng.nextDouble()
      var acc = 0.0
      pdf.p.toVector.sortBy(_._1.toString).collectFirst {
        case (k, p) if { acc += p; r <= acc } => k
      }.orElse(pdf.p.keys.headOption)

  private def sendToOneEligibleNeighbor(kind: MsgKind, payload: String): Unit =
    val eligible = nbrs.keys.filter { to =>
      allowed.getOrElse(to, Set.empty).contains(kind)
    }.toVector.sortBy(_.value)

    eligible.headOption match
      case Some(to) =>
        ctxImpl.send(to, Algorithm.Envelope(from = id, kind = kind, payload = payload))
      case None =>
        if nbrs.nonEmpty then
          // The node had neighbors, but all channels filtered this kind.
          Metrics.recordDropped(kind)
          log.debug(s"edge-filter drop from=${id.value} kind=${kind} reason=no-eligible-neighbor")

