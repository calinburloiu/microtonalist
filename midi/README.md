# `midi` module group

A Scala-idiomatic MIDI API, which confines Java Sound to the `sc-midi-javamidi` module.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`sc-midi`](sc-midi/docs/ARCHITECTURE.md) | Immutable MIDI messages, composable receivers, transmitters and processors, and MIDI devices | `org.calinburloiu.music.scmidi` |
| [`sc-midi-javamidi`](sc-midi-javamidi/docs/ARCHITECTURE.md) | The Java Sound implementation of `sc-midi`'s MIDI devices, with CoreMIDI4J on macOS | `org.calinburloiu.music.scmidi.javamidi` |
