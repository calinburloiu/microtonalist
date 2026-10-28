# Module overview

A *module* is an SBT project. Modules are organized in *module groups*: each group is a folder at the repository root
that holds the modules of one area, `<group>/<module>/`. A group's `README.md` lists every module in it, with its root
package; the group's `CLAUDE.md` imports that README, so an agent gets it when working in the group.

## Module groups

| Group | Area |
| ----- | ---- |
| [`base`](../../base/README.md) | Infrastructure: shared utilities, and the event bus with its threading model |
| [`theory`](../../theory/README.md) | Music theory: interval math and the composition model |
| [`midi`](../../midi/README.md) | A Scala-idiomatic MIDI API over Java Sound |
| [`tuner`](../../tuner/README.md) | Real-time tuning of the instruments over MIDI |
| [`rig`](../../rig/README.md) | The tracks a performer plays, one per instrument |
| [`format`](../../format/README.md) | Persistence of compositions, scales and rigs: what the format modules share, and their wiring |
| [`ui`](../../ui/README.md) | The desktop GUI |
| [`ai`](../../ai/README.md) | Support for AI agents: the base of the MCP servers |
| [`app`](../../app/README.md) | The application layer: the executables, and their configuration and wiring |
| [`testbench`](../../testbench/README.md) | Tooling that tests the app from outside it: the QA MCP server and the QA driver of the `gui-qa` agent |

## Main modules

| Module | Group | Description |
| ------ | ----- | ----------- |
| `app` | `app` | The desktop application, which wires the modules together |
| `config` | `app` | HOCON configuration of the application |
| `cli` | `app` | Command-line utility tool, e.g. for listing the MIDI devices |
| `ui` | `ui` | Swing GUI |
| `format` | `format` | Wiring of the format modules into the repositories of compositions, scales and rigs |
| `format-core` | `format` | What the format modules share, such as (de)serializing plugins |
| `rig` | `rig` | The tracks, each routing MIDI from an input to an output through a tuning changer and a tuner |
| `tuner` | `tuner` | MIDI tuning engine: the `Tuner` contract and the tuning session |
| `tuning-changer` | `tuner` | Contract of the tuning changers, which decide when to change the tuning |
| `tuning` | `tuner` | The `Tuning` and `TuningChange` values |
| `composition` | `theory` | The composition model, which turns scales into tunings |
| `intonation` | `theory` | Interval math |
| `sc-midi-javamidi` | `midi` | Java Sound implementation of `sc-midi`, with CoreMIDI4J |
| `sc-midi` | `midi` | Scala-idiomatic MIDI API |
| `businessync` | `base` | Event bus and threading |
| `common` | `base` | Shared utilities |

Each `<module>-format` module (de)serializes the types of `<module>` and sits in the same group, e.g.
`composition-format` in `theory`. Each `Tuner` and `TuningChanger` implementation has a module of its own in `tuner`,
e.g. `tuner-mts`, `tuner-mpe` and `tuning-changer-pedal`, and its family's format module, `tuner-format` or
`tuning-changer-format`, holds its format.

## Dependencies

The main modules and their direct dependencies, leaving out those that others imply; `build.sbt` has them all.

```mermaid
flowchart TD
  subgraph appGroup ["app/"]
    app
    config
    cli
  end
  subgraph uiGroup ["ui/"]
    ui
  end
  subgraph formatGroup ["format/"]
    format
    formatCore["format-core"]
  end
  subgraph rigGroup ["rig/"]
    rig
  end
  subgraph theoryGroup ["theory/"]
    composition
    intonation
  end
  subgraph tunerGroup ["tuner/"]
    tuner
    tuningChanger["tuning-changer"]
    tuning
  end
  subgraph midiGroup ["midi/"]
    scMidiJavamidi["sc-midi-javamidi"]
    scMidi["sc-midi"]
  end
  subgraph baseGroup ["base/"]
    businessync
    common
  end
  app --> config & ui & format & composition & scMidiJavamidi
  format --> formatCore & rig
  formatCore --> scMidi
  composition --> intonation & tuning
  rig --> tuner & tuningChanger
  ui --> tuner
  tuner --> tuning & businessync
  tuningChanger --> tuning
  tuning --> scMidi
  cli --> scMidiJavamidi
  scMidiJavamidi --> scMidi
  scMidi --> common
  config --> common
```
