# Performance Tests

The `perf-midi-latency` module measures the app's MIDI latency end to end: it launches the app from a JAR, sends MIDI
to the app's input device, timestamps what comes out of its output device, and records the statistics of each metric in
a run file under `perf/perf-midi-latency/results/`, which is committed. A page under `perf/perf-midi-latency/report/`
explores the runs. The design is in
[`perf/perf-midi-latency/docs/ARCHITECTURE.md`](../../perf/perf-midi-latency/docs/ARCHITECTURE.md).

Before the cases, the run measures the floor, the setup's own latency: it sends a message on the app's input device and
receives it back from it, with no app running. The floor tells whether the machine and the devices behave as in other
runs; it isn't a baseline to subtract from the cases, whose path crosses both devices and the app.

The suite needs MIDI devices and depends on the machine, so it runs by hand, not in CI. Without a JAR, its cases are
canceled, and the normal test suite stays green on any machine.

## Set up the MIDI devices

The suite needs two loopback MIDI devices: one into the app, which the suite sends to, and one out of it, which the
suite receives from. A loopback device delivers what it is sent back to its listeners.

On macOS, use two buses of the IAC Driver: in *Audio MIDI Setup*, open *Window → Show MIDI Studio*, double-click *IAC
Driver*, check *Device is online* and add a second bus. List the devices with the `cli` tool
(`bin/mtlist-tool midi-devices`, after `sbt cli/assembly`).

Then put them in `perf/perf-midi-latency/src/test/resources/midi-latency-config.local.json`, which git ignores and
which overrides the committed
[`midi-latency-config.json`](../../perf/perf-midi-latency/src/test/resources/midi-latency-config.json) key by key:

```json
{
  "devices": {
    "input": { "name": "CoreMIDI4J - IAC 1", "vendor": "Apple Inc." },
    "output": { "name": "CoreMIDI4J - IAC 2", "vendor": "Apple Inc." }
  },
  "machineName": "My MacBook"
}
```

`machineName` is an optional label for the report. `midi-latency-config.json` also holds the defaults of a run (3
launches of 10,000 warm-up and 1,000 timed iterations each, the timed ones paced every 50 ms), the timeouts, and the JDK
and JVM options of the app.

The IAC Driver mutes a bus for 12 seconds, for every app, when it carries more than about 1,500 short messages a second
for a few seconds, or a burst of 15,000; a 21-byte SysEx counts about as much as four short messages. Apple doesn't
document it. So the suite paces its warm-up for each device to carry at most `devices.maxMessagesPerSecond` messages a
second, 1,000 by default, counting a SysEx as one per 6 data bytes. Set the limit of other devices in
`midi-latency-config.local.json`, or `0` for devices that have none. A case whose step times out with nothing received
has likely hit the limit.

## Build the JAR to measure

For the current working tree:

```bash
sbtn app/assembly    # app/app/target-bsp/scala-<version>/microtonalist-app.jar
```

For another commit, build it in a clone outside the repository, so that your working tree and the sbt server stay as
they are:

```bash
git clone . ../microtonalist-<sha> && cd ../microtonalist-<sha> && git checkout <sha>
sbt app/assembly     # app/app/target/scala-<version>/microtonalist-app.jar
```

A JAR records the commit it was built from in its `BuildInfo`, except one built before the latency suite existed, such
as `a8ba00df`: give its commit with `-Dcommit`.

## Run the suite

Commit your changes first: a run records the suite's commit and whether its working tree was dirty, leaving out the
results under `perf/perf-midi-latency/results/`, so that you can measure several JARs before committing their runs.
Then, with the development stack running:

```bash
bin/mtlist-perf-midi-latency run --jar <path to the JAR> --commit a8ba00df
```

It runs the suite through `sbtn`. Its options override the defaults of `midi-latency-config.json`:

