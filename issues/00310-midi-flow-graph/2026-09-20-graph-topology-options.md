# MIDI Flow Graph: Three Options for Owning the Topology (Options Study)

- **Date**: 2026-09-20
- **Commit**: `92caf54`, the top of `main` at the time of writing. Every line reference below is against that commit.
- **Issue**: [#310](https://github.com/calinburloiu/microtonalist/issues/310) — Restructure MIDI plumbing into a
  traversable flow graph
- **Revised**: 2026-09-20, same commit, after review. One constraint changed and four decisions were taken;
  **[Section 0](#0-what-changed-after-review) records them** and every section they affect is marked
  *(revised — see §0)*. The three options and the comparison between them are unchanged: the study still stands as
  the record of why S1 was chosen. The parts that changed are how S1 is *built*, not which option wins.
- **Status**: Options study. It presents three candidate architectures in enough detail to choose between them; it
  does **not** name every type finally or settle the open questions in Section 9. The design document for #310
  references this study instead of repeating it.
- **Scope**: the `sc-midi` MIDI plumbing (`MidiReceiver`, the `MidiTransmitter` family, `MidiSplitter`,
  `MidiProcessor`, `MidiSerialProcessor`) and the `tuner` track wiring built on it (`Track`, `TrackManager`).

All Scala in this document is an **illustrative sketch**. It is written to the repository's conventions (brace
syntax, `enum`, no `new`, ScalaDoc on public identifiers) so that it reads like the codebase, but it has not been
compiled and elides error handling, logging and most documentation.

---

## Table of contents

0. [What changed after review](#0-what-changed-after-review)
1. [Why this document exists](#1-why-this-document-exists)
2. [What the code does today](#2-what-the-code-does-today)
3. [The vocabulary all three options share](#3-the-vocabulary-all-three-options-share)
4. [Option S1 — central graph, compiled dispatch](#4-option-s1--central-graph-compiled-dispatch)
5. [Option S2 — distributed topology, walkable nodes](#5-option-s2--distributed-topology-walkable-nodes)
6. [Option S3 — central graph, resolved per message](#6-option-s3--central-graph-resolved-per-message)
7. [Comparison](#7-comparison)
8. [Recommendation](#8-recommendation)
9. [Open questions the design must still settle](#9-open-questions-the-design-must-still-settle)

---

## 0. What changed after review

The study was written assuming #310 would come **before** the per-track threads of
[#121](https://github.com/calinburloiu/microtonalist/issues/121), and therefore that it would have to carry the
concurrency. That assumption was reversed on review. Everything below is the delta; the rest of the document stands.

### C1 — #121 comes first, and it takes the concurrency with it

**#310 is now scheduled after #121.** Each track already runs on its own thread by the time this work starts, so
**a port is touched only by the thread driving its node**. There are no locks, no volatiles and no atomics anywhere
in this design — not on the message path, and not on the wiring path.

The wiring path is worth spelling out, because it is the part that still crosses threads. A topology change is
decided on the business thread, but the ports it changes belong to track threads. Rather than reintroduce
synchronisation, **a commit dispatches each track's portion of the change as a command on that track's own queue**,
alongside its MIDI messages. The track thread applies it between messages. Three consequences:

- No synchronisation is needed on either path, which is what makes C1 possible at all.
- A track sees a clean cut — every message before the rewire, then every message after. That is a stronger ordering
  guarantee than today's, where a rewiring can land in the middle of a fan-out.
- A commit becomes **globally validated but locally applied**: different tracks adopt the change at slightly
  different instants. This does *not* weaken any of the bug fixes. #296 is fixed at validation, which is still
  central and still atomic. #297 and #298 are fixed because a track's thread only begins consuming its input after
  it has processed its own build-and-wire command — arguably more robustly than a globally atomic commit would.

**Sections affected:** §3.4 (affinity is now a given, not something #310 introduces), §4.3 (the compiled fan-out
loses its `volatile`; see the revised snippet there), §4.8 (the cache-coherence risk largely evaporates), §7.3 (the
read-lock removal is #121's win, not S1's).

**This makes the chosen option materially simpler than Section 4 describes.** With per-thread ownership there is no
shared structure to consult and no cross-thread cache to keep coherent: an outlet just holds its targets in a plain
field. The S1-versus-S3 distinction, which was about how a shared fan-out is published, largely collapses — S3's
objection in §6.2 was precisely that it puts a structure shared by every thread on the hot path, and under C1 no
such structure exists in S1 at all.

### C2 — decisions taken

| | Question | Decision |
| - | -------- | -------- |
| **D1** | Port naming (§9, Q1) | **`inlet` / `outlet`.** `input` / `output` stay reserved for a *device's* direction |
| **D2** | Flat graph or composite nodes (§9, Q3) | **Flat.** Vertices are individual nodes; `Track` is a facade over nodes sharing a `trackId`, with `TrackInputNode` / `TrackOutputNode` as stable endpoints |
| **D3** | Does `process` run with nothing downstream (§9, Q4) | **Yes** — the `nonEmpty` guard goes, so state-tracking nodes observe the stream regardless |
| **D4** | Option (§8) | **S1** |

### C3 — the sub-issue split

Section 8's five-step split is superseded by three sub-issues of #310, all scheduled after #121:

1. [#311](https://github.com/calinburloiu/microtonalist/issues/311) — Introduce MIDI graph ports and node roles (§3)
2. [#312](https://github.com/calinburloiu/microtonalist/issues/312) — Add `MidiGraph` with transactions, snapshots
   and traversal (§4.1–4.4)
3. [#313](https://github.com/calinburloiu/microtonalist/issues/313) — Move track wiring onto the MIDI graph
   (§4.6); closes #295, #296, #297 and #298

### C4 — questions still open

Q1, Q3 and Q4 of Section 9 are answered by D1–D3. Q2 and Q5–Q9 stand, and #312 carries Q6, Q7 and Q8.

---

## 1. Why this document exists

A `Track` is a linear pipeline, but tracks feed one another through `FromTrackInputSpec` and `ToTrackOutputSpec`, so
zoomed out the runtime structure is a **directed graph**. #310 asks for two things from that graph:

1. **Clearly defined node types and their roles.**
2. **Graph traversal (DFS/BFS) with the ability to mutate the graph while traversing it.**

Both run into the same wall, and it is worth stating it plainly before comparing options:

> **The graph cannot be walked today, at any node.** An edge is a bare `MidiReceiver` inside a `Seq`. A
> `MidiReceiver` is a one-method sink with no back-reference to whatever owns it, and the static type
> `Seq[MidiReceiver]` would erase such a reference even if the instances had one.

`MidiProcessor.MidiProcessorReceiver` is an inner class of the trait, so it *could* expose `MidiProcessor.this`. But
`JavaMidiDeviceHandle.HandleReceiver` and `MidiSplitter` have no node to point at in the first place — a splitter
belongs to no vertex; it *is* a piece of wire. So goal 2 is not merely awkward to implement on the current
primitives. It is unimplementable on them without changing what an edge points at.

That single fact — **what an edge points at** — is what the three options below disagree about, along with **who
holds the edges**.

### The question this study answers

Given that edges must become walkable, **who owns the topology?**

- **S1 — a central `MidiGraph`, compiled.** The graph owns the edge set; mutation is a validated, atomic commit; the
  commit compiles a resolved fan-out into each port so that sending stays a direct call.
- **S2 — distributed, nodes made walkable.** Each node keeps owning its own downstream list, as today; ports gain
  `owner` back-references so a walk becomes possible.
- **S3 — a central `MidiGraph`, uncompiled.** The graph owns the adjacency and every send consults it.

Sections 4–6 develop each one against the same nine use cases, with the same API skeleton, so they can be compared
line for line.

### What this study deliberately does not do

It does not evaluate the *port* redesign (Section 3) as an option — all three options assume it, because all three
need it, and it is the part of #310 that is not really in doubt. Section 3.5 does note precisely how much the
renaming fixes on its own, which is less than it first appears.

---

## 2. What the code does today

### 2.1 The primitives

| Type | Shape | Role in the graph |
| ---- | ----- | ----------------- |
| `MidiReceiver` | `def send(message: MidiMsg, timeStamp: Long): Unit` | An inlet, but anonymous — no owner, no name |
| `MidiTransmitter` | `def receivers: Seq[MidiReceiver]` | An outlet, read-only view |
| `ImmutableMidiTransmitter` | `case class`, `withReceiver`/`withoutReceiver`… | Outlet as a value |
| `MutableMidiTransmitter` | `@NotThreadSafe`, `final` modifiers over `setReceivers`/`withChangeGuard` hooks | Outlet, single-threaded |
| `ConcurrentMidiTransmitter` | `@ThreadSafe`, the two hooks under a `ReentrantReadWriteLock` | Outlet, any thread |
| `MidiSplitter` | `extends MidiReceiver`, composes a `MidiTransmitter` | Pure wire: one inlet fanned to many |
| `MidiProcessor` | composes a `MidiProcessorReceiver` **and** a `MidiProcessorTransmitter` | The only real vertex type |
| `MidiSerialProcessor` | `extends MidiProcessor`, holds `Seq[MidiProcessor]`, self-wires by index | A path, disguised as a vertex |
| `MidiDeviceHandle` | exposes both a `receiver` and a `transmitter` | Source *or* sink, depending on how it was opened |

Two of these deserve a closer look, because the options treat them very differently.

**`MidiProcessor` composes both ports.** A subclass implements `process`; the inbound port is
`MidiProcessorReceiver` and the outbound port is `MidiProcessorTransmitter`, a `ConcurrentMidiTransmitter` that runs
an attach/detach protocol in `setReceivers`
(`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala:77`). The hot path is:

```scala
// sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiProcessor.scala:59
class MidiProcessorReceiver private[scmidi] extends MidiReceiver {
  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    val outputReceivers = transmitter.receivers
    if (outputReceivers.nonEmpty) {
      for (outputMessage <- process(message, timeStamp); outputReceiver <- outputReceivers) {
        outputReceiver.send(outputMessage, timeStamp)
      }
    }
  }
}
```

Note the `transmitter.receivers` read. For a `ConcurrentMidiTransmitter` that is
`ConcurrentMidiTransmitter.scala:49`, which takes the **read lock of a `ReentrantReadWriteLock`**. So today
*every MIDI message acquires one read lock per processor stage it passes through* — three lock acquisitions for a
Note On crossing a `TuningChangeProcessor`, a `TunerProcessor` and the enclosing `MidiSerialProcessor`. This matters
in Section 7.3.

Note also the `if (outputReceivers.nonEmpty)` guard: a processor with nothing downstream drops the message **without
calling `process`**, so its tuner and its `MidiChannelStateTracker` never observe it. That guard is the direct cause
of [#297](https://github.com/calinburloiu/microtonalist/issues/297) and
[#298](https://github.com/calinburloiu/microtonalist/issues/298).

**`MidiSerialProcessor` is a path pretending to be a vertex.** It holds a `Seq[MidiProcessor]` and wires neighbours
by assigning `processors(i - 1).transmitter.receivers = Seq(processors(i).receiver)`
(`MidiSerialProcessor.scala:225`), mirroring its own output receivers onto the chain's tail through
`wireOutput` (`:236`) driven by the `onReceiversChanged` hook (`:184`). Its own `process` (`:166`) short-circuits:
it pushes the message into the head processor and returns `Seq.empty`, because the chain already carries the message
to the output.

The index arithmetic in that wiring is where
[#295](https://github.com/calinburloiu/microtonalist/issues/295) comes from: `removeAt` (`:133`) calls
`wireProcessorToPrevious(index)` unconditionally, and that method `require`s `index >= 1`, so removing the chain
head throws — after `_processors` has already been reassigned.

### 2.2 The nine use cases

Every option in Sections 4–6 is walked against this list. UC1–UC7 exist today; UC8 and UC9 are what #310 is for.

| # | Use case | Where it lives today |
| - | -------- | -------------------- |
| **UC1** | Build a device-to-device track | `Track.scala:61–69` |
| **UC2** | Build a pair of chained tracks (A feeds B) | `Track` + `TrackManager.scala:63–80` |
| **UC3** | Apply a tuning to every track | `TrackManager.tune` → `Track.tune` → `TunerProcessor.tune` |
| **UC4** | An output device (re)opens → reset the tuner | `TrackManager.onMidiEvent` → `Track.resetTuner` |
| **UC5** | An input device disconnects → release the input | `Track.releaseInput` |
| **UC6** | Close a track | `Track.close` |
| **UC7** | Replace all tracks | `TrackManager.replaceAllTracks:51` |
| **UC8** | Put each track on its own thread | *not implemented* ([#121](https://github.com/calinburloiu/microtonalist/issues/121)) |
| **UC9** | Walk the graph, mutating it as you go | *not possible* (#310, goal 2) |

Two of them carry most of the discriminating power, so here they are in full as they stand.

#### UC1 today — a device-to-device track

```scala
// tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala:61
private val pipeline: MidiSerialProcessor = MidiSerialProcessor(
  Seq(tuningChangeProcessor, tunerProcessor).flatten, outputDeviceHandle.map(_.receiver).toSeq)

inputDeviceHandle.foreach(_.transmitter.addReceiver(receiver))

sendInitMidiMessages()
```

The output device receiver goes in as an *initial* receiver, so the tuner's `reset()` messages reach the device as
soon as the track is built. The input subscription happens in the constructor.

#### UC2 today — a chained pair

```scala
// tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala:63
// Wire inter-track connections
for (currTrack <- tracks) {
  currTrack.spec.input match {
    case Some(FromTrackInputSpec(trackId, _)) =>
      val fromTrack = tracksById(trackId)
      fromTrack.transmitter.addReceiver(currTrack.receiver)
    case _ =>
  }

  currTrack.spec.output match {
    case Some(ToTrackOutputSpec(trackId, _)) =>
      val toTrack = tracksById(trackId)
      currTrack.transmitter.addReceiver(toTrack.receiver)
    case _ =>
  }
}
```

Three defects live in the gap between UC1 and UC2:

- Track B, whose output is `ToTrackOutputSpec`, is constructed with **no** output receivers, yet UC1 already
  subscribed it to its input device. Everything arriving before this loop runs is dropped unprocessed — **#298**.
- The same emptiness swallows `initMidiMessages` — **#297**.
- A spec pair that declares one link from both ends (`A.output = ToTrack(B)` *and* `B.input = FromTrack(A)`) runs
  both branches, and `addReceiver` appends unconditionally, so `B.receiver` sits in `A`'s list twice and B processes
  everything twice — **#296**.

All three are *wiring* defects, not logic defects. That is the observation the options are graded on.

### 2.3 The three structural faults

**F1 — edges point at anonymous sinks.** Covered above. Makes UC9 impossible.

**F2 — the vocabulary names the far end of the wire, not the node's own ports.** `MidiTransmitter.receivers` means
*other nodes' inlets*. `MidiProcessor.receiver` means *my inlet*. One word, two meanings, and they are neighbours in
the same class. This is why mixing inheritance and composition reads badly: a node that both `extends MidiTransmitter`
and composes an inbound receiver would expose `receiver` (inbound) beside `receivers` (outbound), which is backwards
for a reader. The clash is already latent in the names; inheritance merely surfaces it.

**F3 — wiring is distributed and non-atomic.** Each node owns its downstream list, so nothing can validate the whole
prospective topology, and nothing can change several edges as one unit. #296, #297 and #298 are all instances of
this. #295 is a variant of it inside `MidiSerialProcessor`.

### 2.4 A note on inheritance versus composition

#310 asks whether a node should *be* a receiver and a transmitter rather than *have* them. This study assumes
composition in all three options, for three reasons, in order of weight:

1. **`extends` caps port arity at one, permanently.** A node that *is* a `MidiReceiver` has exactly one inlet,
   forever. `TrackChannelIOSupport`'s per-channel filtering, MPE's Master/Member channel split and any future merge
   node all want more than one. Composition of *named* ports scales to N; inheritance does not.
2. **Mutation policy is a port concern, not a node concern.** `MidiTransmitter`'s API and implementation vary across
   `ImmutableMidiTransmitter` / `MutableMidiTransmitter` / `ConcurrentMidiTransmitter`. A node that extends one
   commits its own type to a policy, forcing parallel branches of the whole processor hierarchy. A node that
   composes one swaps the implementation without changing its own type. (This is the objection raised in #310, and
   it is correct.)
3. **`send` is caller-facing; `process` is author-facing.** If a node is-a receiver, `node.send(…)` is public on
   every node and anything can inject into the middle of the graph, past the port. Today's separate port object is a
   feature.

What changes in all three options is not composition, but **what the composed things are called and what they point
at**.

---

## 3. The vocabulary all three options share

### 3.1 Nodes and ports

```scala
/** Identifier of a node, unique within a graph. */
opaque type MidiNodeId = String

/**
 * A vertex of the MIDI flow graph: something that consumes messages, produces them, or both.
 *
 * A node owns its ports and nothing else. It does not know which other nodes it is wired to; that is the
 * topology's business, and the three options differ in who holds it.
 */
trait MidiNode {
  /** Identifies this node within its graph, for diagnostics, traversal and wiring. */
  def id: MidiNodeId

  /** The inbound ports, in declaration order; empty for a source. */
  def inlets: Seq[MidiInlet]

  /** The outbound ports, in declaration order; empty for a sink. */
  def outlets: Seq[MidiOutlet]
}

/**
 * An inbound port: the place messages enter a node.
 *
 * It is a [[MidiReceiver]], so an upstream port can send to it without knowing anything else, and it carries the
 * back-reference that makes an edge walkable.
 */
trait MidiInlet extends MidiReceiver {
  /** The node this port belongs to. */
  def owner: MidiNode

  /** Distinguishes this port from the node's other inlets; unique within the node. */
  def name: String
}

/**
 * An outbound port: the place messages leave a node.
 *
 * A node's `process` result goes out through one of these. A node with several outlets chooses which, so routing
 * decisions (per channel, per MPE zone) become a property of the node rather than of a downstream filter.
 */
trait MidiOutlet {
  def owner: MidiNode

  def name: String

  /** Forwards a message to everything wired downstream of this port. */
  def send(message: MidiMsg, timeStamp: Long): Unit
}
```

The name clash of F2 disappears: a node has an `inlet` (mine, inbound) and `outlets` (mine, outbound). Downstream
nodes are reached through the topology, never through a member called `receivers`. The word *receiver* survives only
on `MidiReceiver`, the low-level "something you can send to" abstraction that `MidiInlet` refines.

Two single-port conveniences keep the common case terse, since almost every node today has exactly one of each:

```scala
trait MidiNode {
  // …

  /** The sole inlet, for the common single-inlet node. */
  final def inlet: MidiInlet = inlets match {
    case Seq(only) => only
    case _ => throw IllegalStateException(s"Node $id does not have exactly one inlet!")
  }

  /** The sole outlet, for the common single-outlet node. */
  final def outlet: MidiOutlet = outlets match {
    case Seq(only) => only
    case _ => throw IllegalStateException(s"Node $id does not have exactly one outlet!")
  }
}
```

### 3.2 Node roles

Roles are expressed as traits rather than as an `enum`, because they differ in *members*, not merely in identity:

```scala
/** A node that only produces messages: a MIDI input device, a sequencer, a test generator. */
trait MidiSourceNode extends MidiNode {
  final override def inlets: Seq[MidiInlet] = Seq.empty
}

/** A node that only consumes messages: a MIDI output device, a recorder, a monitor. */
trait MidiSinkNode extends MidiNode {
  final override def outlets: Seq[MidiOutlet] = Seq.empty
}

/**
 * A node that transforms the stream: it consumes on its inlets, and what it emits leaves through its outlets.
 *
 * This is today's [[MidiProcessor]] with its ports renamed. Subclasses implement [[process]].
 */
trait MidiProcessorNode extends MidiNode {
  protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg]
}
```

`MidiDeviceHandle` is deliberately **not** made a node. It stays a lifecycle-and-I/O type, and node-ness is an
adapter over it, so that the Java Sound implementation stays independent of the graph:

```scala
/** Presents the messages a MIDI input device sends as a source node. */
class MidiDeviceSourceNode(val handle: MidiDeviceHandle) extends MidiSourceNode { /* … */ }

/** Presents a MIDI output device as a sink node. */
class MidiDeviceSinkNode(val handle: MidiDeviceHandle) extends MidiSinkNode { /* … */ }
```

This also settles a small confusion in the current vocabulary: a device opened with `MidiDirection.Input` is a
graph **source**, and one opened with `MidiDirection.Output` is a graph **sink**. The words *input* and *output* are
already taken, from the device's point of view, which is the reason this study uses **inlet** and **outlet** for the
graph's point of view. (See Section 9, Q1.)

### 3.3 Track boundary nodes

Every track gets two stable endpoint nodes, whatever its contents:

```scala
/** The stable upstream endpoint of a track: everything feeding the track attaches here. */
class TrackInputNode(val trackId: TrackSpec.Id) extends MidiProcessorNode

/** The stable downstream endpoint of a track: everything the track feeds attaches here. */
class TrackOutputNode(val trackId: TrackSpec.Id) extends MidiProcessorNode
```

They earn their place four times over:

- A track with neither a tuner nor tuning changers still has endpoints (today `MidiSerialProcessor` passes through
  an empty chain; with a flat graph there would otherwise be no node at all).
- `Track.releaseInput` needs to send All Notes Off **straight to the track's output, bypassing the tuner** — that is
  `trackOutputNode.outlet.send(…)`.
- Inter-track edges attach to endpoints that do not move, so swapping a track's tuner at runtime does not disturb
  its links — which is exactly what the `Track Management` milestone is about.
- Under UC8, the per-track queue has an obvious home: `TrackInputNode`.

With them, a `Track` need not be a node at all. It becomes a **facade over a group of nodes sharing a `trackId`**,
and the graph stays flat. That avoids composite/subgraph nodes entirely, and Section 9, Q3 records the alternative.

### 3.4 Thread affinity *(revised — see §0)*

> **Revision (C1).** #121 now lands first, so affinity is a fact of the system before #310 starts rather than
> something #310 introduces. The type below and the "hand-off is a property of an edge" rule both stand; what
> changes is that #310 *reads* affinities to compile edges, instead of establishing the threading model itself.

```scala
/**
 * The execution context a node's processing belongs to. Nodes sharing an affinity are driven by one thread, so
 * messages between them need no hand-off; an edge crossing two affinities does.
 */
final case class MidiAffinity(name: String)

trait MidiNode {
  // …

  /** The affinity this node is driven on. Defaults to the caller's thread, i.e. no hand-off anywhere. */
  def affinity: MidiAffinity = MidiAffinity.Caller
}
```

Under UC8 a track's nodes all share `MidiAffinity(s"track-$trackId")`. The crucial design point, common to all three
options but realisable to very different degrees, is:

> **A cross-thread hand-off should be a property of an edge, derived from its two endpoints' affinities — never a
> node type that `TrackSpec` has to name.**

Otherwise the threading decision leaks into `TrackSpec`, into the `.tracks` JSON format, and into `TrackManager`'s
wiring loop, and `ToTrackOutputSpec` sprouts a transport variant it has no business carrying.

### 3.5 What this renaming fixes on its own — and what it does not

Sections 3.1–3.4 fix **F1** (edges become walkable) and **F2** (the name clash). Applied alone, on top of today's
distributed wiring, they make UC9 possible and the code readable.

They fix **none of F3**. #296, #297 and #298 are all "several edges had to change as one unit and did not", and no
amount of renaming makes a distributed `addReceiver` atomic. That is what Sections 4–6 are actually deciding.

---

## 4. Option S1 — central graph, compiled dispatch

> A `MidiGraph` owns the edge set. Nodes hold no wiring. Every change goes through a transaction that **validates**
> the whole prospective topology, **applies** it, and **notifies** the affected nodes. Applying it compiles a
> resolved, immutable fan-out into each outlet, so sending stays a direct call.

### 4.1 API

```scala
/** A directed edge: everything leaving `from` arrives at `to`. */
final case class MidiEdge(from: MidiOutlet, to: MidiInlet)

/**
 * The MIDI flow graph: the single owner of which nodes exist and how they are wired.
 *
 * Nodes and edges are changed only inside a [[transaction]], which validates the whole prospective topology before
 * any of it goes live. A failed validation leaves the running graph untouched.
 */
class MidiGraph {
  /** An immutable view of the current topology, safe to walk while the graph is being changed. */
  def snapshot(): MidiGraphSnapshot

  /**
   * Applies a batch of changes as one unit.
   *
   * The body accumulates changes; nothing is live until it returns. Then, in order:
   *   1. '''validate''' — the prospective topology is checked as a whole; a failure throws and changes nothing;
   *   1. '''apply''' — the node and edge sets are replaced and every affected outlet's fan-out recompiled;
   *   1. '''notify''' — the lifecycle callbacks of Section 4.5 fire, outside any lock.
   *
   * @param body accumulates the changes; it may read `tx.snapshot`, which is stable for its whole duration.
   * @return what changed, for the caller to log or assert on.
   */
  def transaction[R](body: MidiTransaction => R): R
}

/** Accumulates the changes of one [[MidiGraph.transaction]]. */
trait MidiTransaction {
  /** The topology as it was when the transaction opened; does not reflect this transaction's own changes. */
  def snapshot: MidiGraphSnapshot

  def addNode[N <: MidiNode](node: N): N

  def removeNode(node: MidiNode): Unit

  /** Wires `from` to `to`. Wiring the same pair twice is a no-op: edges form a set. */
  def connect(from: MidiOutlet, to: MidiInlet): Unit

  def disconnect(from: MidiOutlet, to: MidiInlet): Unit

  /** Removes every edge touching `node`, leaving the node in the graph. */
  def isolate(node: MidiNode): Unit
}
```

`connect` being set-valued is the whole of **#296**: both spec branches of a reciprocal pair name the same
`MidiEdge(A.outlet, B.inlet)`, and a set holds it once.

### 4.2 The snapshot

```scala
/**
 * An immutable view of a topology: the value traversal walks.
 *
 * It is decoupled from the live graph, so a walk is never disturbed by a concurrent commit, and a walk that drives
 * a commit sees a stable structure from first node to last.
 */
final class MidiGraphSnapshot(val nodes: Set[MidiNode], val edges: Set[MidiEdge]) {

  private val outgoing: Map[MidiNode, Seq[MidiEdge]] = edges.groupBy(_.from.owner).view.mapValues(_.toSeq).toMap
  private val incoming: Map[MidiNode, Seq[MidiEdge]] = edges.groupBy(_.to.owner).view.mapValues(_.toSeq).toMap

  /** The nodes fed by `node`, in no particular order. */
  def successorsOf(node: MidiNode): Seq[MidiNode] = outgoing.getOrElse(node, Seq.empty).map(_.to.owner)

  /** The nodes feeding `node`, in no particular order. */
  def predecessorsOf(node: MidiNode): Seq[MidiNode] = incoming.getOrElse(node, Seq.empty).map(_.from.owner)

  /** The nodes reachable downstream of `node`, depth first, each visited once, `node` included. */
  def depthFirstFrom(node: MidiNode): LazyList[MidiNode] = // …

  /** The nodes reachable downstream of `node`, breadth first, each visited once, `node` included. */
  def breadthFirstFrom(node: MidiNode): LazyList[MidiNode] = // …

  /** The nodes in dependency order, or the cycle found instead. */
  def topologicalOrder: Either[Seq[MidiNode], Seq[MidiNode]] = // …
}
```

Both walks are ordinary textbook traversals over `successorsOf`, because the snapshot is a real adjacency structure.
That is the point: **goal 2 stops being an architectural problem and becomes twenty lines of graph code.**

### 4.3 Compiled dispatch and the hot path *(revised — see §0)*

> **Revision (C1).** This section was written assuming #310 landed before #121, so the compiled fan-out had to be
> published across threads and the outlet below holds it in a `@volatile`. With #121 first, an outlet is read only
> by the thread driving its node and written only by that same thread, when it applies a wiring command from its own
> queue. **The `@volatile` goes; `targets` is a plain field.** The revised snippet follows the original one below.
> Everything else in this section stands: the fan-out is still compiled once, per edge, from the two nodes'
> affinities, and the hot path is still a walk over an array the node owns.

Sending must not consult the graph — that is what separates S1 from S3. Instead the commit *compiles* the topology
into each outlet:

```scala
/** How one edge is realised at runtime, decided when the topology is compiled. */
sealed trait MidiTarget {
  def send(message: MidiMsg, timeStamp: Long): Unit
}

/** Same affinity on both ends: the downstream inlet is called directly, on the sender's thread. */
final class MidiDirectTarget(inlet: MidiInlet) extends MidiTarget {
  override def send(message: MidiMsg, timeStamp: Long): Unit = inlet.send(message, timeStamp)
}

/** Different affinities: the message is queued for the thread that drives the downstream node. */
final class MidiHandOffTarget(queue: BlockingQueue[MidiDelivery], inlet: MidiInlet) extends MidiTarget {
  override def send(message: MidiMsg, timeStamp: Long): Unit = queue.put(MidiDelivery(inlet, message, timeStamp))
}

/** One queued message and where it is bound. */
final case class MidiDelivery(inlet: MidiInlet, message: MidiMsg, timeStamp: Long)
```

The outlet holds the compiled result and nothing else:

```scala
/**
 * The standard outlet: a volatile reference to the fan-out its graph compiled for it.
 *
 * The array is never mutated after publication — a commit installs a new one — so a sender either sees the whole
 * old fan-out or the whole new one, and a message already in flight during a commit goes to the old one. That
 * matches today's behaviour, where [[ConcurrentMidiTransmitter]] snapshots under the read lock and then fans out
 * outside it.
 */
final class CompiledMidiOutlet(val owner: MidiNode, val name: String) extends MidiOutlet {

  @volatile private var targets: Array[MidiTarget] = Array.empty

  private[scmidi] def compile(newTargets: Array[MidiTarget]): Unit = {
    targets = newTargets
  }

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    val currentTargets = targets
    var i = 0
    while (i < currentTargets.length) {
      currentTargets(i).send(message, timeStamp)
      i += 1
    }
  }
}
```

**Revised outlet (C1).** With #121 first, the same class holds no `volatile` and synchronises nothing:

```scala
/**
 * The standard outlet: the fan-out its graph compiled for it.
 *
 * Both the read in [[send]] and the write in [[compile]] happen on the one thread that drives [[owner]] — the
 * write when that thread applies a wiring command from its own queue, between two messages. So no synchronisation
 * is needed, and a rewiring can never land in the middle of a fan-out.
 */
final class CompiledMidiOutlet(val owner: MidiNode, val name: String) extends MidiOutlet {

  private var targets: Array[MidiTarget] = Array.empty

  private[scmidi] def compile(newTargets: Array[MidiTarget]): Unit = {
    targets = newTargets
  }

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    var i = 0
    while (i < targets.length) {
      targets(i).send(message, timeStamp)
      i += 1
    }
  }
}
```

Two consequences worth stating explicitly:

- **Nothing synchronises on the hot path.** Today every message takes a `ReentrantReadWriteLock` read lock per stage
  (Section 2.1). Here it is a plain field read. *(C1: removing that lock is #121's win rather than S1's, since it is
  the per-track thread that makes it safe — but only S1 also has nothing left to publish across threads.)*
- **UC8 costs nothing at the call site.** Whether an edge is a call or a hand-off is decided once, at compile time,
  from the two nodes' affinities. No branch on the hot path, and nothing about threading reaches `TrackSpec`.

Compilation itself is the straightforward part:

```scala
private def compileTargets(snapshot: MidiGraphSnapshot, outlet: MidiOutlet): Array[MidiTarget] = {
  snapshot.edges
    .filter(_.from == outlet)
    .toArray
    .map { edge =>
      if (edge.from.owner.affinity == edge.to.owner.affinity) {
        MidiDirectTarget(edge.to)
      } else {
        MidiHandOffTarget(queueOf(edge.to.owner.affinity), edge.to)
      }
    }
}
```

### 4.4 Traversal, and mutating while traversing

This is where S1 earns its keep. The walk reads the transaction's snapshot, which is frozen; the mutations
accumulate in the transaction and land as one unit when the body returns:

```scala
// Detach every device sink downstream of a track whose input device just vanished.
graph.transaction { tx =>
  for {
    node <- tx.snapshot.depthFirstFrom(trackInputNode)
    sink <- Option(node).collect { case sink: MidiDeviceSinkNode => sink }
  } {
    tx.isolate(sink)
  }
}
```

The walk cannot be corrupted by its own mutations, because `tx.snapshot` does not reflect them. The graph cannot be
left half-rewired, because nothing is applied until the body returns and validation passes. Both properties are
structural, not conventions the caller has to remember.

### 4.5 Lifecycle callbacks

Today's `onAttach` / `onDetach` / `onReceiversChanged` (`MidiProcessor.scala`) fire inside the transmitter's write
lock. Here they become the commit's notify phase:

```scala
/** Reacts to this node's own wiring changing. Called by the commit, after the topology is live, outside any lock. */
trait MidiNodeLifecycle { this: MidiNode =>

  /**
   * New inlets are now fed by this node — configure the outputs they lead to.
   *
   * @param inlets the newly wired inlets; never empty, and disjoint from those already wired.
   */
  protected def onAttachedDownstream(inlets: Seq[MidiInlet]): Unit = {}

  /**
   * Inlets are no longer fed by this node — leave the outputs they lead to in a consistent state.
   *
   * Send to the given inlets '''directly''', not through an outlet: by the time this runs they are no longer wired,
   * so an outlet would not reach them.
   *
   * @param inlets the inlets that were dropped; never empty.
   */
  protected def onDetachedDownstream(inlets: Seq[MidiInlet]): Unit = {}
}
```

The one behavioural difference from today is worth flagging. Today `onDetach` fires **before** the removal, so the
tuner's courtesy 12-EDO messages still reach the detaching receiver through the live transmitter. Here it fires
after, which is why the contract says to send *directly to the given inlets*. `TunerProcessor` already does exactly
this — its `sendTo(receivers, messages, timeStamp)` helper takes the receivers as an argument rather than reading
the transmitter — so the change is contained.

Running the callbacks outside any lock also removes the constraint documented on `MidiProcessor` today, that a hook
"must not wait for another thread".

### 4.6 End to end: the use cases

#### UC1 — a device-to-device track

```scala
graph.transaction { tx =>
  val source = tx.addNode(MidiDeviceSourceNode(inputHandle))
  val trackIn = tx.addNode(TrackInputNode(spec.id))
  val changes = tx.addNode(TuningChangeProcessorNode(spec.tuningChangers, tuningService))
  val tuner = tx.addNode(TunerProcessorNode(spec.tuner))
  val trackOut = tx.addNode(TrackOutputNode(spec.id))
  val sink = tx.addNode(MidiDeviceSinkNode(outputHandle))

  tx.connect(source.outlet, trackIn.inlet)
  tx.connect(trackIn.outlet, changes.inlet)
  tx.connect(changes.outlet, tuner.inlet)
  tx.connect(tuner.outlet, trackOut.inlet)
  tx.connect(trackOut.outlet, sink.inlet)
}
```

`MidiSerialProcessor` is gone: the chain is four edges. An optional stage is simply a node that was not added, and
the edges close over it — no index arithmetic, so **#295 has nowhere left to live**. The tuner's `reset()` messages
reach the device from the notify phase, as today, but now *after* the whole topology is live rather than during
construction.

#### UC2 — a chained pair

One transaction for both tracks and the link between them:

```scala
graph.transaction { tx =>
  val trackA = addTrackNodes(tx, specA)   // source → trackIn → tuner → trackOut
  val trackB = addTrackNodes(tx, specB)   // trackIn → tuner → trackOut → sink

  // Both spec branches name this same edge; the second call is a no-op.
  tx.connect(trackA.outputNode.outlet, trackB.inputNode.inlet)
  tx.connect(trackA.outputNode.outlet, trackB.inputNode.inlet)
}
```

- **#296** — the two calls produce one edge. Whether a reciprocal spec pair is *legal* is still a `TrackSpecs`
  validation question (Section 9, Q5), but it can no longer double the stream.
- **#298** — nothing is live until the commit returns, so track A is never subscribed to its input device while
  track B is unwired. The window does not exist.
- **#297** — `initMidiMessages` are sent after the commit, when the track has its downstream. Section 9, Q4 argues
  that the `nonEmpty` guard should be dropped as well, so that state-tracking nodes observe the stream regardless.

#### UC3 — apply a tuning to every track

Unchanged in substance; the last line goes through an outlet instead of a transmitter:

```scala
class TunerProcessorNode(tuner: Tuner) extends MidiProcessorNode, MidiNodeLifecycle {

  def tune(tuning: Tuning): Unit = {
    for (message <- tuner.tune(tuning)) {
      outlet.send(message, -1)
    }
  }

  override protected def process(message: MidiMsg, timeStamp: Long): Seq[MidiMsg] = tuner.process(message)
}
```

`TrackManager.tune` still walks its tracks and calls `tune` on each. Nothing about UC3 needs the graph — worth
saying, because it means the migration does not disturb the tuning-change flow.

#### UC4 — an output device (re)opens

`TrackManager` still handles `MidiDeviceOpenedEvent` and calls `resetTuner()`. The graph offers a better *lookup*
than today's `spec.output.collect { … }.contains(deviceId)` scan:

```scala
val snapshot = graph.snapshot()
for {
  sink <- snapshot.nodes.collect { case sink: MidiDeviceSinkNode if sink.handle.id == deviceId => sink }
  upstream <- snapshot.predecessorsOf(sink)
  tunerNode <- Option(upstream).collect { case tunerNode: TunerProcessorNode => tunerNode }
} {
  tunerNode.reset()
}
```

This finds the tuner actually feeding the device, rather than inferring it from the spec — which is the same thing
today, but stops being the same thing as soon as tracks can be rewired at runtime.

#### UC5 — an input device disconnects

```scala
def releaseInput(): Unit = {
  for (channel <- 0 until MidiChannelCount) {
    trackOutputNode.outlet.send(AllNotesOffMidiMsg(channel), -1)
  }

  tuningChangeNode.foreach(_.reset())
  tunerNode.foreach(_.reset())
}
```

The `TrackOutputNode` of Section 3.3 is what makes "bypass the tuner, go straight to the output" expressible without
reaching into `transmitter.receivers` as `Track.releaseInput` does today.

#### UC6 — close a track

One transaction, replacing four separate wiring calls and the ordering comment that explains them:

```scala
graph.transaction { tx =>
  tx.removeNode(sourceNode)                        // stop taking input
  tx.disconnect(trackOutputNode.outlet, sinkNode.inlet)   // detach the device; notify sends it 12-EDO
  // Downstream tracks stay wired and get 12-EDO from the tune() below.
}
tune(Tuning.Standard)
```

The subtle ordering `Track.close` documents today — detach the device *first* so the courtesy messages reach it
exactly once, then tune the survivors — survives, but as an explicit two-step rather than as prose.

#### UC7 — replace all tracks

```scala
graph.transaction { tx =>
  tx.snapshot.nodes.foreach(tx.removeNode)
  trackSpecs.tracks.filterNot(_.muted).foreach(spec => addTrackNodes(tx, spec))
  wireInterTrackLinks(tx, trackSpecs)
}
```

Atomic by construction. The "forget the closed tracks before building the new ones, so an event published while the
new devices open never reaches a closed track" dance in `replaceAllTracks` becomes unnecessary.

#### UC8 — a thread per track

Each track's nodes share an affinity; the boundary node drives the thread:

```scala
class TrackInputNode(val trackId: TrackSpec.Id) extends MidiProcessorNode, Runnable {

  override val affinity: MidiAffinity = MidiAffinity(s"track-$trackId")

  private val queue: BlockingQueue[MidiDelivery] = LinkedBlockingQueue()

  override def run(): Unit = {
    while (!Thread.currentThread().isInterrupted) {
      val delivery = queue.take()
      delivery.inlet.send(delivery.message, delivery.timeStamp)
    }
  }
}
```

The compile step of Section 4.3 turns every inter-track edge into a `MidiHandOffTarget` automatically, because the
affinities differ, and every intra-track edge into a `MidiDirectTarget`. `TrackSpec`, `ToTrackOutputSpec` and the
`.tracks` format are untouched.

This also dissolves the lock-ordering hazard `MidiSerialProcessor`'s ScalaDoc currently defers to #121: there is no
per-node lock left to order.

#### UC9 — walk the graph

Section 4.4.

### 4.7 What happens to today's types

| Type | Fate under S1 |
| ---- | ------------- |
| `MidiReceiver` | Kept, as the low-level sink abstraction `MidiInlet` refines |
| `MidiTransmitter` | Superseded as a public API; its read-only view is `MidiOutlet` |
| `ImmutableMidiTransmitter` | Superseded — the compiled `Array[MidiTarget]` is the immutable fan-out |
| `MutableMidiTransmitter` | Retained as `CompiledMidiOutlet`'s internal, with its mutators narrowed to `private[scmidi]` so only a commit may call them |
| `ConcurrentMidiTransmitter` | Same; its read lock leaves the hot path (Section 4.3) |
| `MidiSplitter` | **Deleted.** Fan-out is what an outlet with several edges does; a splitter degenerates to identity |
| `MidiProcessor` | Becomes `MidiProcessorNode`; inner receiver/transmitter classes become ports |
| `MidiSerialProcessor` | **Deleted.** A chain is a path of ordinary edges (see UC1) |
| `MidiDeviceHandle` | Unchanged; node-ness is an adapter (Section 3.2) |

The honest reading of this table: **S1 supersedes most of the `MidiTransmitter` family that #280 introduced.** The
softer framing in the right column — keeping the mutable/concurrent classes as the outlet's internal with
`private[scmidi]` mutators — is genuinely available and preserves the work, but the public `addReceiver` /
`removeReceiver` API that `Track` and `TrackManager` use today does go away, by design: unmediated wiring is exactly
what F3 is.

### 4.8 Cost and risk

**Cost.** The largest of the three. A new central type with transaction and compile machinery; `MidiProcessor`
rewritten around ports; `MidiSerialProcessor` and `MidiSplitter` deleted and their callers migrated; `Track` and
`TrackManager` rewritten around transactions; `MidiProcessorTest`, `MidiSerialProcessorTest`, `TrackTest` and
`TrackManagerTest` substantially rewritten.

**Risks.**

- *Cache coherence.* The compiled fan-out is derived state. A commit path that misses an outlet leaves it sending to
  a stale topology. Mitigation: recompile every outlet of every node touched by the transaction, and assert in tests
  that a compiled fan-out always equals the one derived from the edge set.
- *Commit ordering.* The notify phase's ordering (topological? reverse for detach?) is a real design question, not a
  detail. Section 9, Q6.
- *Re-entrancy.* A lifecycle callback that mutates the graph would re-enter `transaction`. Either forbid it, or
  queue such changes for a follow-up commit. Section 9, Q7.
- *Big-bang migration.* Deleting `MidiSerialProcessor` touches `Track` and every pipeline test at once. Splitting
  #310 into sub-issues — ports first, then the graph, then the track migration, then affinity — is how #278 handled
  a change of this size, and is the obvious template.

---

## 5. Option S2 — distributed topology, walkable nodes

> Every node keeps owning its own downstream list, exactly as today. The ports of Section 3 are introduced, edges
> gain `owner` back-references, and traversal becomes a free function over them.

### 5.1 API

The outlet *is* today's transmitter, narrowed so its targets are inlets rather than anonymous receivers:

```scala
/**
 * An outbound port that owns its own downstream list.
 *
 * It is [[MutableMidiTransmitter]] with the element type narrowed from [[MidiReceiver]] to [[MidiInlet]] — which is
 * the whole of what makes the graph walkable — and with a back-reference to the node it belongs to.
 */
class OwnedMidiOutlet(val owner: MidiNode, val name: String, initialTargets: Seq[MidiInlet] = Seq.empty)
  extends MidiOutlet {

  private var _targets: Seq[MidiInlet] = initialTargets

  /** The inlets fed by this port; an immutable snapshot. */
  def targets: Seq[MidiInlet] = _targets

  final def addTarget(inlet: MidiInlet): Unit = withChangeGuard {
    setTargets(_targets :+ inlet)
  }

  final def removeTarget(inlet: MidiInlet): Unit = withChangeGuard {
    setTargets(_targets.filterNot(_ == inlet))
  }

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    val currentTargets = targets
    currentTargets.foreach(_.send(message, timeStamp))
  }

  protected def withChangeGuard[R](body: => R): R = body

  protected def setTargets(newTargets: Seq[MidiInlet]): Unit = {
    _targets = newTargets
  }
}
```

`withChangeGuard` / `setTargets` are today's two hooks, unchanged, so the concurrent variant and the attach/detach
protocol carry over as-is. This is the continuity argument for S2: the `MidiTransmitter` family survives essentially
intact, renamed.

Traversal is a free function, since there is no object to hang it on:

```scala
/** Walks a MIDI flow graph from a node, following the downstream lists each node owns. */
object MidiGraphWalk {

  /**
   * @param from the node to start at.
   * @return the nodes reachable downstream, depth first, each visited once, `from` included.
   * @note The walk reads each node's targets as it reaches it, so a concurrent rewiring is partly visible to it.
   *       See [[reachableFrom]] when a stable set is needed.
   */
  def depthFirstFrom(from: MidiNode): LazyList[MidiNode] = // …

  def breadthFirstFrom(from: MidiNode): LazyList[MidiNode] = // …

  /** Materialises the reachable set eagerly, so that a caller may then rewire without disturbing its own walk. */
  def reachableFrom(from: MidiNode): Vector[MidiNode] = depthFirstFrom(from).toVector
}
```

### 5.2 Traversal, and mutating while traversing

Here is S2's central limitation, stated as plainly as possible.

Each node's `targets` read returns an immutable `Seq`, so a *single node's* fan-out is consistent. But there is no
consistent view of the **graph**: a walk reads node 1's targets, then node 7's, then node 12's, and a rewiring
between those reads is partly visible. A walk can therefore visit a node that has just been removed, or miss one
that has just been added, or visit one twice by two paths if an edge moves underneath it.

Mutating while traversing is therefore **collect-then-mutate, by convention**:

```scala
// Correct under S2: materialise first, then rewire.
val reachable = MidiGraphWalk.reachableFrom(trackInputNode)
for (sink <- reachable.collect { case sink: MidiDeviceSinkNode => sink }) {
  predecessorsOf(sink).foreach(_.outlet.removeTarget(sink.inlet))
}

// Wrong under S2, and nothing stops you: the walk is lazy, so the removals below change what it yields.
for (sink <- MidiGraphWalk.depthFirstFrom(trackInputNode).collect { case sink: MidiDeviceSinkNode => sink }) {
  predecessorsOf(sink).foreach(_.outlet.removeTarget(sink.inlet))
}
```

Note also that `predecessorsOf` is not cheap under S2. Edges are held forward-only, so finding what feeds a node
means scanning every node's targets, and that requires a registry of all nodes — which S2 does not otherwise need.
Either accept an O(V + E) scan per query, or add reverse links to every inlet and keep them in step with the forward
ones, which is a second copy of the topology to get wrong.

### 5.3 End to end: the use cases

**UC1** is today's code with the names changed:

```scala
private val pipeline: MidiSerialProcessor = MidiSerialProcessor(
  Seq(tuningChangeProcessor, tunerProcessor).flatten, outputDeviceHandle.map(_.sinkNode.inlet).toSeq)

inputDeviceHandle.foreach(_.sourceNode.outlet.addTarget(inlet))
```

`MidiSerialProcessor` survives, so **#295 still needs its own fix** — the `require(1 <= _index)` in
`wireProcessorToPrevious`, and the half-updated `_processors` the throw leaves behind.

**UC2** is today's `TrackManager` loop with the names changed, so **#296 needs an explicit fix**: either a
membership check before `addTarget`, or `addTarget` made idempotent, or reciprocal spec pairs rejected in
`TrackSpecs` validation. **#297 and #298 need an explicit ordering fix** in `TrackManager`: build every track, wire
every link, *then* subscribe the inputs — three phases the class must keep straight by hand, with nothing structural
to enforce it.

These are all perfectly fixable. The distinction that matters is that under S2 they are *fixed*, and can regress;
under S1 they are *unrepresentable*.

**UC3–UC7** are today's code with the names changed. Nothing about them improves or worsens.

**UC8 is where S2 hurts.** With no central compile step, something must decide per edge whether it is a call or a
hand-off. Three ways, none good:

1. *Check on the hot path.* `send` compares affinities per message. A branch and two field reads per message per
   target, plus the affinity check is now in the inner loop.
2. *Wrap at wiring time.* Whoever calls `addTarget` wraps the inlet in a hand-off decorator when the affinities
   differ. Workable, but the knowledge is now spread across `Track`, `TrackManager` and anything else that wires,
   and a caller that forgets produces a cross-thread direct call — a data race that will not reproduce reliably.
3. *A wiring helper.* Funnel every `addTarget` through one function that applies rule 2. This is a central authority
   over wiring in all but name — at which point S1's transaction is the same idea, done honestly.

**UC9** works, with the caveat of Section 5.2.

### 5.4 What happens to today's types

| Type | Fate under S2 |
| ---- | ------------- |
| `MidiReceiver` | Kept |
| `MidiTransmitter` family | Kept, renamed to outlets, element type narrowed to `MidiInlet` |
| `MidiSplitter` | Kept (still needed by `JavaMidiDeviceHandle`), gains an owner |
| `MidiProcessor` | Ports renamed `inlet` / `outlet`; internals unchanged |
| `MidiSerialProcessor` | Kept, with #295 still to fix |
| `MidiDeviceHandle` | Unchanged; node adapters added |

### 5.5 Cost and risk

**Cost.** The smallest of the three by a wide margin: a rename pass, an `owner` field on each port, one type
narrowing (`Seq[MidiReceiver]` → `Seq[MidiInlet]`), a new traversal object, and node adapters for
`MidiDeviceHandle`. Most existing tests survive with renamed identifiers.

**Risks.**

- *It does not fix F3.* The four open bugs each need their own fix, and the class of bug remains reachable.
- *Traversal is second-class.* No consistent snapshot, and predecessors are either a scan or a second index.
- *UC8 has no good home.* Each of the three placements above either costs the hot path or scatters a correctness
  rule that fails silently.
- *It may be a way-station.* If UC8 pushes you to a central wiring helper anyway (option 3 above), S2 becomes the
  first half of S1, done twice.

The fair counter-argument for S2: it delivers goal 1 and most of goal 2 for perhaps a quarter of the work, and it
leaves S1 available later. If #310 is meant to *unblock* #121 quickly rather than to settle the architecture, this
is the option that does it.

---

## 6. Option S3 — central graph, resolved per message

> A `MidiGraph` owns the adjacency, as in S1, but nothing is compiled. Every `send` asks the graph who is
> downstream.

### 6.1 API

The graph and the transaction are S1's, minus the compile step. The outlet becomes trivial:

```scala
/** An outlet that resolves its fan-out from the graph on every message. */
final class ResolvingMidiOutlet(val owner: MidiNode, val name: String, graph: MidiGraph) extends MidiOutlet {

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    val targets = graph.targetsOf(this)
    targets.foreach(_.send(message, timeStamp))
  }
}

class MidiGraph {
  /** The current adjacency. Replaced wholesale by a commit, so a reader sees one consistent version. */
  @volatile private var adjacency: Map[MidiOutlet, Seq[MidiInlet]] = Map.empty

  /** The inlets currently fed by `outlet`. */
  def targetsOf(outlet: MidiOutlet): Seq[MidiInlet] = adjacency.getOrElse(outlet, Seq.empty)

  def transaction[R](body: MidiTransaction => R): R = // … validate, swap `adjacency`, notify
}
```

### 6.2 What it buys, and what it costs

S3 inherits **every structural property of S1** — validation of the whole topology, atomic multi-edge commits,
consistent snapshots, first-class traversal, the same fix for #295–#298 — because those all come from centralising
the edges, not from compiling them.

What it gives up is the hot path, and in two distinct ways.

**The measurable cost is modest.** A `HashMap` lookup on a `@volatile` immutable `Map` is on the order of tens of
nanoseconds — comparable to, and possibly cheaper than, the `ReentrantReadWriteLock` read lock the code takes today
(Section 2.1). Judged purely on nanoseconds per message, S3 is not obviously worse than the status quo.

**The structural cost is the real objection.** Every node on every thread reads one shared object on every message:

- That single `@volatile` reference is read by all track threads, so the cache line holding it is shared across
  cores. It is read-mostly, so this is cheap in steady state — but it is a global point of contention introduced
  deliberately into a design (#121) whose entire purpose is to give each track an independent thread.
- The `Map` and its internal arrays are shared structure that every track thread touches, so no track's hot path is
  confined to its own working set. That is the opposite of what per-track threading is for.
- It makes the real-time story harder to state. "Sending is a walk over an array this node owns" is a sentence you
  can reason about under a deadline. "Sending is a hash lookup into a structure shared by every thread" is not
  false, but it is not a sentence you want in the `Tuner` hot path's documentation either.

**And the saving is small.** What S3 avoids relative to S1 is the compiled cache and its coherence invariant —
roughly the `compile` method of Section 4.3 and the tests that assert the cache matches the edge set. That is real
work, but it is a small fraction of S1's cost, and it is the *easily testable* fraction: a property test asserting
`outlet.targets == snapshot.edges.filter(_.from == outlet).map(_.to)` after every commit covers it.

**UC8 is also worse than S1's.** The affinity comparison cannot be pre-decided, so either the hand-off decision runs
per message (a branch per target, on top of the lookup), or the graph stores already-wrapped targets in the
adjacency map — at which point the map *is* a compiled structure and S3 has become S1 with a slower lookup.

### 6.3 Use cases

UC1–UC7 and UC9 are identical to S1 (Sections 4.6 and 4.4), since only the outlet implementation differs. UC8 is as
described above.

### 6.4 Cost and risk

**Cost.** Slightly less than S1 — the transaction machinery is the same, the compile step and its tests are not.

**Risk.** The one that matters is that S3's saving is concentrated in exactly the part of S1 that is cheapest to get
right, while its cost is concentrated in exactly the part of the system (#121's per-track threads) that is most
expensive to get wrong later. Switching from S3 to S1 afterwards is not hard — it is a change to one class — but by
then the per-message lookup will have shaped how the hot path is written and documented.

---

## 7. Comparison

### 7.1 Against the two goals of #310

| | S1 compiled | S2 distributed | S3 uncompiled |
| --- | --- | --- | --- |
| Goal 1 — node types and roles | Yes | Yes | Yes |
| Goal 2 — DFS/BFS | Yes, over a real adjacency | Yes, over back-references | Yes, over a real adjacency |
| Goal 2 — mutate while traversing | **By construction** (frozen snapshot + atomic commit) | **By convention** (collect, then mutate) | By construction |
| Predecessor queries | O(1) after snapshot | O(V + E) scan, or a second index | O(1) after snapshot |
| Whole-graph validation | Yes, pre-commit | No | Yes, pre-commit |

### 7.2 Against the open issues

| Issue | S1 | S2 | S3 |
| ----- | -- | -- | -- |
| [#295](https://github.com/calinburloiu/microtonalist/issues/295) `removeAt(0)` throws | Closed by removal — `MidiSerialProcessor` ceases to exist | **Needs its own fix** | Closed by removal |
| [#296](https://github.com/calinburloiu/microtonalist/issues/296) reciprocal pair doubles | Closed by construction — edges are a set | **Needs its own fix** (dedupe or reject) | Closed by construction |
| [#297](https://github.com/calinburloiu/microtonalist/issues/297) init messages dropped | Closed by construction — atomic commit | **Needs its own fix** (ordering) | Closed by construction |
| [#298](https://github.com/calinburloiu/microtonalist/issues/298) track drops input | Closed by construction — atomic commit | **Needs its own fix** (ordering) | Closed by construction |
| [#121](https://github.com/calinburloiu/microtonalist/issues/121) thread per track | Infrastructure supplied; hand-off free at the call site | Infrastructure absent; three bad placements | Infrastructure supplied; hand-off costs a per-message branch |
| [#305](https://github.com/calinburloiu/microtonalist/issues/305) attach/detach | Ordered, lock-free notify phase | Today's hooks, unchanged | Same as S1 |

### 7.3 Against the hot path *(revised — see §0)*

Per MIDI message, per stage crossed. The columns assume #121 has landed, so a port is driven by one thread (C1):

| | Today | S1 | S2 | S3 |
| --- | --- | --- | --- | --- |
| Synchronisation | `ReentrantReadWriteLock` read lock | **None** — plain field | None within a track | None, but see below |
| Target lookup | Field read | Field read | Field read | Hash lookup in a shared map |
| Cross-thread decision (#121) | n/a | Pre-decided at compile | Per message, or wrapped at wiring | Per message, or effectively compiled |
| Shared structure across threads | Per-node lock | **None** | None within a track | **One map, all threads** |

Removing today's per-stage read lock is #121's doing, not any option's: it is the per-track thread that makes a
plain field read safe. What distinguishes the options is what is left afterwards. S1 and S2 leave a track's hot path
entirely within its own working set. S3 does not — its adjacency map is read by every track thread on every message,
which is the one thing per-track threading exists to avoid, and C1 makes that contrast sharper rather than softer.

### 7.4 Migration size

Rough, relative:

| | S1 | S2 | S3 |
| --- | --- | --- | --- |
| New types | `MidiGraph`, transaction, snapshot, targets, ports, node roles | ports, node roles, walk object | `MidiGraph`, transaction, snapshot, ports, node roles |
| Deleted | `MidiSplitter`, `MidiSerialProcessor` | none | `MidiSplitter`, `MidiSerialProcessor` |
| `MidiTransmitter` family | Demoted to outlet internals, mutators `private[scmidi]` | Kept, renamed | Mostly superseded |
| `Track` / `TrackManager` | Rewritten around transactions | Renamed | Rewritten around transactions |
| Test churn | High | Low | High |
| Relative effort | **1.0** | **~0.25** | **~0.85** |

---

## 8. Recommendation

**S1**, with the caveat that it should be split into sub-issues rather than landed at once.

The reasoning, in order:

1. **Goal 2 is the whole point, and only S1 and S3 deliver it properly.** "Mutate while traversing" as a convention
   the caller must remember (S2) is not the same feature as "mutate while traversing" as a property the design
   guarantees. For a graph that will be rewired from the UI at runtime — which is what the `Track Management`
   milestone is about — the convention will be broken, and the failure will be a rewiring race that does not
   reproduce.
2. **Four of the eight open `Track Management` issues are instances of one fault (F3), and only centralisation
   removes it.** S2 fixes each of #295–#298 individually and leaves the class reachable. Fixing four symptoms of one
   cause, and keeping the cause, is the worse trade even though it is cheaper.
3. **Centralising the topology costs the hot path nothing.** *(Revised, C1: the per-stage read lock comes off every
   message thanks to #121's per-track thread, not thanks to S1. What S1 adds is that nothing is left to publish
   across threads either — a track's sending stays entirely within its own working set.)* That is not the reason to
   choose S1, but it removes the usual objection to centralisation.
4. **S3's saving is in the wrong place.** It avoids the compiled cache — the part of S1 that is cheapest to build
   and easiest to test — at the price of putting a shared structure on every thread's hot path, in a system whose
   next step is to give each track its own thread.

**Against S1**, honestly: it is four times the work of S2, it supersedes the public API of the `MidiTransmitter`
family introduced by #280, and it rewrites `Track` and `TrackManager`. If #310's real purpose is to unblock #121
quickly, S2 followed later by S1 is a defensible sequence — but Section 5.3's UC8 analysis suggests S2 will push you
towards a central wiring helper anyway, which is S1's transaction under another name.

*(Revised, C3.)* The five-step split originally proposed here is superseded. #310 is now split into three
sub-issues, all scheduled after [#121](https://github.com/calinburloiu/microtonalist/issues/121), which supplies the
per-track threads this design assumes:

1. [#311](https://github.com/calinburloiu/microtonalist/issues/311) — **Introduce MIDI graph ports and node roles**
   (Section 3). Behaviour-preserving; no topology change. Delivers goal 1.
2. [#312](https://github.com/calinburloiu/microtonalist/issues/312) — **Add `MidiGraph` with transactions,
   snapshots and traversal** (Sections 4.1–4.4). Ships unused, so it is built and tested on its own. Delivers goal 2,
   and carries open questions Q6, Q7 and Q8.
3. [#313](https://github.com/calinburloiu/microtonalist/issues/313) — **Move track wiring onto the MIDI graph**
   (Section 4.6). `Track` and `TrackManager` on transactions; `MidiSerialProcessor` and `MidiSplitter` deleted;
   the `nonEmpty` guard dropped. This is where #295, #296, #297 and #298 close.

The step that carried affinity and the compiled hand-off has no separate sub-issue: with #121 first, affinity
already exists and #312 simply reads it when it compiles an edge.

---

## 9. Open questions the design must still settle

**Q1 — port naming. ANSWERED (D1): `inlet` / `outlet`.** This study uses `inlet` / `outlet`, borrowed from
Max/MSP, because `input` / `output` are
already taken by `MidiDirection` and by `TrackInputSpec` / `TrackOutputSpec`, where they describe a *device's*
direction rather than the graph's. A device opened as `Input` is a graph *source*. Alternatives: `source` / `sink`
(clear, but they name node roles in Section 3.2), or `in` / `out`.

**Q2 — is `MidiNodeId` needed, or is identity enough?** Identity suffices for wiring. An id helps diagnostics,
logging and any future serialisation of a graph, and gives the UI something stable to address. It also costs a
uniqueness invariant to maintain.

**Q3 — flat graph or composite nodes? ANSWERED (D2): flat.** Section 3.3 proposes flat, with `Track` as a facade
over nodes sharing a
`trackId` and the boundary pinned by `TrackInputNode` / `TrackOutputNode`. The alternative is a `MidiCompositeNode`
owning a subgraph, which makes a track opaque to traversal and raises the question of whether a walk descends into
it. Flat is simpler and matches how `TrackManager` already wires processors directly.

**Q4 — should `process` run when nothing is downstream? ANSWERED (D3): yes, the guard goes.** Today it does not
(`MidiProcessor.scala:62`), which is why
#297 and #298 drop messages *unprocessed*. This study argues the guard should go: `MidiChannelStateTracker` and the
tuners need to observe the stream whether or not anyone is listening, so that a note already held when a link is
wired is known to the tuner afterwards. #297's own text raises this as an open question; it should be answered in
the design, not left to the commit protocol.

**Q5 — is a reciprocal spec pair legal?** S1 and S3 make it harmless, but #296 also asks whether declaring one link
from both ends is a *valid* way to write a `.tracks` file. That is a `TrackSpecs` validation decision that no
topology choice answers.

**Q6 — notify-phase ordering.** In what order do the lifecycle callbacks of Section 4.5 fire? Topological, so an
upstream node configures its output before a downstream one runs? Reverse topological for detach? Undefined, with
each callback required to be independent? This determines whether a tuner's `reset()` can rely on the node feeding
it having already been configured.

**Q7 — may a lifecycle callback mutate the graph?** Today a hook may send downstream re-entrantly but must not wait
for another thread. Under S1, a callback that wires something would re-enter `transaction`. Forbid it, or queue the
change for a follow-up commit?

**Q8 — are cycles illegal?** A cycle in a MIDI flow graph is an infinite message loop, so validation should probably
reject one. But `TrackSpecs` can express one today, and it is worth deciding whether that is a load-time error, a
commit-time error, or a warning with a hop limit.

**Q9 — what drives the `MidiAffinity.Caller` default?** A graph with no explicit affinities behaves exactly as today
(every send is a direct call on the caller's thread), which is what keeps steps 1–4 of the split in Section 8
behaviour-preserving. Confirm that is the intended semantics before #121 builds on it.
