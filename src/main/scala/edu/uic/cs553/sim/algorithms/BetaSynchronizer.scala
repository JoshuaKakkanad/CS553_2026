package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.{MsgKind, NodeId}
import edu.uic.cs553.sim.runtime.{Algorithm, DistributedAlgorithm, NodeContext}

/**
 * A lightweight tree-based synchronizer inspired by Awerbuch's beta synchronizer.
 *
 * Modeling choices (documented in docs/REPORT.md):
 * - We build a spanning tree rooted at node 0 using a simple flood.
 * - Pulses advance when the root receives "Safe(p)" from all children.
 * - This module does not "wrap" all application traffic; it demonstrates pulse
 *   coordination over the same CONTROL message substrate.
 */
final class BetaSynchronizer(maxPulses: Int = 10) extends DistributedAlgorithm:
  override val name: String = "AwerbuchBetaSynchronizer"

  private enum Phase:
    case BuildingTree, Running

  private var phase: Phase = Phase.BuildingTree
  private var parent: Option[NodeId] = None
  private var children: Set[NodeId] = Set.empty
  private var pulse: Int = -1
  private var safeFrom: Map[Int, Set[NodeId]] = Map.empty.withDefaultValue(Set.empty)

  // ---------- wire protocol ----------
  // Keep encoding simple and deterministic: "beta:<Tag>:<Int>"
  private enum Ctrl:
    case TreeHello(from: NodeId)
    case TreeAck(from: NodeId)
    case Pulse(p: Int)
    case Safe(p: Int)

  private def enc(m: Ctrl): String =
    val body = m match
      case Ctrl.TreeHello(from) => s"TreeHello:${from.value}"
      case Ctrl.TreeAck(from)   => s"TreeAck:${from.value}"
      case Ctrl.Pulse(p)        => s"Pulse:$p"
      case Ctrl.Safe(p)         => s"Safe:$p"
    "beta:" + body

  private def dec(payload: String): Option[Ctrl] =
    if !payload.startsWith("beta:") then None
    else
      payload.drop(5).split(":", 2).toList match
        case "TreeHello" :: v :: Nil => v.toIntOption.map(i => Ctrl.TreeHello(NodeId(i)))
        case "TreeAck" :: v :: Nil   => v.toIntOption.map(i => Ctrl.TreeAck(NodeId(i)))
        case "Pulse" :: v :: Nil     => v.toIntOption.map(Ctrl.Pulse.apply)
        case "Safe" :: v :: Nil      => v.toIntOption.map(Ctrl.Safe.apply)
        case _                       => None

  override def onStart(ctx: NodeContext): Unit =
    // Build spanning tree rooted at 0.
    if ctx.selfId.value == 0 then
      parent = None
      phase = Phase.BuildingTree
      ctx.neighbors.keys.foreach { nbr =>
        ctx.send(nbr, Algorithm.Envelope(from = ctx.selfId, kind = MsgKind.CONTROL, payload = enc(Ctrl.TreeHello(ctx.selfId))))
      }

  override def onTick(ctx: NodeContext): Unit =
    // Root starts pulse 0 once it has at least discovered itself; this works even for n=1 in tests.
    if ctx.selfId.value == 0 && phase == Phase.BuildingTree && parent.isEmpty then
      // If children have responded (or there are no neighbors), we can start.
      // We treat "no neighbors" as a degenerate tree.
      startPulseIfReady(ctx)

  override def onMessage(ctx: NodeContext, env: Algorithm.Envelope): Unit =
    if env.kind != MsgKind.CONTROL then return
    dec(env.payload).foreach {
      case Ctrl.TreeHello(from) =>
        if phase == Phase.BuildingTree && parent.isEmpty && ctx.selfId.value != 0 then
          parent = Some(from)
          // forward flood to other neighbors
          ctx.neighbors.keys.filterNot(_ == from).foreach { nbr =>
            ctx.send(nbr, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.TreeHello(ctx.selfId))))
          }
          // ack chosen parent
          ctx.send(from, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.TreeAck(ctx.selfId))))

      case Ctrl.TreeAck(from) =>
        if phase == Phase.BuildingTree && ctx.selfId.value == 0 then
          children = children + from
          startPulseIfReady(ctx)
        else if phase == Phase.BuildingTree then
          // non-root discovers children too (helps local accounting)
          children = children + from

      case Ctrl.Pulse(p) =>
        if phase != Phase.Running then
          phase = Phase.Running
        if p > pulse then
          pulse = p
          safeFrom = safeFrom.updated(p, Set.empty)
          ctx.logInfo(s"[beta] node=${ctx.selfId.value} recv pulse=$p children=${children.size}")
          // forward pulse down the tree
          children.foreach { ch =>
            ctx.send(ch, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Pulse(p))))
          }
          // leaf can immediately report safe
          maybeReportSafe(ctx, p)

      case Ctrl.Safe(p) =>
        if phase == Phase.Running then
          safeFrom = safeFrom.updated(p, safeFrom(p) + env.from)
          ctx.logInfo(s"[beta] node=${ctx.selfId.value} recv safe(p=$p) from=${env.from.value} (${safeFrom(p).size}/${children.size})")
          if safeFrom(p) == children then
            // All children safe: report up or advance (root).
            parent match
              case Some(par) =>
                ctx.send(par, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Safe(p))))
              case None =>
                // root advances pulse
                if p + 1 < maxPulses then
                  val next = p + 1
                  ctx.logInfo(s"[beta] root advancing to pulse=$next")
                  children.foreach { ch =>
                    ctx.send(ch, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Pulse(next))))
                  }
                else
                  ctx.logInfo(s"[beta] reached maxPulses=$maxPulses")
    }

  private def startPulseIfReady(ctx: NodeContext): Unit =
    // For a ring/tree, children will show up via TreeAck. For small graphs, start anyway.
    if phase == Phase.BuildingTree then
      phase = Phase.Running
      val p0 = 0
      pulse = p0
      ctx.logInfo(s"[beta] root starting pulse=$p0 children=${children.toVector.sortBy(_.value).map(_.value)}")
      children.foreach { ch =>
        ctx.send(ch, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Pulse(p0))))
      }
      maybeReportSafe(ctx, p0)

  private def maybeReportSafe(ctx: NodeContext, p: Int): Unit =
    if children.isEmpty then
      parent.foreach { par =>
        ctx.send(par, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Safe(p))))
      }

