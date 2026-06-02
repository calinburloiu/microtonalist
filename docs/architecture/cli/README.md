# `cli` module architecture

## Responsibility

The `cli` module is a **separate command-line executable** — a small utilities tool, distinct from the main
Microtonalist desktop application (the `app` module). It is not loaded by, and is not part of, the GUI application; it is
assembled and run on its own.

Its purpose is to provide developer/operator utilities that need access to the local MIDI subsystem without launching
the full app. Currently it offers a single subcommand that **lists the MIDI devices connected to the computer**, printing
each input and output device along with its metadata (name, vendor, version, description) and its maximum
transmitter/receiver count. This is useful for discovering device names to reference in compositions and track
configurations.

In the layered architecture it sits to the side of the main stack: it depends only on `sc-midi` (the Scala-idiomatic MIDI
API) and the transitive `businessync` event bus that `MidiManager` requires. Nothing depends on `cli`. See
[Dependencies](#dependencies).

Package: `org.calinburloiu.music.microtonalist.cli`.

## Key types

### `MicrotonalistToolApp` (`cli/src/main/scala/org/calinburloiu/music/microtonalist/cli/MicrotonalistToolApp.scala:25`)

A plain Scala `object` with a `main` method — the executable's entry point and the assembly `mainClass`
(`build.sbt:105`). It does its own minimal argument dispatch (no CLI-parsing library):

- `main(args)` (`MicrotonalistToolApp.scala:27`) pattern-matches the first argument. `midi-devices` invokes
  `printMidiDevices()`; anything else prints a usage message listing the available subcommands.
- `printMidiDevices()` (`:38`) constructs a `Businessync` (over a Guava `EventBus`) and a
  `org.calinburloiu.music.scmidi.MidiManager`, then iterates `midiManager.inputDevicesInfo` and
  `midiManager.outputDevicesInfo`, opening each `MidiDevice` via `javax.sound.midi.MidiSystem` to print its metadata and
  handler counts. It always calls `midiManager.close()` when done. The nested helpers `printMidiDevicesByEndpoint`,
  `printTransmitters`, and `printReceivers` factor out the per-endpoint (input vs. output) printing.
- `fromHandlerCountToString(handlerCount)` (`:76`) renders the JVM's `-1` "unlimited" sentinel (used for
  max transmitters/receivers) as the string `"unlimited"`.

There are no subcommand classes, traits, or a command framework — the tool is intentionally a single flat object.

## Dependencies

Per `build.sbt:97`, the `cli` project declares exactly one application dependency:

```scala
lazy val cli = (project in file("cli"))
  .dependsOn(
    scMidi,
  )
```

- **`sc-midi` (`scMidi`)** — the only direct application dependency, used for `MidiManager` and the MIDI endpoint
  abstraction. `businessync` is pulled in transitively (and is referenced directly here only to satisfy `MidiManager`'s
  constructor).
- It also uses the JDK's `javax.sound.midi` API and Guava's `EventBus` (the latter via `Businessync`).
- **Nothing depends on `cli`.** It is aggregated by `root` for building/testing but is not on any other module's
  classpath; it is packaged as its own fat JAR (`microtonalist-cli`, `build.sbt:102`).

## Future / planned changes

- `build.sbt:106` carries `// TODO #181`: coverage thresholds are currently `0`/`0` and are to be raised toward the
  project's 80% statement/branch target, which will require adding tests for this module.
- The hand-rolled argument dispatch in `main` is structured to grow: new subcommands are added as additional `case`
  branches. The plural naming (`MicrotonalistToolApp`, "prints all available MIDI devices") signals the tool is intended
  to host more utilities over time.
