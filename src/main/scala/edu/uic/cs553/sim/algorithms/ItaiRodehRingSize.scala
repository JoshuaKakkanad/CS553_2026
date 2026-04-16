package edu.uic.cs553.sim.algorithms

import edu.uic.cs553.sim.core.{MsgKind, NodeId}
import edu.uic.cs553.sim.runtime.{Algorithm, DistributedAlgorithm, NodeContext}

/**
 * Ring size computation packaged under the "Itai–Rodeh ring size" slot.
 *
 * Practical variant used here:
 * - Assumes a distinguished initiator (node 0) to start the size probe.
 * - Assumes a bidirectional ring over node ids 0..n-1, and orients the ring clockwise
 *   by choosing the neighbor with id = self+1 (wrapping via the unique neighbor with
 *   the smallest id if self is the maximum).
 *
 * This is intentionally kept small and testable; the report explicitly documents the
 * assumptions and how they relate to the original algorithm family.
 */
final class ItaiRodehRingSize extends DistributedAlgorithm:
  override val name: String = "ItaiRodehRingSize"

  private enum Ctrl:
    case Probe(hops: Int)
    case Result(size: Int)

  private var decided: Option[Int] = None

  // "irsize:<Tag>:<Int>"
  private def enc(m: Ctrl): String =
    val body = m match
      case Ctrl.Probe(hops)   => s"Probe:$hops"
      case Ctrl.Result(size)  => s"Result:$size"
    "irsize:" + body

  private def dec(payload: String): Option[Ctrl] =
    if !payload.startsWith("irsize:") then None
    else
      payload.drop(7).split(":", 2).toList match
        case "Probe" :: v :: Nil  => v.toIntOption.map(Ctrl.Probe.apply)
        case "Result" :: v :: Nil => v.toIntOption.map(Ctrl.Result.apply)
        case _                    => None

  override def onStart(ctx: NodeContext): Unit =
    if ctx.selfId.value == 0 then
      nextClockwise(ctx).foreach { nxt =>
        ctx.logInfo(s"[irsize] starting probe to ${nxt.value}")
        ctx.send(nxt, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Probe(hops = 1))))
      }

  override def onMessage(ctx: NodeContext, env: Algorithm.Envelope): Unit =
    if env.kind != MsgKind.CONTROL then return
    dec(env.payload).foreach {
      case Ctrl.Probe(hops) =>
        if ctx.selfId.value == 0 then
          decided = Some(hops)
          ctx.logInfo(s"[irsize] ring size determined: n=$hops")
          // best-effort broadcast of result around the ring (clockwise)
          nextClockwise(ctx).foreach { nxt =>
            ctx.send(nxt, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Result(size = hops))))
          }
        else
          nextClockwise(ctx).foreach { nxt =>
            ctx.logInfo(s"[irsize] node=${ctx.selfId.value} fwd probe hops=$hops to=${nxt.value}")
            ctx.send(nxt, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Probe(hops = hops + 1))))
          }

      case Ctrl.Result(size) =>
        if decided.isEmpty then
          decided = Some(size)
          ctx.logInfo(s"[irsize] learned ring size: n=$size")
          // forward once so everyone learns; stop when it returns to 0
          if ctx.selfId.value != 0 then
            nextClockwise(ctx).foreach { nxt =>
              ctx.send(nxt, Algorithm.Envelope(ctx.selfId, MsgKind.CONTROL, enc(Ctrl.Result(size))))
            }
    }

  def sizeIfKnown: Option[Int] = decided

  private def nextClockwise(ctx: NodeContext): Option[NodeId] =
    val nbrs = ctx.neighbors.keys.toVector.sortBy(_.value)
    if nbrs.isEmpty then None
    else
      val expected = ctx.selfId.value + 1
      nbrs.find(_.value == expected).orElse(nbrs.headOption)

