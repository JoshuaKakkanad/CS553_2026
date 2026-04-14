package edu.uic.cs553.sim.core

import upickle.default.*

object GraphIO:
  // uPickle codecs
  given ReadWriter[MsgKind] = readwriter[String].bimap(_.toString, MsgKind.valueOf)
  given ReadWriter[NodeId] = readwriter[Int].bimap(_.value, NodeId.apply)
  given ReadWriter[Edge] = macroRW
  given ReadWriter[EdgeLabel] = macroRW
  given ReadWriter[Pdf] = macroRW
  given ReadWriter[EnrichedGraph] = macroRW

  def writeJson(g: EnrichedGraph): String =
    write(g, indent = 2)

  def readJson(json: String): EnrichedGraph =
    read[EnrichedGraph](json)

