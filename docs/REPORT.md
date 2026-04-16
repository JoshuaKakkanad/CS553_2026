## CS553 Course Project – Algorithm Report (UIN 678769216)

### Assigned algorithms (from the instructor’s rule)

Given \(U = 678769216\):

- **Index 1** \(= 1 + U \bmod 23 = 2\) → **Awerbuch beta synchronizer**
- **Index 2** \(= 1 + \lfloor U/23 \rfloor \bmod 23 = 15\) → **Itai–Rodeh ring size algorithm**

### System model implemented in this repository

- **Nodes**: Akka **classic** actors (`edu.uic.cs553.sim.runtime.NodeActor`), one per graph node.
- **Channels**: an outgoing edge is modeled by a stored destination `ActorRef`.
- **Message envelope**: all traffic uses `Algorithm.Envelope(from, kind, payload)`.
- **Edge labels**: each directed edge carries a set of allowed `MsgKind`; `NodeActor` enforces it on every `send`.
- **Background traffic**: timer-driven `Tick` samples the node PDF and sends an application `MsgKind` to one eligible neighbor.

### Algorithm 1 – Awerbuch beta synchronizer (tree-based pulse coordination)

**Implementation**: `edu/uic/cs553/sim/algorithms/BetaSynchronizer.scala`

**Assumptions modeled**

- We need a **spanning tree** to coordinate pulses. In this code we build one with a flood rooted at **node 0**.
- Channels are assumed **reliable** (no loss/duplication) and messages are eventually delivered.

**Protocol (CONTROL messages, payload prefix `beta:`)**

- **Tree construction**: `TreeHello:<id>` and `TreeAck:<id>`
- **Pulse**: `Pulse:<p>`
- **Safety acknowledgement**: `Safe:<p>`

**State per node**

- `parent`, `children`
- `pulse` number
- `safeFrom(p)` set for children acknowledgements

**How it matches the algorithm family**

This module implements the beta-style *tree aggregation* of “safe to advance” and root-driven pulse broadcast. It is intentionally a minimal pulse mechanism over the project’s existing substrate; it does not wrap/queue all application traffic in the current baseline runtime.

### Algorithm 2 – Itai–Rodeh ring size (practical probe variant)

**Implementation**: `edu/uic/cs553/sim/algorithms/ItaiRodehRingSize.scala`

**Important note on variant**

The original Itai–Rodeh family targets anonymous rings with probabilistic symmetry breaking. In this repository, the ring-size computation is implemented as a **distinguished-initiator probe** (initiator is node 0). This is documented explicitly to avoid quietly changing assumptions.

**Assumptions modeled**

- The topology used for experiments is a **bidirectional ring** (`GraphGenerators.bidirectionalRing`).
- A single node (**node 0**) is an initiator that starts the probe.
- The ring is oriented “clockwise” using the simulator’s stable node ids (choose neighbor with id `self+1`, else the smallest neighbor id).

**Protocol (CONTROL messages, payload prefix `irsize:`)**

- `Probe:<hops>` circulates clockwise; each node increments `hops` and forwards.
- When the probe returns to node 0, node 0 decides \(n = hops\) and forwards `Result:<n>` once around so all nodes learn it.

**State per node**

- `decided: Option[Int]` (known ring size if learned)

### Experiments and how to run

This repo’s runnable simulator entry point is `edu.uic.cs553.sim.cli.SimMain`.

- Run:

```bash
sbt "runMain edu.uic.cs553.sim.cli.SimMain"
```

- Run one of the included experiment configs and write artifacts:

```bash
rm -rf outputs/run1
sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment3_injection_demo.conf --inject-file conf/injections_demo.txt --out outputs/run1"
```

This produces:

- `outputs/run1/graph.json`
- `outputs/run1/metrics.json`

### Tests

Added ScalaTest coverage for the simulator-side algorithms in:

- `src/test/scala/edu/uic/cs553/sim/algorithms/SimAlgorithmsSpec.scala`

Run:

```bash
sbt test
```

