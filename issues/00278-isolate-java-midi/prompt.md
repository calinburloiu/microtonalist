Your task is to refactor `sc-midi` library/module to isolate the Java MIDI implementation from the Scala-idiomatic API from the module. By doing this, other implementations may be provided, such a MIDI 2.0 implementation or an Android-based implementation.

`MidiManager` needs to be a trait or abstract class and the current `MidiManager` will extend it and become the Java implementation named `JavaMidiManager`.

The only place where the Java MIDI classes will still appear will be `JavaMidiManager` and its satellite classes implementations. You should strip all `javax.sound.midi` package usages from the project and replace the with types from `org.calinburloiu.music.scmidi` library.

Since the Java MIDI types won't be used anymore outside `sc-midi`, maybe we can dump the `Sc` word from the type names.

`MultiTransmitter` which will be renamed to `MidiTransmitter` and must expose an immutable (read-only) interface, without modifiers, and become a pure interface, with no implementation and no synchronization. Note that the name drops `Multi`, but it's implicit, so it will still require multiple receivers. Its subtypes most provide:
- `ImmutableMidiTransmitter`: An *immutable* case class implementation which allows changing receivers by creating new instances of the same class.
- `MutableMidiTransmitter`: A *mutable* non-thread-safe implementation, similar with the current implementation, but without locks.
- `ConcurrentMidiTransmitter`: A *concurrent* (mutable, thread-safe) implementation, with the current implementation functionality.

The changes for `MidiTransmitter` will allow replacing `javax.sound.midi.Transmitter` and to better optimize the code by using concurrent implementation when really required. For example, in the future each track will have its own thread and all MIDI processing inside it will be performed by that single thread allowing to use a non-concurrent implementation.

A replacement for `javax.sound.midi.Receiver` already exists, `ScMidiReceiver`. Make sure the Java version is not used anymore outside `sc-midi`.

Part of this task, we should also write a separate document that sketches superficially what it would take to provide a MIDI 2.0 implementation in the future, just to be prepared with some concise document. It does not have to be deep, it will mostly be used for planning. It should also explain the relevant characteristics and differences of MIDI 2.0 to a newbie and how MIDI 1.0 and 2.0 are integrated.

Part of this task we could prepare the `ScMidiMessage` hierarchy with some top level trait that will contain the MIDI 2.0 hierarchy. I am thinking that we could have `ScMidiMessage` at the top, extended by `ScMidi1Message` and `ScMidi2Message`. No need to extend too much, a full hierarchy is out of scope.

We also need an issue for this. Create one, and write all docs in the issue directory create for this issue. Use /superpowers:brainstorming .
