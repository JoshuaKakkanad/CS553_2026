# CS553_2026 - Course Project Simulator

This repository contains the CS553 course-project implementation of an Akka classic distributed-systems simulator that maps enriched graphs to actor networks and runs assigned distributed algorithms.

## What This Repo Runs Today

- Graph to actor mapping (`node -> Actor`, `edge -> ActorRef channel`)
- Config-driven edge labels and enforcement
- Config-driven PDFs (explicit, uniform, zipf) with per-node overrides
- Config-driven initiators:
  - timer nodes (`pdf` or `fixed` mode)
  - input nodes for external injections
- CLI injection modes:
  - file-driven (`--inject-file`)
  - interactive (`--interactive`)
- Output artifacts:
  - `graph.json`
  - `metrics.json`
- Assigned algorithms integrated:
  - Awerbuch beta synchronizer
  - Itai-Rodeh ring-size variant (ring-only; skipped on non-ring graphs)
- NetGameSim integration for DOT exports (`.ngs.dot`, `.ngs.perturbed.dot`)

## Prerequisites

- Java 11+
- SBT 1.9+
- Scala 3.3.x (configured in `build.sbt`)

## Build and Test

```bash
sbt compile
sbt test
```

## Main CLI

Entrypoint:

```bash
sbt "runMain edu.uic.cs553.sim.cli.SimMain"
```

Common options:

- `--config <path>`: use a specific config file from `conf/`
- `--out <dir>`: write `graph.json` and `metrics.json`
- `--write-graph <path>`: write enriched graph JSON and exit
- `--graph <path>`: load enriched graph JSON
- `--netgamesim <path>`: load NetGameSim `.dot` / `.perturbed.dot`
- `--run <10s|250ms|2m>`: override runtime duration
- `--inject-file <path>`: scheduled external injections (`atMs node kind payload...`)
- `--interactive`: interactive injections (`send <node> <KIND> <payload...>`, `quit`)

## Example Commands

### 1) Standard experiment with metrics output

```bash
rm -rf outputs/run1
sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment3_injection_demo.conf --inject-file conf/injections_demo.txt --out outputs/run1"
```

### 2) Graph artifact write/load workflow

```bash
rm -rf outputs/run2
sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment1_small_ring.conf --write-graph outputs/run2/graph.json"
sbt "runMain edu.uic.cs553.sim.cli.SimMain --graph outputs/run2/graph.json --config conf/experiment1_small_ring.conf --out outputs/run2"
```

### 3) Real NetGameSim DOT ingestion

```bash
sbt "runMain edu.uic.cs553.sim.cli.SimMain --netgamesim NetGameSimOut/NetGraph_16-04-26-11-19-04.ngs.perturbed.dot --config conf/experiment1_small_ring.conf --out outputs/netgamesim-real --run 10s"
```

## Cinnamon Status (CourseProject.MD Note)

`CourseProject.MD` asks for Cinnamon instrumentation, but Cinnamon artifacts are behind Akka tokenized repository access. In this repository state, Cinnamon is intentionally **not hard-enabled in `build.sbt`** to keep `sbt compile/test/run` stable on clean machines.

If your environment has valid Akka commercial repository access, Cinnamon can be enabled locally by following the tokenized resolver + plugin/dependency steps in `CourseProject.MD`.

## NetGameSim File Compatibility

- Supported directly by `--netgamesim`:
  - `*.ngs.dot`
  - `*.ngs.perturbed.dot`
- Not supported directly:
  - `*.ngs`, `*.ngs.perturbed` (internal serialized format)

Use the corresponding DOT export when running the simulator.

## Notes on Legacy Modules

This repo still contains older `com.uic.cs553.distributed.*` teaching/example modules. The project submission path used for CourseProject.MD is `edu.uic.cs553.sim.*`.

## Grader Submission Checklist (CourseProject.MD minus Cinnamon)

This checklist is the fastest way to verify the course-project deliverables on a clean checkout.

### 1) Build + tests

```bash
sbt compile
sbt test
```

Expected: all tests pass (currently 11 tests).

### 2) Three experiment configurations

These are checked into `conf/`:

- `conf/experiment1_small_ring.conf`
- `conf/experiment2_edge_override.conf`
- `conf/experiment3_injection_demo.conf`

Run each and confirm it produces `graph.json` + `metrics.json`:

```bash
rm -rf outputs/exp1 outputs/exp2 outputs/exp3

sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment1_small_ring.conf --out outputs/exp1 --run 8s"
sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment2_edge_override.conf --out outputs/exp2 --run 10s"
sbt "runMain edu.uic.cs553.sim.cli.SimMain --config conf/experiment3_injection_demo.conf --inject-file conf/injections_demo.txt --out outputs/exp3 --run 15s"

ls outputs/exp1 outputs/exp2 outputs/exp3
```

### 3) NetGameSim artifact ingestion (DOT)

Use the NetGameSim DOT exports (not `.ngs`):

```bash
rm -rf outputs/netgamesim-real
sbt "runMain edu.uic.cs553.sim.cli.SimMain --netgamesim NetGameSimOut/NetGraph_16-04-26-11-19-04.ngs.perturbed.dot --config conf/experiment1_small_ring.conf --out outputs/netgamesim-real --run 10s"
ls outputs/netgamesim-real
```

Expected behavior note:

- NetGameSim graphs are not bidirectional rings, so the simulator prints that `ItaiRodehRingSize` is skipped and runs `BetaSynchronizer` only.

### 4) Report

See `docs/REPORT.md` for:

- system model
- message protocols
- assumptions/variants
- how to run experiments

### 5) Cinnamon (explicitly out of scope for this submission build)

`CourseProject.MD` mentions Cinnamon, but this repository is configured to remain buildable without commercial Akka tokenized repository access.

If you need Cinnamon locally, follow Akka’s tokenized resolver instructions and enable Cinnamon plugin/deps as described in `CourseProject.MD`.

