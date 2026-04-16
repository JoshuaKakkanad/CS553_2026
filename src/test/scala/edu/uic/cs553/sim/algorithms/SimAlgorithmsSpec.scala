package edu.uic.cs553.sim.algorithms

import akka.actor.ActorRef
import edu.uic.cs553.sim.core.{MsgKind, NodeId}
import edu.uic.cs553.sim.runtime.{Algorithm, NodeContext}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

final class SimAlgorithmsSpec extends AnyWordSpec with Matchers:

  private final class CapturingCtx(
    override val selfId: NodeId,
    neighborIds: Vector[NodeId]
  ) extends NodeContext:
    override val neighbors: Map[NodeId, ActorRef] =
      neighborIds.map(_ -> null.asInstanceOf[ActorRef]).toMap

    val sent: scala.collection.mutable.ArrayBuffer[(NodeId, Algorithm.Envelope)] =
      scala.collection.mutable.ArrayBuffer.empty

    val logs: scala.collection.mutable.ArrayBuffer[String] =
      scala.collection.mutable.ArrayBuffer.empty

    override def send(to: NodeId, env: Algorithm.Envelope): Unit =
      sent.append((to, env))

    override def logInfo(msg: String): Unit =
      logs.append(msg)

  "GraphGenerators.bidirectionalRing" should {
    "create 2n directed edges for a ring" in {
      val edges = edu.uic.cs553.sim.core.GraphGenerators.bidirectionalRing(5)
      edges.size shouldBe 10
      edges.distinct.size shouldBe 10
    }
  }

  "ItaiRodehRingSize" should {
    "have node 0 send a CONTROL probe on start" in {
      val alg = new ItaiRodehRingSize()
      val ctx0 = new CapturingCtx(NodeId(0), Vector(NodeId(1), NodeId(4)))
      alg.onStart(ctx0)

      ctx0.sent.size shouldBe 1
      val (to, env) = ctx0.sent.head
      to shouldBe NodeId(1)
      env.kind shouldBe MsgKind.CONTROL
      env.payload.startsWith("irsize:") shouldBe true
    }

    "increment hop count at non-leader nodes" in {
      val alg = new ItaiRodehRingSize()
      val ctx0 = new CapturingCtx(NodeId(0), Vector(NodeId(1), NodeId(4)))
      alg.onStart(ctx0)
      val probePayload = ctx0.sent.head._2.payload

      val ctx1 = new CapturingCtx(NodeId(1), Vector(NodeId(0), NodeId(2)))
      alg.onMessage(ctx1, Algorithm.Envelope(from = NodeId(0), kind = MsgKind.CONTROL, payload = probePayload))
      ctx1.sent.size shouldBe 1
      val (_, env) = ctx1.sent.head
      env.payload shouldBe "irsize:Probe:2"
    }

    "let node 0 decide size when probe returns" in {
      val alg0 = new ItaiRodehRingSize()
      val alg1 = new ItaiRodehRingSize()
      val alg2 = new ItaiRodehRingSize()

      val ctx0 = new CapturingCtx(NodeId(0), Vector(NodeId(1), NodeId(2)))
      val ctx1 = new CapturingCtx(NodeId(1), Vector(NodeId(0), NodeId(2)))
      val ctx2 = new CapturingCtx(NodeId(2), Vector(NodeId(0), NodeId(1)))

      def deliver(to: NodeId, env: Algorithm.Envelope): Unit =
        to match
          case NodeId(0) => alg0.onMessage(ctx0, env)
          case NodeId(1) => alg1.onMessage(ctx1, env)
          case NodeId(2) => alg2.onMessage(ctx2, env)
          case other     => fail(s"unexpected node id $other")

      // Start at node 0; then manually relay captured sends around the ring.
      alg0.onStart(ctx0)

      val q = scala.collection.mutable.Queue.empty[(NodeId, Algorithm.Envelope)]
      q.enqueueAll(ctx0.sent.toSeq); ctx0.sent.clear()

      var steps = 0
      while q.nonEmpty && steps < 20 do
        val (to, env) = q.dequeue()
        deliver(to, env)
        q.enqueueAll(ctx0.sent.toSeq); ctx0.sent.clear()
        q.enqueueAll(ctx1.sent.toSeq); ctx1.sent.clear()
        q.enqueueAll(ctx2.sent.toSeq); ctx2.sent.clear()
        steps += 1

      alg0.sizeIfKnown shouldBe Some(3)
    }
  }

  "BetaSynchronizer" should {
    "prefix its control payloads so it can share CONTROL kind" in {
      val beta = new BetaSynchronizer(maxPulses = 2)
      val root = new CapturingCtx(NodeId(0), Vector(NodeId(1), NodeId(2)))
      beta.onStart(root)
      root.sent.nonEmpty shouldBe true
      root.sent.map(_._2.payload.startsWith("beta:")).forall(identity) shouldBe true
    }
  }

