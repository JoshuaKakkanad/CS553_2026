package edu.uic.cs553.sim.core

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

final class NetGameSimIOSpec extends AnyWordSpec with Matchers:
  "NetGameSimIO" should {
    "parse DOT edges and standalone node declarations" in {
      val tmp = Files.createTempFile("netgamesim-", ".dot")
      Files.writeString(
        tmp,
        """digraph "x" {
          |  "0" ["label"=<<b>Init</b>>]
          |  "1"
          |  "0" -> "2" ["weight"="1.0"]
          |}
          |""".stripMargin
      )
      val t = NetGameSimIO.readTopology(tmp, treatUndirectedAsBidirectional = true)
      t.nodes.map(_.value).toSet shouldBe Set(0, 1, 2)
      t.edges.map(e => (e.from.value, e.to.value)).toSet shouldBe Set((0, 2), (2, 0))
      Files.deleteIfExists(tmp)
    }

    "reject ngs files with actionable error" in {
      val tmp = Files.createTempFile("netgamesim-", ".ngs")
      val ex = intercept[IllegalArgumentException] {
        NetGameSimIO.readTopology(tmp)
      }
      ex.getMessage.toLowerCase should include("serialized internal format")
      Files.deleteIfExists(tmp)
    }
  }

