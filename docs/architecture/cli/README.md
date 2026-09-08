# `cli` module architecture

## Responsibility

The `cli` module is a **separate command-line executable** — a small utilities tool, distinct from the main
Microtonalist desktop application (`app`). It is not loaded by, nor part of, the GUI application; it is assembled and
run on its own (as the `microtonalist-cli` fat JAR).

Its purpose is developer/operator utilities that need the local MIDI subsystem without launching the full app. Currently
it offers a single subcommand that **lists the MIDI devices connected to the computer**, printing each input and output
device with its metadata (name, vendor, version, description) and its maximum transmitter/receiver count — useful for
discovering device names to reference in compositions and track configurations.

In the layered architecture it sits to the side of the main stack: it depends only on `sc-midi` (and the transitive
`businessync` that a `MidiManager` implementation requires). Nothing depends on `cli`.

Package: `org.calinburloiu.music.microtonalist.cli`.

## Key types

**`MicrotonalistToolApp`** is a plain Scala `object` with a `main` method — the executable's entry point and the
assembly `mainClass`. It does its own minimal argument dispatch (no CLI-parsing library): `main` pattern-matches the
first argument, routing `midi-devices` to `printMidiDevices` and anything else to a usage message. `main` is the
composition root: it constructs a `Businessync` and a `JavaMidiManager` (`sc-midi`'s Java Sound implementation, from
its `javamidi` package), hands the manager to `printMidiDevices` and closes it when done. `printMidiDevices` takes a
`MidiManager` and only prints: it iterates `inputDevicesInfo` and `outputDevicesInfo` and writes each
`MidiDeviceInfo`'s name, vendor, version and description plus its maximum transmitter (inputs) or receiver (outputs)
count, which a `MidiConnectionLimit` renders as `unlimited` or as the number — Java Sound's `-1` sentinel is mapped to
`MidiConnectionLimit.Unlimited` inside `javamidi` and never reaches the `cli`. Taking the manager as a parameter is
what makes the printing testable without MIDI hardware (`MicrotonalistToolAppTest` drives it over a stubbed
`MidiManager`). There is no command framework — the tool is intentionally a single flat object.

## Dependencies

`cli` declares exactly one application dependency, `sc-midi`, used for the `MidiManager` trait, its
`JavaMidiManager` implementation and the `MidiDeviceInfo` / `MidiConnectionLimit` value types;
`businessync` and Guava's `EventBus` are pulled in transitively (referenced directly only to build the
`Businessync` that `JavaMidiManager`'s constructor takes). The module imports nothing from
`javax.sound.midi` (#282). Nothing depends on `cli`; it is aggregated by `root` for building/testing but
packaged as its own fat JAR.

## Future / planned changes

- Coverage thresholds are currently 0 with `// TODO #181` to raise them toward the project's 80% target. Since #282
  the module does have a test — `printMidiDevices` is covered through a stubbed `MidiManager` — but `main` is not,
  because it still builds the real `JavaMidiManager`.
- The hand-rolled argument dispatch is structured to grow: new subcommands are added as additional `case` branches.
