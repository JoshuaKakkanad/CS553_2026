package edu.uic.cs553.sim.cli

import com.typesafe.config.{Config, ConfigFactory}
import edu.uic.cs553.sim.core.*

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

object SimConfig:

  final case class TimerConf(node: NodeId, every: FiniteDuration, mode: TimerMode)

  final case class InjectionLine(at: FiniteDuration, node: NodeId, kind: MsgKind, payload: String)

  def loadFromArgs(args: Array[String]): (Config, CliArgs) =
    val cli = CliArgs.parse(args.toVector)
    val base =
      cli.configFile match
        case None    => ConfigFactory.load()
        case Some(f) => ConfigFactory.parseFile(f.toFile).resolve()
    (base, cli)

  /** Build an EnrichedGraph from config, with edge label overrides and node PDFs. */
  def buildGraph(conf: Config): EnrichedGraph =
    val sim = conf.getConfig("sim")
    val seed = if sim.hasPath("seed") then sim.getLong("seed") else 1L

    val graphConf = if sim.hasPath("graph") then sim.getConfig("graph") else ConfigFactory.empty()
    val n = if graphConf.hasPath("ringN") then graphConf.getInt("ringN") else 8

    val nodes = (0 until n).toVector.map(NodeId.apply)
    val edges = GraphGenerators.bidirectionalRing(n)
    enrichGraph(conf, nodes, edges, seed)

  def enrichGraph(conf: Config, nodes: Vector[NodeId], edges: Vector[Edge], seed: Long): EnrichedGraph =
    val sim = conf.getConfig("sim")

    val defaultAllowed =
      if sim.hasPath("edgeLabeling.default") then
        sim.getStringList("edgeLabeling.default").asScala.toSet.map(MsgKind.valueOf)
      else
        GraphGenerators.defaultEdgeLabel.allowed

    val baseLabels = GraphGenerators.labelAll(edges, EdgeLabel(defaultAllowed))

    val overrides: Map[Edge, EdgeLabel] =
      if sim.hasPath("edgeLabeling.overrides") then
        sim.getConfigList("edgeLabeling.overrides").asScala.toVector.map { c =>
          val from = NodeId(c.getInt("from"))
          val to = NodeId(c.getInt("to"))
          val allow = c.getStringList("allow").asScala.toSet.map(MsgKind.valueOf)
          Edge(from, to) -> EdgeLabel(allow)
        }.toMap
      else Map.empty

    val edgeLabels = baseLabels ++ overrides

    val defaultPdf = readPdf(sim, "traffic.default")
      .getOrElse(GraphGenerators.uniformPdf(Set(MsgKind.PING)))

    val perNode: Map[NodeId, Pdf] =
      if sim.hasPath("traffic.perNode") then
        sim.getConfigList("traffic.perNode").asScala.toVector.map { c =>
          val node = NodeId(c.getInt("node"))
          val pdf = readPdf(c, "pdf").getOrElse(defaultPdf)
          node -> pdf
        }.toMap
      else Map.empty

    val nodePdfs = nodes.map(id => id -> perNode.getOrElse(id, defaultPdf)).toMap

    EnrichedGraph(nodes, edges, edgeLabels, nodePdfs, seed)

  /**
   * Supported PDF spec:
   * - explicit: { family = "explicit", entries = [ {msg="PING", p=0.5}, ... ] }
   * - uniform:  { family = "uniform", kinds = ["PING","GOSSIP"] }
   * - zipf:     { family = "zipf", kinds = ["PING","GOSSIP","WORK"], s = 1.1 }
   */
  private def readPdf(conf: Config, path: String): Option[Pdf] =
    if !conf.hasPath(path) then None
    else
      val c = conf.getConfig(path)
      val fam = if c.hasPath("family") then c.getString("family").toLowerCase else "explicit"
      fam match
        case "explicit" =>
          val entries = c.getConfigList("entries").asScala.toVector.map { e =>
            MsgKind.valueOf(e.getString("msg")) -> e.getDouble("p")
          }
          Some(Pdf(entries.toMap).normalizedOrThrow())
        case "uniform" =>
          val kinds = c.getStringList("kinds").asScala.toSet.map(MsgKind.valueOf)
          Some(GraphGenerators.uniformPdf(kinds))
        case "zipf" =>
          val kinds = c.getStringList("kinds").asScala.toVector.map(MsgKind.valueOf)
          val s = if c.hasPath("s") then c.getDouble("s") else 1.1
          Some(zipfPdf(kinds, s))
        case other =>
          throw new IllegalArgumentException(s"unknown pdf family: $other at $path")

  private def zipfPdf(kinds: Vector[MsgKind], s: Double): Pdf =
    val ranked = kinds.distinct.zipWithIndex.map { case (k, i) =>
      val rank = i + 1
      k -> (1.0 / math.pow(rank.toDouble, s))
    }
    val sum = ranked.map(_._2).sum
    Pdf(ranked.map { case (k, w) => k -> (w / sum) }.toMap).normalizedOrThrow()

  def timers(conf: Config): List[TimerConf] =
    if !conf.hasPath("sim.initiators.timers") then Nil
    else
      conf.getConfigList("sim.initiators.timers").asScala.toList.map { c =>
        val node = NodeId(c.getInt("node"))
        val every = c.getLong("tickEveryMs").millis
        val mode = c.getString("mode").toLowerCase match
          case "pdf"   => TimerMode.Pdf
          case "fixed" => TimerMode.Fixed(MsgKind.valueOf(c.getString("fixedMsg")))
          case other   => throw new IllegalArgumentException(s"unknown timer mode: $other")
        TimerConf(node, every, mode)
      }

  def inputNodes(conf: Config): Set[NodeId] =
    if !conf.hasPath("sim.initiators.inputs") then Set.empty
    else
      conf.getConfigList("sim.initiators.inputs").asScala.toVector.map { c =>
        NodeId(c.getInt("node"))
      }.toSet

  def runFor(conf: Config): FiniteDuration =
    if conf.hasPath("sim.runForSeconds") then conf.getLong("sim.runForSeconds").seconds
    else 5.seconds

  final case class CliArgs(
    configFile: Option[java.nio.file.Path],
    outDir: Option[java.nio.file.Path],
    writeGraph: Option[java.nio.file.Path],
    graphFile: Option[java.nio.file.Path],
    netGameSimFile: Option[java.nio.file.Path],
    runForOverride: Option[FiniteDuration],
    injectFile: Option[java.nio.file.Path],
    interactive: Boolean
  )

  object CliArgs:
    def parse(args: Vector[String]): CliArgs =
      def loop(i: Int, acc: CliArgs): CliArgs =
        if i >= args.length then acc
        else
          args(i) match
            case "--config" =>
              loop(i + 2, acc.copy(configFile = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--out" =>
              loop(i + 2, acc.copy(outDir = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--write-graph" =>
              loop(i + 2, acc.copy(writeGraph = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--graph" =>
              loop(i + 2, acc.copy(graphFile = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--netgamesim" =>
              loop(i + 2, acc.copy(netGameSimFile = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--run" =>
              loop(i + 2, acc.copy(runForOverride = Some(parseDuration(args(i + 1)))))
            case "--inject-file" =>
              loop(i + 2, acc.copy(injectFile = Some(java.nio.file.Path.of(args(i + 1)))))
            case "--interactive" =>
              loop(i + 1, acc.copy(interactive = true))
            case other =>
              throw new IllegalArgumentException(s"unknown arg: $other")

      loop(
        0,
        CliArgs(
          configFile = None,
          outDir = None,
          writeGraph = None,
          graphFile = None,
          netGameSimFile = None,
          runForOverride = None,
          injectFile = None,
          interactive = false
        )
      )

    private def parseDuration(s: String): FiniteDuration =
      val str = s.trim.toLowerCase
      if str.endsWith("ms") then str.dropRight(2).toLong.millis
      else if str.endsWith("s") then str.dropRight(1).toLong.seconds
      else if str.endsWith("m") then str.dropRight(1).toLong.minutes
      else throw new IllegalArgumentException(s"bad duration (use 250ms, 10s, 2m): $s")

  /** Parse injection schedule file lines: `atMs node kind payload...` */
  def readInjectionFile(path: java.nio.file.Path): Vector[InjectionLine] =
    val lines = java.nio.file.Files.readAllLines(path).asScala.toVector
    lines
      .map(_.trim)
      .filter(l => l.nonEmpty && !l.startsWith("#"))
      .map { l =>
        val parts = l.split("\\s+", 4)
        if parts.length < 4 then
          throw new IllegalArgumentException(s"bad injection line (need: atMs node kind payload): $l")
        val at = parts(0).toLong.millis
        val node = NodeId(parts(1).toInt)
        val kind = MsgKind.valueOf(parts(2))
        val payload = parts(3)
        InjectionLine(at, node, kind, payload)
      }
      .sortBy(_.at.toMillis)

