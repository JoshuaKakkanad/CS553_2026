package edu.uic.cs553.sim.core

import ujson.*
import java.nio.file.Path
import scala.jdk.CollectionConverters.*

/**
 * Reader for NetGameSim exports.
 *
 * Supported input files:
 * - .dot          (preferred; matches NetGameSim graphviz output)
 * - .json         (strict shape: nodes + edges arrays)
 *
 * .ngs files are NetGameSim internal serialized artifacts and are not
 * directly parseable here without NetGameSim model classes.
 */
object NetGameSimIO:
  final case class Topology(nodes: Vector[NodeId], edges: Vector[Edge])

  def readTopology(path: Path, treatUndirectedAsBidirectional: Boolean = true): Topology =
    val name = path.getFileName.toString.toLowerCase
    if name.endsWith(".dot") then
      readTopologyFromDot(java.nio.file.Files.readAllLines(path).asScala.toVector, treatUndirectedAsBidirectional)
    else if name.endsWith(".json") then
      readTopologyFromJson(java.nio.file.Files.readString(path), treatUndirectedAsBidirectional)
    else if name.endsWith(".ngs") || name.endsWith(".ngs.perturbed") then
      throw new IllegalArgumentException(
        s"Unsupported NetGameSim artifact '${path.getFileName}': .ngs is a serialized internal format. " +
          s"Use the corresponding .dot export file (for example: <name>.dot or <name>.perturbed.dot)."
      )
    else
      throw new IllegalArgumentException(
        s"Unsupported netgamesim input extension for '${path.getFileName}'. Use .dot or .json."
      )

  private def readTopologyFromJson(json: String, treatUndirectedAsBidirectional: Boolean): Topology =
    val root = ujson.read(json)
    val obj = root.obj
    if !obj.contains("nodes") || !obj.contains("edges") then
      throw new IllegalArgumentException("NetGameSim JSON must contain top-level 'nodes' and 'edges' arrays.")

    val nodesFromJson: Vector[NodeId] =
      obj.get("nodes") match
        case Some(arr: Arr) =>
          arr.value.toVector.map(nodeIdFromJsonStrict)
        case _ =>
          throw new IllegalArgumentException("'nodes' must be an array.")

    val rawEdges: Vector[Edge] =
      obj.get("edges") match
        case Some(arr: Arr) =>
          arr.value.toVector.map(edgeFromJsonStrict)
        case _ =>
          throw new IllegalArgumentException("'edges' must be an array.")

    val directionalEdges =
      if treatUndirectedAsBidirectional then
        rawEdges ++ rawEdges.map(e => Edge(e.to, e.from))
      else rawEdges

    val edgeSet = directionalEdges.distinct

    val inferredNodes = edgeSet.flatMap(e => Vector(e.from, e.to)).distinct
    val nodes = (nodesFromJson ++ inferredNodes).distinct.sortBy(_.value)

    Topology(nodes = nodes, edges = edgeSet)

  private def nodeIdFromJsonStrict(v: Value): NodeId =
    v match
      case Num(n) => NodeId(n.toInt)
      case Obj(fields) =>
        fields.get("id") match
          case Some(Num(n)) => NodeId(n.toInt)
          case _ => throw new IllegalArgumentException("Node object must contain numeric field 'id'.")
      case _ =>
        throw new IllegalArgumentException("Each node must be either a number or object {id:<number>}.")

  private def edgeFromJsonStrict(v: Value): Edge =
    v match
      case Obj(fields) =>
        val from = fields.get("source").orElse(fields.get("from")).collect { case Num(n) => NodeId(n.toInt) }
        val to = fields.get("target").orElse(fields.get("to")).collect { case Num(n) => NodeId(n.toInt) }
        (from, to) match
          case (Some(f), Some(t)) => Edge(f, t)
          case _ =>
            throw new IllegalArgumentException("Each edge must contain numeric 'source'/'target' or 'from'/'to'.")
      case _ =>
        throw new IllegalArgumentException("Each edge must be an object.")

  private def readTopologyFromDot(lines: Vector[String], treatUndirectedAsBidirectional: Boolean): Topology =
    val edgeRegex = raw""""?(\d+)"?\s*->\s*"?(\d+)"?""".r
    val undirectedRegex = raw""""?(\d+)"?\s*--\s*"?(\d+)"?""".r
    val nodeRegex = "^\\s*\"?(\\d+)\"?\\s*(?:\\[[^\\]]*\\])?\\s*;?\\s*$".r

    val directed = lines.flatMap { line =>
      edgeRegex.findFirstMatchIn(line).map(m => Edge(NodeId(m.group(1).toInt), NodeId(m.group(2).toInt)))
    }
    val undirected = lines.flatMap { line =>
      undirectedRegex.findFirstMatchIn(line).map(m => Edge(NodeId(m.group(1).toInt), NodeId(m.group(2).toInt)))
    }
    val declaredNodes = lines.flatMap {
      case nodeRegex(id) => Some(NodeId(id.toInt))
      case _             => None
    }

    val rawEdges = directed ++ undirected
    if rawEdges.isEmpty then
      throw new IllegalArgumentException("No edges parsed from DOT file.")

    val withUndirected =
      if treatUndirectedAsBidirectional then
        rawEdges ++ rawEdges.map(e => Edge(e.to, e.from))
      else rawEdges

    val edges = withUndirected.distinct
    val nodes =
      (declaredNodes ++ edges.flatMap(e => Vector(e.from, e.to)))
        .distinct
        .sortBy(_.value)
    Topology(nodes = nodes, edges = edges)

