# `perf` module group

End-to-end performance measurement of the app, which runs the app as a black box from its JAR.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`perf-midi-latency`](perf-midi-latency/docs/ARCHITECTURE.md) | The MIDI latency suite: launches the app's JAR, measures its latency end to end and records the statistics | `org.calinburloiu.music.microtonalist.perf.midilatency` |

Each module measures one aspect of performance, `perf-<aspect>`, and keeps its results, and the report that reads them,
in its own directory.

How to run the MIDI latency suite and open its report:
[`docs/development/performance-tests.md`](../docs/development/performance-tests.md).
