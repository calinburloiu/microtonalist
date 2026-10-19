# Threading architecture: decisions on the open gaps

- **Date:** 2026-10-20
- **Based on commit:** `a8ba00df` (`doc/threading`)
- **Issue:** #383, implemented by #90 and #99

These decisions close three gaps left by [`requirements.md`](requirements.md) and
[`track-node-proposal.md`](track-node-proposal.md) before the end-to-end design. Where this document disagrees with
them, it takes precedence.

## 1. A threading layer between the domain and Pekko

**Decision.** Services talk to the Business Thread through our own layer, not through `ActorRef`s.

**Why.** The receiving end needs class-keyed dispatch and `Future` routing anyway, so sending to an `ActorRef`
directly would save little and would put Pekko types in `tuner`, `rig` and `ui`.

The layer owns the following, which is what makes it more than a mirror of Pekko:

- Subscriptions keyed by message class, where subscribing to a base class also catches its subclasses, delivered on
  the Business or the UI Thread (#90).
- Routing a `Future`'s result to the Business Thread.
- Linking each event to the command that caused it: while the layer handles command N, it stamps N on every event
  published. This is the hook for sequential consistency, which the PoC's Option A needs
  (`2026-09-09-sequential-consistency-options.md` in the PoC repo). Sequential consistency itself stays out of scope;
  only the room for it is designed now.
- Checks that code runs on the right thread, e.g. `requireBusinessThread`.
- A deterministic runtime for tests: a queue that the test drains by hand. Tests of sessions, services and view
  models then run without an `ActorSystem` and still see messages in production order.

Commands are typed messages, e.g. `ChangeTuning(change)`, not closures, so that a message type can later opt in to
sequencing with a marker trait. The API separates the two kinds of message:

- `send`, for a command: exactly one handler, optionally with a reply.
- `publish`, for an event: zero or more subscribers.

A command sent through subscriptions alone would be dropped silently when nothing subscribes to it.

**Pekko's role** is pinned threads, mailboxes, lifecycle and supervision. All the dispatch logic above is ours,
written against a small runtime interface: start a pinned mailbox with a given thread priority, and run something on
the UI Thread. The accepted consequence: replacing Pekko means reimplementing only that interface.

**Modules**, following the `sc-midi` / `sc-midi-javamidi` pattern:

- `businessync` holds the API and the logic above. It has no Pekko.
- `businessync-pekko` implements the runtime interface. Only `app` depends on it.
- `tuner`, `rig` and `ui` depend on `businessync` only, so a Pekko import there is a compile error.
- `ui` or `app` supplies the UI-thread hook (`Platform.runLater` and `Platform.isFxApplicationThread`, as
  `BusinessyncUiHandler` models it), so `businessync-pekko` doesn't depend on JavaFX.

The names are placeholders: the requirements allow replacing `Businessync`.

## 2. Tracks share the runtime, not the messaging API

**Decision.** Track threads use the layer's runtime interface (a pinned mailbox, at a higher priority), not its
subscription API.

**Why.** Tracks are many threads, created and removed as the rig changes; their messages go point to point; and the
note path must stay a direct call over an immutable `Seq`, with no per-message lock or map lookup (#310). A track
reaches Business only through services (`TuningChangeProcessor` calls `TuningService.changeTuning`), so Track→Business
already goes through the layer, including the case where both a pedal and the UI change the tuning. Track
communication needs no sequential consistency.

The track-node proposal stands, with these refinements:

1. `TrackMidiNode` is one concrete class in `rig`, built on the mailbox interface, not a trait with one implementation
   per runtime. Its forwarding logic, including the Business-side copy of the receivers, is then written and tested
   once, with the hand-drained queue.
2. A track's inlet is the only place where a MIDI message changes threads: `TrackMidiNode.receiver.send` only
   enqueues. Device input callbacks and upstream tracks call it, and a track sends to its output device directly, so
   `DeviceMidiNode` has no thread concerns.
3. Business wires the receiver but never sends through it. `send` must be safe to call from any thread.
4. Control messages (tune, reset, release input, receiver changes, close) share the mailbox with MIDI, so each one
   applies between two MIDI messages, never during one: the clean cut that #310 asks for.

## 3. A layered MIDI graph

**Decision.** Option (b): an outer graph of devices and tracks, and a subgraph per track for its pipeline. The MIDI
graph refactoring itself stays in #310.

**Why.** Each graph is then changed on one thread only: Business rewires the outer graph, and a track's thread rewires
its pipeline. The `MidiGraph` of #312 can be `@NotThreadSafe` and serve both levels, and only `TrackMidiNode` deals
with threads. In a flat graph, option (a), the graph lives on Business while most of its nodes run on track threads,
so the graph itself would have to route each edge change to the thread of the node it starts from.

The traversals that #310's fixes need are all at track level: #296 (a link wired twice), #298 (messages lost before a
link is wired), #316 (feeding tracks not released) and cycle detection. The outer graph's vertices are exactly what
`RigSpec` talks about. The fixes inside a track, #295 and #297, stay within one pipeline.

This reverses the "Flat graph" decision recorded in #310's description, which needs updating to match.

Prepare now for #310:

- Rename `MidiDeviceHandle` to `DeviceMidiNode`.
- Make `TrackPipeline` usable only from its track's thread, so that #312 can replace `MidiSerialProcessor` inside it
  without touching threads.

## Left to the end-to-end design

- The final names: the layer, its modules, and whether `RigManager` is renamed.
- The exact shape of the runtime interface, including how the UI side runs on the JavaFX thread and whether a UI
  actor remains.
- How `send` returns a reply, and how service methods that return a `Future`, such as `RigService.open`, map onto it.
- Whether an event published while a handler runs reaches the Business-side subscribers before or after that handler
  returns.
- The supervision policy when a tuner or tuning changer throws on a track thread.
