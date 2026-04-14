package edu.uic.cs553.sim.core

import scala.concurrent.duration.FiniteDuration

/** Message "kinds" are intentionally explicit, not raw strings everywhere. */
enum MsgKind:
  case CONTROL, PING, GOSSIP, WORK, ACK

final case class NodeId(value: Int) extends AnyVal

final case class Edge(from: NodeId, to: NodeId)

final case class EdgeLabel(allowed: Set[MsgKind])

/** Discrete probability mass function over message kinds. */
final case class Pdf(p: Map[MsgKind, Double]):
  def normalizedOrThrow(tol: Double = 1e-9): Pdf =
    val sum = p.values.sum
    if sum.isNaN || sum.isInfinite then
      throw new IllegalArgumentException(s"PDF has invalid sum: $sum")
    if math.abs(sum - 1.0) > tol then
      throw new IllegalArgumentException(s"PDF must sum to 1.0 (got $sum)")
    this

final case class Initiators(
  timers: List[TimerInitiator],
  inputs: Set[NodeId]
)

final case class TimerInitiator(
  node: NodeId,
  tickEvery: FiniteDuration,
  mode: TimerMode
)

enum TimerMode:
  case Pdf
  case Fixed(kind: MsgKind)

/**
 * Enriched graph is what the runtime consumes.
 *
 * - edges: directed edges
 * - edgeLabels: which message kinds are allowed on each directed edge
 * - nodePdfs: per-node message generation PMF (application traffic)
 */
final case class EnrichedGraph(
  nodes: Vector[NodeId],
  edges: Vector[Edge],
  edgeLabels: Map[Edge, EdgeLabel],
  nodePdfs: Map[NodeId, Pdf],
  seed: Long
):
  def outNeighbors(from: NodeId): Vector[NodeId] =
    edges.collect { case Edge(f, t) if f == from => t }

  def allowedOnEdge(from: NodeId, to: NodeId): Set[MsgKind] =
    edgeLabels.getOrElse(Edge(from, to), EdgeLabel(Set.empty)).allowed

