# `businessync` module architecture

## Responsibility

`businessync` is Microtonalist's threading and event-bus infrastructure. It centralizes two concerns that otherwise leak
into every domain and GUI component:

- **Thread affinity** — running work on the right thread (a dedicated *Business Thread* for all domain logic, the *UI
  Thread* for all GUI updates) so domain and GUI state can be mutated without explicit locking, and *Track Threads*, one
  for each track (not yet implemented).
- **Decoupled communication** — a publish/subscribe event bus so producers (MIDI device changes, tuning changes) notify
  consumers (the GUI or other components) without direct references, delivering each event on the correct thread for its
  subscriber.

It is pure infrastructure: it has **no application dependencies** and knows nothing about scales, tunings, MIDI, or the
GUI. Domain and UI modules depend on it, never the other way around.

> **Implementation status (important).** The module is currently an early skeleton. `Businessync` is a thin wrapper
> around a Guava [`EventBus`](https://github.com/google/guava/wiki/EventBusExplained); most of the threading API is
> either stubbed (`???`) or runs work inline on the caller's thread. The richer multi-thread, queue-based, sequentially
> consistent design described in issue [#90](https://github.com/calinburloiu/microtonalist/issues/90) is **planned, not
> yet built**. Treat the multi-thread API described under [Threading model](#threading-model) as the *target*, not
> current behavior.

## Key types

All types live in package `org.calinburloiu.businessync`.

- **`Businessync`** — the single entry point, constructed with a Guava `EventBus` and injected into the modules that
  need cross-thread communication. It wraps event publish/subscribe and (eventually) thread dispatch.
- **`BusinessyncEvent`** — the abstract base class for every event posted through the bus, providing a `name` defaulting
  to the runtime simple class name. Domain events extend it as sealed `case class`/`case object` hierarchies (e.g.
  `MidiEvent`, `TuningEvent`, `TrackEvent`). Because Guava's `EventBus` dispatches by runtime type and walks supertypes,
  **subscribing to a base class catches all of its subclasses** — e.g. `TrackManager` subscribes to `TuningEvent` and
  receives every concrete subtype.
- **`BusinessyncUiHandler`** — a small `case class` (`run` lambda + `isUiThread` predicate) intended to make UI-thread
  submission *pluggable*: a GUI toolkit supplies the lambda that schedules work onto its event thread. It is defined but
  not yet consumed by `Businessync`.

### Current behavior

The `Businessync` API is written against the *target* multi-thread semantics but is mostly not backed by real dispatch
yet: `publish`/`register` delegate straight to the Guava `EventBus` (synchronous, on the caller's thread); `run`/`runIf`
run their function inline; `call`/`callAsync` use the Scala global `ExecutionContext`; and the UI-thread methods
(`runOnUi`, `callOnUi`, `handleOnUi`) plus the keyed `subscribe`/`subscribeOnUi` are stubs (`???` or no-ops). The
numerous `// TODO #90` markers track the work to back these with a real dispatcher.

Until `subscribe`/`subscribeOnUi` exist, subscription goes straight through Guava: a handler method is annotated with
`com.google.common.eventbus.@Subscribe` (taking the event class or a base class it wants), the owning object is handed
to `businessync.register(obj)`, and producers call `businessync.publish(event)` — Guava then invokes the matching
handlers synchronously on the publishing thread. Those call sites carry `// TODO #90` comments marking them for
migration to `subscribe`/`subscribeOnUi`.

## Threading model

This is the *intended* multi-thread model (see also the project [Threading Model wiki
page](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model)); the current code does not yet enforce it.

- **Business Thread** — owns all domain logic. Domain state is mutated only here, so it needs no locks. Work is
  submitted with `run` (fire-and-forget) or `call` (returns a `Future`); business-side handlers register with
  `subscribe` (today: `@Subscribe` + `register`).
- **UI Thread** — owns all GUI updates. Work is submitted with `runOnUi`/`callOnUi` and UI-side handlers with
  `subscribeOnUi`. The actual UI thread is the GUI toolkit's event thread (Swing EDT now; JavaFX is the migration
  target), supplied via `BusinessyncUiHandler`.
- **Cross-thread rule.** Never mutate domain state from the UI thread or GUI state from the Business Thread directly;
  always hop via the appropriate `Businessync` method. Results computed on worker threads (Scala `Future`s, e.g. file
  I/O) must be funneled back onto the Business Thread before touching domain state.

Other threads in the wider system (documented on the wiki, not owned here): **Track threads** (one per `Track`), **MIDI
threads** (CoreMidi4J), and **worker threads** (the `Future` execution context).

**Current reality.** No dedicated Business or UI thread is started yet, so the threading *contracts* are expressed at
call sites (e.g. `TuningService.changeTuning` wraps its body in `businessync.run`, `TrackService.open` uses
`businessync.callAsync`) but are not yet *enforced* by `Businessync`. Call sites are written against the target API so
that, once the real dispatcher exists, behavior tightens without touching callers.

## Planned design (issue #90)

Issue [#90](https://github.com/calinburloiu/microtonalist/issues/90) (milestone *Architecture*) specifies the full
design; treat it as planned and subject to change. The headline elements:

- **Async calls on both threads** — `run`/`call`, each in a Business and a UI variant. *Present as signatures; not yet
  backed by real thread dispatch.*
- **Class-keyed pub/sub with base-class matching.** *Base-class matching already works via Guava `@Subscribe`; the
  `subscribe`/`subscribeOnUi` methods are stubs.*
- **Pluggable UI-thread submission** via `BusinessyncUiHandler`. *Type exists but is not yet consumed.*
- **Sequential consistency of UI updates** — *not implemented.* The plan uses a UI-side **version** counter attached to
  each queued update so the business thread (the consumer) can reject stale responses, with explicit version handling
  for async business-thread work and a rule that publishing to UI subscribers from an unmanaged thread must throw. See
  the issue for the queue/version mechanics.

## Dependencies

`businessync` has **no application `dependsOn`** — it is a leaf in the dependency graph, alongside `intonation` and
`common`. Its only third-party dependency is Guava (`EventBus`), plus the inherited common logging stack.

Modules that depend on it: `sc-midi` (publishes `MidiEvent`s), `tuner` (publishes/subscribes to `TuningEvent`/
`TrackEvent`), `app` (constructs the instance and wires it in), `cli` (constructs one for `MidiManager`), and
transitively `format` (publishes `ScaleLossyConversionEvent`) and `ui` (subscribes to `TuningIndexUpdatedEvent`). The
instance is created once in `MicrotonalistApp.run` and injected everywhere, so a single bus/threading context is shared
across the running app.
