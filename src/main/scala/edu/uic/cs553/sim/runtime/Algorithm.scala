package edu.uic.cs553.sim.runtime

import edu.uic.cs553.sim.core.{MsgKind, NodeId}
import akka.actor.ActorRef

object Algorithm:
  /** All messages in the simulator share this envelope. */
  final case class Envelope(from: NodeId, kind: MsgKind, payload: String)

trait NodeContext:
  def selfId: NodeId
  def neighbors: Map[NodeId, ActorRef]
  def send(to: NodeId, env: Algorithm.Envelope): Unit
  def logInfo(msg: String): Unit

trait DistributedAlgorithm:
  def name: String
  def onStart(ctx: NodeContext): Unit = ()
  def onTick(ctx: NodeContext): Unit = ()
  def onMessage(ctx: NodeContext, env: Algorithm.Envelope): Unit = ()

