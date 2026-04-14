package edu.uic.cs553.sim.cli

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import edu.uic.cs553.sim.core.*
import edu.uic.cs553.sim.runtime.*

import scala.concurrent.duration.*

/**
 * Minimal runnable entrypoint so we can iterate toward CourseProject.MD:
 * - build an enriched graph (currently: directed ring)
 * - map nodes -> classic actors, edges -> neighbor ActorRefs
 * - start timer-driven background traffic
 *
 * Next steps will add: graph artifact IO, edge label overrides, PDFs from config,
 * CLI injections, and the two required algorithms.
 */
object SimMain:
  def main(args: Array[String]): Unit =
    val conf = ConfigFactory.load()

    val n = if conf.hasPath("sim.graph.ringN") then conf.getInt("sim.graph.ringN") else 8
    val seed = if conf.hasPath("sim.seed") then conf.getLong("sim.seed") else 1L
    val tickMs = if conf.hasPath("sim.traffic.tickEveryMs") then conf.getLong("sim.traffic.tickEveryMs") else 100L

    val nodes = (0 until n).toVector.map(NodeId.apply)
    val edges = GraphGenerators.directedRing(n)
    val edgeLabels = GraphGenerators.labelAll(edges, GraphGenerators.defaultEdgeLabel)
    val pdf = GraphGenerators.uniformPdf(Set(MsgKind.PING))
    val nodePdfs = nodes.map(id => id -> pdf).toMap

    val g = EnrichedGraph(nodes, edges, edgeLabels, nodePdfs, seed)

    val system = ActorSystem("sim")
    try
      val algorithms: List[DistributedAlgorithm] = Nil

      val nodeRefs =
        g.nodes.map { id =>
          id -> system.actorOf(NodeActor.props(id, algorithms), s"node-${id.value}")
        }.toMap

      g.nodes.foreach { id =>
        val outgoing = g.outNeighbors(id)
        val neighbors = outgoing.map(to => to -> nodeRefs(to)).toMap
        val allowedOnEdge = outgoing.map(to => to -> g.allowedOnEdge(id, to)).toMap
        val pdf0 = g.nodePdfs(id)

        // For now: all nodes are timers.
        nodeRefs(id) ! NodeActor.Init(
          neighbors = neighbors,
          allowedOnEdge = allowedOnEdge,
          pdf = pdf0,
          timer = Some(tickMs.millis),
          seed = g.seed
        )
      }

      val runFor =
        if conf.hasPath("sim.runForSeconds") then conf.getLong("sim.runForSeconds").seconds
        else 5.seconds

      Thread.sleep(runFor.toMillis) // driver thread, not actor threads
    finally
      system.terminate()

