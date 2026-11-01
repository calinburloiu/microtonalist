# `perf` module group

Performance measurement of the app: the suites that measure it from its JAR, and any module that helps measure
performance across the others, such as shared measurement utilities.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`perf-midi-latency`](perf-midi-latency/docs/ARCHITECTURE.md) | The MIDI latency suite: launches the app's JAR, measures its latency end to end and records the statistics | `org.calinburloiu.music.microtonalist.perf.midilatency` |

A module named `perf-<aspect>` measures one aspect of performance, and keeps its results, and the report that reads
them, in its own directory.

How to run the MIDI latency suite and open its report:
[`docs/development/performance-tests.md`](../docs/development/performance-tests.md).
