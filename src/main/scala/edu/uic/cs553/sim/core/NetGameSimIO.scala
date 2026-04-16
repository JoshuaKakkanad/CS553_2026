package edu.uic.cs553.sim.core

import ujson.*

/**
 * Lenient reader for NetGameSim-like graph JSON exports.
 *
 * Supported patterns:
 * - nodes: [0,1,2] OR nodes: [{id:0}, {id:1}]
 * - edges: [{from:0,to:1}] OR [{source:0,target:1}] OR {u,v}
 */
object NetGameSimIO:
  final case class Topology(nodes: Vector[NodeId], edges: Vector[Edge])

  def readTopology(json: String, treatUndirectedAsBidirectional: Boolean = true): Topology =
    val root = ujson.read(json)
    val obj = root.obj

    val nodesFromJson: Vector[NodeId] =
      obj.get("nodes") match
        case Some(arr: Arr) =>
          arr.value.toVector.flatMap(nodeIdFromJson)
        case _ => Vector.empty

    val rawEdges: Vector[Edge] =
      obj.get("edges") match
        case Some(arr: Arr) =>
          arr.value.toVector.flatMap(edgeFromJson)
        case _ => Vector.empty

    val directionalEdges =
      if treatUndirectedAsBidirectional then
        rawEdges ++ rawEdges.map(e => Edge(e.to, e.from))
      else rawEdges

    val edgeSet = directionalEdges.distinct

    val inferredNodes = edgeSet.flatMap(e => Vector(e.from, e.to)).distinct
    val nodes = (nodesFromJson ++ inferredNodes).distinct.sortBy(_.value)

    Topology(nodes = nodes, edges = edgeSet)

  private def nodeIdFromJson(v: Value): Option[NodeId] =
    v match
      case Num(n) => Some(NodeId(n.toInt))
      case Obj(fields) =>
        fields
          .get("id")
          .orElse(fields.get("nodeId"))
          .collect { case Num(n) => NodeId(n.toInt) }
      case _ => None

  private def edgeFromJson(v: Value): Option[Edge] =
    v match
      case Obj(fields) =>
        val from =
          fields.get("from")
            .orElse(fields.get("source"))
            .orElse(fields.get("u"))
            .collect { case Num(n) => NodeId(n.toInt) }
        val to =
          fields.get("to")
            .orElse(fields.get("target"))
            .orElse(fields.get("v"))
            .collect { case Num(n) => NodeId(n.toInt) }
        for
          f <- from
          t <- to
        yield Edge(f, t)
      case _ => None