| Option | Meaning |
| ------ | ------- |
| `--jar <path>` | The app's JAR to measure |
| `--commit <sha>` | The commit of a JAR whose `BuildInfo` doesn't record it |
| `--name <name>` | Names the run, to tell it apart from others of the same app and machine, e.g. `no-warm-up` |
| `--launches <n>` | How many times the app is launched for each case, in a fresh JVM each time; launches that disagree show that the JVM, not the app, moved the numbers |
| `--warmup <n>` | How many iterations of a case each launch runs before the timed ones, to let the JIT compiler work; they record nothing |
| `--iterations <n>` | How many timed iterations each launch runs; each records one sample of each metric |
| `--pace <ms>` | The time between the starts of two timed iterations, in milliseconds |
| `--max-rate <n>` | The most messages a second that each device carries in the warm-up, or `0` for no limit |
| `--results <dir>` | Writes the run file to another directory, e.g. for a trial run you won't commit |
| `--suite <id>` | Runs only the cases of a suite, `mts`, `mpb` or `mpe`; repeat it for several |
| `--case <text>` | Runs only the cases whose name contains the text, e.g. `"pass a note"`; repeat it for several |

An iteration is one pass through a case's steps; see [Write a case](#write-a-case). A run that selects some cases still
measures the floor, and its run file holds only those cases. Paths are relative to the current directory.
`bin/mtlist-perf-midi-latency --help` lists the options.

With the defaults, a run takes about 45 minutes, and the app's window opens for each launch. Keep the machine on AC
power, and don't use it meanwhile. At the end, the run prints a table of each metric's median, p99 and maximum, and the
path of the run file it wrote. A case that fails records its reason instead; the others keep their metrics. The app's
logs stay in the scratch directory the run prints.

Commit the run file and `perf/perf-midi-latency/results/index.json`. The run also writes the raw samples of each metric
beside its run file, in a `.samples.json` file that git ignores, and removes those of the machine's previous run.

To see when the JIT compiler kicks in, run without warm-up, e.g. with `--warmup 0 --name no-warm-up`, and look at the
samples of each timed iteration in the report.

## Open the report

```bash
bin/mtlist-perf-midi-latency serve    # --port <port>, 8000 by default
```

Browsers don't let a page opened from disk read files, so this serves `perf/perf-midi-latency/report/` with the JDK's
file server, and the results it reads beside it. Then open <http://localhost:8000/>. Pick a machine, a run, and a run to
compare it with ("vs", by default the previous run of the machine). A metric that drifted significantly is highlighted;
click a metric for its timeline across the machine's runs, its distribution, with the floor's median for reference, its
samples, the agreement of its launches and the run's details. Only the last run of each machine written on this computer keeps its raw samples. The report
only compares runs of one machine.

<http://localhost:8000/test.html> runs the tests of the report's statistics in the browser.

## Write a case

The cases are Scala code in the module's tests, in the `cases` package, written in the language of `PerfDsl`. Its model
of a case, with scenarios, steps, series and metrics, is in
[`case-model.md`](../../perf/perf-midi-latency/docs/case-model.md). Each tuner has a suite, such as
`MtsTunerLatencyTest`; `MidiLatencyPerfTest` runs them all in one run, and `LatencyMessages` holds what they share.

A case is a test that calls `perfCase`:

```scala
"change the tuning on a pedal" in perfCase(MtsTracks, TwoTunings, readyAfterPlaying(E4))(
  input("pedal")(pedal*) ~> output("tuning")(pythagoreanSysEx),
  input(pedalBack*) ~> output(justSysEx)
)(metric("pedal", "tuning"))
```

- Its first argument list gives the rig, as resource paths under `src/test/resources/`: the tracks template, whose
  `{{input.name}}`, `{{input.vendor}}`, `{{output.name}}` and `{{output.vendor}}` the suite fills with the configured
  devices, and the composition; then the `Readiness`, and optionally a custom app config.
- The second lists the steps of an iteration. `input(…) ~> output(…)` is a step that sends an input series and waits
  for an output series; `step(inputs*)(outputs*)` is one with several of each.
- The third lists the metrics. `metric("pedal", "tuning")` measures from the input series marked `pedal` to the output
  series marked `tuning`, and is named `pedal→tuning`.

`input("mark")(messages*)` and `output("mark")(expected*)` make a marked series, `input(messages*)` and
`output(expected*)` an unmarked one. An output series takes exact messages, or `matching(description) { case … => }`
for any message the pattern covers, such as a Note On on any channel. Each iteration must leave the app as it found it.

The test's name names the case in the run file and the report, so renaming a test starts a new timeline. A new suite of
cases is a `@DoNotDiscover` class that extends `LatencySuite`, with the id that `--suite` selects it by, and takes the
`LatencySession`; add it to those that `MidiLatencyPerfTest` runs.
