# `perf` module group

End-to-end performance measurement of the app, which runs the app as a black box from its JAR.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`perf`](perf/docs/ARCHITECTURE.md) | The MIDI latency suite: launches the app's JAR, measures its latency end to end and records the statistics | `org.calinburloiu.music.microtonalist.perf` |

Besides the module, the group holds the suite's committed results and the report that reads them:

- `results/` — a run file per run, `<machine id>/<UTC start time>-<app version>-<short commit>.json`, and `index.json`,
  which lists the runs. The suite writes them; they are committed to track the latency across versions.
- `report/` — a single page, `index.html`, that explores the results, and `test.html`, which tests its statistics in
  the browser. It needs no build step, only a static file server.

How to run the suite and open the report: [`docs/development/performance-tests.md`](../docs/development/performance-tests.md).
