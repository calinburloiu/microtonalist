# Performance Tests

The `perf-midi-latency` module measures the app's MIDI latency end to end: it launches the app from a JAR, sends MIDI
to the app's input device, timestamps what comes out of its output device, and records the statistics of each metric in
a run file under `perf/perf-midi-latency/results/`, which is committed. A page under `perf/perf-midi-latency/report/`
explores the runs. The design is in
[`perf/perf-midi-latency/docs/ARCHITECTURE.md`](../../perf/perf-midi-latency/docs/ARCHITECTURE.md).

Besides the cases through the app, the loopback cases measure the setup alone: they send the messages of the tuners'
flows on the app's input device, with no app running, and receive them back from it.

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
Then:

```bash
sbtn "perf-midi-latency/testOnly org.calinburloiu.music.microtonalist.perf.midilatency.cases.* -- -oNCXEHLOPQRMWS \
  -Djar=<path to the JAR> -Dcommit=a8ba00df"
```

| Parameter | Meaning |
| --------- | ------- |
| `jar` | The app's JAR to measure; without it, the cases are canceled |
| `commit` | The commit of a JAR whose `BuildInfo` doesn't record it |
| `launches`, `warmup`, `iterations` | Override the launches, and the warm-up and timed iterations of each launch |
| `pace` | Overrides the pace of the timed iterations, in milliseconds |
| `maxRate` | Overrides the most messages a second that each device carries in the warm-up |
| `results` | Writes the run file to another directory, e.g. for a trial run you won't commit |
| `name` | Names the run, to tell it apart from others of the same app and machine, e.g. `no-warm-up` |

Relative paths resolve against the repository's root. ScalaTest's `-z` selects the cases by name.

With the defaults, a run takes about 45 minutes, and the app's window opens for each launch. Keep the machine on AC
power, and don't use it meanwhile. At the end, the run prints a table of each metric's median, p99 and maximum, and the
path of the run file it wrote. A case that fails records its reason instead; the others keep their metrics. The app's
logs stay in the scratch directory the run prints.

Commit the run file and `perf/perf-midi-latency/results/index.json`. The run also writes the raw samples of each metric
beside its run file, in a `.samples.json` file that git ignores, and removes those of the machine's previous run.

To see when the JIT compiler kicks in, run without warm-up, e.g. with `-Dwarmup=0 -Dname=no-warm-up`, and look at the
samples of each timed iteration in the report.

## Open the report

Browsers don't let a page opened from disk read files, so serve the module's directory with the JDK's file server:

```bash
"$JAVA_HOME/bin/jwebserver" -d "$(pwd)/perf/perf-midi-latency"
```

Then open <http://localhost:8000/report/>. Pick a machine, a run, and a run to compare it with ("vs", by default the
previous run of the machine). A metric that drifted significantly is highlighted; click a metric for its timeline across
the machine's runs, its distribution, its samples, the agreement of its launches and the run's details. Only the last
run of each machine written on this computer keeps its raw samples. The report only compares runs of one machine.

<http://localhost:8000/report/test.html> runs the tests of the report's statistics in the browser.
