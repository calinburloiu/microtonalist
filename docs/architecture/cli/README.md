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
`businessync` that `MidiManager` requires). Nothing depends on `cli`.

Package: `org.calinburloiu.music.microtonalist.cli`.

## Key types

**`MicrotonalistToolApp`** is a plain Scala `object` with a `main` method — the executable's entry point and the
assembly `mainClass`. It does its own minimal argument dispatch (no CLI-parsing library): `main` pattern-matches the
first argument, routing `midi-devices` to `printMidiDevices()` and anything else to a usage message. `printMidiDevices`
constructs a `Businessync` and a `MidiManager`, iterates the input and output devices to print their metadata and
handler counts (rendering the JVM's `-1` "unlimited" sentinel as `"unlimited"`), and always closes the `MidiManager`
when done. There is no command framework — the tool is intentionally a single flat object.

## Dependencies

`cli` declares exactly one application dependency, `sc-midi`, used for `MidiManager` and the MIDI
endpoint abstraction; `businessync` is pulled in transitively (referenced directly only to satisfy
`MidiManager`'s constructor). It also uses the JDK's `javax.sound.midi` and Guava's `EventBus` (via
`Businessync`). Nothing depends on `cli`; it is aggregated by `root` for building/testing but
packaged as its own fat JAR.

## Future / planned changes

- Coverage thresholds are currently 0 with `// TODO #181` to raise them toward the project's 80% target, which will
  require adding tests for this module.
- The hand-rolled argument dispatch is structured to grow: new subcommands are added as additional `case` branches.
