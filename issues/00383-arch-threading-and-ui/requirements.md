We are working on the architecture for proper threading for the application by using the actor model with Apache Pekko and to migrate the UI to JavaFX.

Why both threading and UI? We will have two fixed threads, one that manages UI and the other that manages business logic. We will also have a dedicated thread for each track in the rig. Proper UI thread management requires a proper architecture at that area, so we will use MVVM (Model, View, View Model) and JavaFX work great with this architectural pattern.

The work is mostly covered by issue #90, for the threading part, and by issue #99, for the UI part. Read the issues’ descriptions as they contain important information not covered here. Break the work into slices and create sub-issues under the issues mentioned for each slice. Each slice gets its own plan document and its own PR, but the whole work is covered by a single design doc.

A PoC which proves that Apache Pekko and JavaFX can integrate was created in a separate repo, https://github.com/calinburloiu/javafx-pekko-poc, cloned locally at `/Users/calinburloiu/Development/_poc/javafx-pekko-poc`. You may start looking in that repo by studying its `README.md` and its design doc from `docs/superpowers/specs/2026-09-06-javafx-pekko-midi-poc-design.md`. Other docs might also be useful. It's worth spawning a sub-agent to investigate useful things from the that repo.

**Gap decisions.** [`2026-10-20-gap-decisions.md`](2026-10-20-gap-decisions.md) closes the open gaps of these
requirements and of [`track-node-proposal.md`](track-node-proposal.md): the threading abstraction layer and its
modules, the track threads, and the layered MIDI graph. It takes precedence over both documents.

# Threading

The actors that manage UI and business logic must be fixed (pinned). The former must be exactly JavaFX Application Thread. Each track must have a fixed (pinned) thread and must have a higher priority, because latency is important when playing music and the UI and business have a slightly smaller priority and may wait a few more milliseconds for some notes to play. Note that the current microtonalist code does have an unused `executorService` in `RigManager` that configures that priority, you may use that for inspiration.

The PoC successfully covered the configuration of those threads and how they are pinned.

# UI Actor and Business Actor Communication

The current UI should be migrated to JavaFX from Swing and should adopt the MVVM (Model, View, View Model) design pattern.

View Model bindings to View controls are always updated by the UI Thread (JavaFX Application Thread). The communication of UI with the Business Actor happens from View Models: when UI events are triggered messages are being sent to the Business Actor; messages from the Business Actor to the UI Actor and handled in View Models.

Sequential consistency is planned that out of scope for this work.

# Handling Futures

microtonalist uses `Future`s in several places, for example in repositories like `CompositionRepo`. Usually their result, such as the case of the repositories, need to arrive on the Business Thread. 

Pekko dispatchers implement `ExecutionContext` directly. Use `pipeTo` (classic) or `context.pipeToSelf` (Typed) to route a `Future`'s result back into an actor's mailbox rather than mutating actor state from a callback thread.

# The Rig and Track Threads

There needs to be a separation of concerns:

- **Rig domain model:** `RigSpec`, `TrackSpec`. Kept by the Business Actor in `RigSession`.
- **Rig MIDI graph:**
    - **Rig:** the “macro” / coarse MIDI graph that connects devices and tracks. Today it is managed in `RigManager`. That might change if necessary, but what should remain is that this part of the graph shall be updated by the Business Actor.
    - **Track pipeline:** the “micro” / fine MIDI graph inside a track that manages the tuning pipeline, Today this is `pipeline: MidiSerialProcessor` in `Track`. We should consider put this logic in its own track pipeline class and its code shall always run on its Track Actor, with its own pinned thread.
- **Track threading:** passing messages to/from track threads/actors.

A Track Actor manages on its own thread the track pipeline. Messages received by the Track Actor:

- MIDI messages from the track input (a device or another track) that should pass through to pipeline and eventually be send to the track output (a device or another track).
- Control messages such as those that update the track pipeline or inform it about its receivers.

Note that a track `MidiReceiver` and `MidiTransmitter` lay at the boundary between the Business Actor and the Track Actor. The former needs to forward the MIDI messages to the Track Actor, and the latter need to announce  the Track Actor about its `MidiReceivers` through messages. The track transmitter may also keep the receivers sequence internally for the Business Thread.

# Abstracting Thread Communication

The current `Businessync` interface may be thrown away and be replaced with something else.

Using services to expose API for rigs, tuning and other business concerns is a good idea. But instead of using `Businessync` as today, they should wrap the publishing of messages to the Business Actor. But on the arrival end, there needs to be some message subscription mechanism to dispatch messages internally in the Business Thread as detailed in issue #90.

We should completely eliminate `EventBus` from guava.