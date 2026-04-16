package edu.uic.cs553.sim.core

object GraphGenerators:

  /**
   * Deterministic directed ring: i -> (i+1) mod n.
   * Useful for ring-based algorithms (leader election, ring size, token passing).
   */
  def directedRing(n: Int): Vector[Edge] =
    require(n >= 2, "ring requires at least 2 nodes")
    (0 until n).toVector.map { i =>
      Edge(NodeId(i), NodeId((i + 1) % n))
    }

  /** Bidirectional ring: i <-> (i+1) mod n (as two directed edges). */
  def bidirectionalRing(n: Int): Vector[Edge] =
    val cw = directedRing(n)
    val ccw = cw.map(e => Edge(e.to, e.from))
    (cw ++ ccw).distinct

  def defaultEdgeLabel: EdgeLabel =
    EdgeLabel(Set(MsgKind.CONTROL, MsgKind.PING))

  def labelAll(edges: Vector[Edge], label: EdgeLabel): Map[Edge, EdgeLabel] =
    edges.map(e => e -> label).toMap

  def uniformPdf(kinds: Set[MsgKind]): Pdf =
    val p = 1.0 / kinds.size.toDouble
    Pdf(kinds.map(k => k -> p).toMap).normalizedOrThrow()

