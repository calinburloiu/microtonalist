> Refined by sections 2 and 3 of [`2026-10-20-gap-decisions.md`](2026-10-20-gap-decisions.md), which take precedence.

Today, we have `MidiDeviceHandle` that abstracts a device node in the MIDI graph. Rename it to the more explicit name `DeviceMidiNode`. This opens the gate to the MIDI Graph API improvement planned in issue #310 (the description there is outdated with the gaps closed in this architecture). But tracks that as input and output either devices or other tracks, forming a MIDI graph of tracks and devices. So we are proposing the introduction of a `TrackMidiNode` that abstracts a track node in the MIDI graph.

The track pipeline (today `pipeline: MidiSerialProcessor` in `RigManager`) can be abstracted in a `TrackPipeline` class.

A `TrackMidiNode` is responsible to:

- Manage the communication with its Track Actor.
- Manage its `TrackPipeline` by ensuring that it's exclusively updated on its Track Thread inside its Actor Thread .
- Expose its `MidiReceiver` to the Business Thread. This forwards messages to its Track Actor to sends them through the `TrackPipeline`.
- Expose its `MidiTransmitter` to the Business Thread. This does the following:\
    - Keeps internally the sequence of receivers. This is exposed to the Business Thread and only updated by it.
    - Forwards all mutations of the receivers sequence to its Track Actor. The latter learns about the receivers and updates its internal sequence of receivers (note that it's only updated by the Track Thread). Then, the pipeline will send the output to all those receivers.

The separation of concerns:

- The **domain model concern** is `RigSpec`, `TrackSpec` and the state is kept in `RigSession`. It is the lingua franca when talking about tracks and the rig.
- The rig MIDI graph may continue to be managed by `RigManager`, but we should consider renaming it. Its API speaks the domain model language, but internally is on the **rig MIDI graph concern** level which the connection between `DeviceMidiNode`s and `TrackMidiNode`s.
- Inside a `TrackPipeline` is the **track pipeline MIDI graph concern** (a MIDI subgraph inside a track). Its API still speaks the lingua franca of the domain model.
- `TrackMidiNode` is at the the boundary between the Business Actor and its Track Actor and thus lays at the **track threading concern**. Its API still speaks the lingua franca of the domain model, but inside it manages to communication between threads. It does not have the **track pipeline MIDI graph concern**, for that it proxies to `TrackPipeline` inside its Thread Actor. So replacing Apache Pekko with some other message passing threading model should just mean providing another `TrackMidiNote` implementation. This is a precedent for pushing alternative implementations on the rig MIDI graph, `DeviceMidiNode` implementations provide alternative MIDI backends (today we only have `JavaMidiDeviceHandle` with the Java Sound implementation).
