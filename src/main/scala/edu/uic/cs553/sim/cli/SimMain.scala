package edu.uic.cs553.sim.cli

import akka.actor.ActorSystem
import edu.uic.cs553.sim.core.*
import edu.uic.cs553.sim.algorithms.*
import edu.uic.cs553.sim.runtime.*

import scala.concurrent.duration.*

/**
 * CLI entrypoint aligned with CourseProject.MD:
 * - build/load an EnrichedGraph
 * - apply edge labels + PDFs from config
 * - map nodes -> classic actors, edges -> neighbor ActorRefs
 * - configure timer/input initiators
 * - optional message injection (interactive or file schedule)
 * - write run artifacts (graph + metrics) to --out directory
 */
object SimMain:
  def main(args: Array[String]): Unit =
    val (conf, cli) = SimConfig.loadFromArgs(args)

    Metrics.reset()

    val g: EnrichedGraph =
      cli.graphFile match
        case Some(path) =>
          val json = java.nio.file.Files.readString(path)
          GraphIO.readJson(json)
        case None =>
          cli.netGameSimFile match
            case Some(path) =>
              val sim = conf.getConfig("sim")
              val seed = if sim.hasPath("seed") then sim.getLong("seed") else 1L
              val undirectedAsBi =
                if sim.hasPath("netgamesim.treatUndirectedAsBidirectional")
                then sim.getBoolean("netgamesim.treatUndirectedAsBidirectional")
                else true
              val topology = NetGameSimIO.readTopology(
                json = java.nio.file.Files.readString(path),
                treatUndirectedAsBidirectional = undirectedAsBi
              )
              SimConfig.enrichGraph(conf, topology.nodes, topology.edges, seed)
            case None =>
              SimConfig.buildGraph(conf)

    if cli.writeGraph.nonEmpty then
      val out = cli.writeGraph.get
      out.getParent match
        case null => ()
        case p    => java.nio.file.Files.createDirectories(p)
      java.nio.file.Files.writeString(out, GraphIO.writeJson(g))
      // graph-only mode
      ()
    else
      runSimulation(conf, cli, g)

  private def runSimulation(conf: com.typesafe.config.Config, cli: SimConfig.CliArgs, g: EnrichedGraph): Unit =
    val system = ActorSystem("sim")
    try
      val algorithms: List[DistributedAlgorithm] =
        List(
          new BetaSynchronizer(maxPulses = 5),
          new ItaiRodehRingSize()
        )

      val nodeRefs =
        g.nodes.map { id =>
          id -> system.actorOf(NodeActor.props(id, algorithms), s"node-${id.value}")
        }.toMap

      val timers = SimConfig.timers(conf)
      val timerByNode: Map[NodeId, SimConfig.TimerConf] = timers.map(t => t.node -> t).toMap
      val inputNodes = SimConfig.inputNodes(conf)

      g.nodes.foreach { id =>
        val outgoing = g.outNeighbors(id)
        val neighbors = outgoing.map(to => to -> nodeRefs(to)).toMap
        val allowedOnEdge = outgoing.map(to => to -> g.allowedOnEdge(id, to)).toMap
        val pdf0 = g.nodePdfs(id)

        val timer0 = timerByNode.get(id).map(_.every)
        val timerMode0 = timerByNode.get(id).map(_.mode).getOrElse(TimerMode.Pdf)

        nodeRefs(id) ! NodeActor.Init(
          neighbors = neighbors,
          allowedOnEdge = allowedOnEdge,
          pdf = pdf0,
          timer = timer0,
          timerMode = timerMode0,
          seed = g.seed
        )
      }

      val runFor = cli.runForOverride.getOrElse(SimConfig.runFor(conf))

      // optional injections
      val startNs = System.nanoTime()

      cli.injectFile.foreach { p =>
        val schedule = SimConfig.readInjectionFile(p)
        schedule.foreach { line =>
          if inputNodes.contains(line.node) then
            val sleepMs = line.at.toMillis - ((System.nanoTime() - startNs) / 1000000L)
            if sleepMs > 0 then Thread.sleep(sleepMs)
            nodeRefs.get(line.node).foreach(_ ! NodeActor.ExternalInput(line.kind, line.payload))
        }
      }

      if cli.interactive then
        val in = scala.io.Source.stdin.getLines()
        // commands:
        //   send <node> <KIND> <payload...>
        //   quit
        var keepGoing = true
        while in.hasNext && keepGoing do
          val line = in.next().trim
          if line == "quit" then keepGoing = false
          else if line.startsWith("send ") then
            val parts = line.split("\\s+", 4)
            if parts.length >= 4 then
              val node = NodeId(parts(1).toInt)
              val kind = MsgKind.valueOf(parts(2))
              val payload = parts(3)
              if inputNodes.contains(node) then
                nodeRefs.get(node).foreach(_ ! NodeActor.ExternalInput(kind, payload))

      val elapsedMs = (System.nanoTime() - startNs) / 1000000L
      val remaining = runFor.toMillis - elapsedMs
      if remaining > 0 then
        Thread.sleep(remaining) // driver thread, not actor threads

      cli.outDir.foreach { outDir =>
        java.nio.file.Files.createDirectories(outDir)
        java.nio.file.Files.writeString(outDir.resolve("graph.json"), GraphIO.writeJson(g))
        val m = Metrics.snapshot()
        val json =
          s"""{
             |  "sent": ${toJsonObj(m.sent)},
             |  "received": ${toJsonObj(m.received)},
             |  "dropped": ${toJsonObj(m.dropped)},
             |  "externalInputs": ${toJsonObj(m.externalInputs)}
             |}
             |""".stripMargin
        java.nio.file.Files.writeString(outDir.resolve("metrics.json"), json)
      }
    finally
      system.terminate()

  private def toJsonObj(m: Map[String, Long]): String =
    m.toVector.sortBy(_._1).map { case (k, v) => "\"" + k + "\": " + v }.mkString("{", ", ", "}")

