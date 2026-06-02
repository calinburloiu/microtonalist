# `businessync` module architecture

## Responsibility

`businessync` is Microtonalist's threading and event-bus infrastructure. It is meant to centralize two concerns that
otherwise leak into every domain and GUI component:

- **Thread affinity** — running work on the right thread (a dedicated *Business Thread* for all domain/MIDI logic, and
  the *UI Thread* for all GUI updates) so that domain and GUI state can be mutated without explicit locking.
- **Decoupled communication** — a publish/subscribe event bus so producers (e.g. MIDI device changes, tuning changes)
  can notify consumers (e.g. the GUI) without direct references, and so events can be delivered on the correct thread
  for the subscriber.

It is pure infrastructure: it has **no application dependencies** and knows nothing about scales, tunings, MIDI, or the
GUI. Domain and UI modules depend on it, never the other way around.

> **Implementation status (important).** The module is currently an early skeleton. `Businessync` is a thin wrapper
> around a Guava [`EventBus`](https://github.com/google/guava/wiki/EventBusExplained); most of the threading API is
> either stubbed (`???`) or runs work inline on the caller's thread. The richer two-thread, queue-based, sequentially
> consistent design described in issue
> [#90](https://github.com/calinburloiu/microtonalist/issues/90) is **planned, not yet built**. The
> [Threading model](#threading-model) and [Planned design](#planned-design-issue-90) sections below separate what the
> code does *today* from what is intended. Treat the intended two-thread API documented below (separate Business/UI
> threads, `runOnUi`, `callAsync`) as the *target*, not the current behavior.

## Key types

All types live in package `org.calinburloiu.businessync`.

- **`Businessync`** — `businessync/src/main/scala/org/calinburloiu/businessync/Businessync.scala:27`
  The single entry point. Constructed with a Guava `EventBus` (`Businessync(eventBus)`) and injected into the modules
  that need cross-thread communication. Wraps event publish/subscribe and (eventually) thread-dispatch. See the
  [API surface](#api-surface-current-code) below for the per-method status.

- **`BusinessyncEvent`** — `businessync/src/main/scala/org/calinburloiu/businessync/BusinessyncEvent.scala:22`
  Abstract base class for every event posted through the bus. Provides a single `name: String` member defaulting to the
  runtime simple class name. All domain events extend it, typically as `case class`/`case object` hierarchies under a
  sealed base (e.g. `MidiEvent`, `TuningEvent`, `TrackEvent`, `ScaleLossyConversionEvent`). Because Guava's `EventBus`
  dispatches by the event's runtime type and walks its supertypes, **subscribing to a base class catches all of its
  subclasses** — e.g. `TrackManager` subscribes to `TuningEvent` and receives every concrete `TuningEvent` subtype.

- **`BusinessyncUiHandler`** — `businessync/src/main/scala/org/calinburloiu/businessync/Businessync.scala:24`
  A small `case class` holding `run: () => Unit` and `isUiThread: () => Boolean`. This is the seam intended to make the
  UI-thread submission *pluggable*: a GUI toolkit (Swing today, JavaFX planned) supplies the lambda that schedules work
  onto its event thread and a predicate that reports whether the current thread is that UI thread. It is defined but not
  yet wired into `Businessync` (no constructor parameter consumes it in the current code).

### API surface (current code)

The table reflects the actual bodies in `Businessync.scala`, not the target semantics.

| Method | Signature | Current behavior |
| ------ | --------- | ---------------- |
| `publish` | `publish(event: BusinessyncEvent): Unit` | Delegates to `eventBus.post(event)`. Dispatch happens synchronously on the caller's thread. |
| `handle` | `handle(event: BusinessyncEvent): Unit` | Currently an alias for `publish`. Intended: deliver to Business-thread subscribers only, throwing if not on the Business Thread. |
| `handleOnUi` | `handleOnUi(event: BusinessyncEvent): Unit` | Stub (`???`). Intended: deliver to UI-thread subscribers only, throwing if not on the UI Thread. |
| `subscribe` | `subscribe[E <: BusinessyncEvent](eventClass: Class[E], handler: E => Unit): Unit` | No-op (`{}`). Intended: register a Business-thread handler keyed by event class. |
| `subscribeOnUi` | `subscribeOnUi[E <: BusinessyncEvent](eventClass: Class[E], handler: E => Unit): Unit` | No-op (`{}`). Intended: register a UI-thread handler keyed by event class. |
| `register` | `register(obj: AnyRef): Unit` | Delegates to `eventBus.register(obj)`. The mechanism actually used today — see below. To be replaced by `subscribe`. |
| `run` | `run(fn: () => Unit): Unit` | Runs `fn()` inline (no thread hop). Intended: schedule on the Business Thread. |
| `runIf` | `runIf(condition: Boolean)(fn: () => Unit): Unit` | Runs `fn()` inline iff `condition`. |
| `runOnUi` | `runOnUi(fn: () => Unit): Unit` | Stub (`???`). Intended: schedule on the UI Thread. |
| `call` | `call[R](fn: () => R): Future[R]` | Runs `fn()` on the Scala global `ExecutionContext` via `Future { fn() }`. Intended: run on the Business Thread and return its result. |
| `callAsync` | `callAsync[R](fn: () => Future[R]): Future[R]` | Invokes `fn()` and returns its `Future` directly (no thread hop). |
| `callOnUi` | `callOnUi[R](fn: () => R): Future[R]` | Stub (`???`). Intended: run on the UI Thread and return its result. |

Note a naming subtlety in the current API: `callAsync` takes a `() => Future[R]` (it flattens an already-async call),
while `call` takes a `() => R`. Every method except the bus delegations and the inline `run`/`runIf`/`call`/`callAsync`
is either a no-op or `???`.

### Event delivery today (`@Subscribe` + `register`)

Until `subscribe`/`subscribeOnUi` are implemented, subscription goes straight through Guava:

1. A subscriber method is annotated with `com.google.common.eventbus.@Subscribe` and takes exactly one parameter whose
   type is the event class (or a base class) it wants. Example:
   `ui/.../TuningListFrame.scala:89` (`onTuningChanged(e: TuningIndexUpdatedEvent)`),
   `tuner/.../TrackManager.scala:113` (`onTuningChanged(e: TuningEvent)` — base class, catches all subtypes).
2. The owning object is handed to `businessync.register(obj)`, which forwards to `eventBus.register(obj)`
   (e.g. `MicrotonalistApp.scala:97`, `TunerModule.scala:33`).
3. Producers call `businessync.publish(event)`; Guava invokes the matching `@Subscribe` methods synchronously **on the
   publishing thread** (there is no thread hop yet).

The `@Subscribe`-based call sites carry `// TODO #90 Remove @Subscribe after implementing businessync` comments,
marking them for migration to `subscribe`/`subscribeOnUi` once the real implementation lands.

```scala
// Producer (sc-midi)
businessync.publish(MidiDeviceConnectedEvent(id))

// Subscriber (ui) — registered via businessync.register(this)
@Subscribe
def onTuningChanged(event: TuningIndexUpdatedEvent): Unit = {
  listComponent.setSelectedIndex(event.tuningIndex)
}
```

## Threading model

This section documents the *intended* two-thread model (see also the project
[Threading Model wiki page](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model)) and flags where the
current code does not yet enforce it.

- **Business Thread** — owns all domain and MIDI logic. Domain state is mutated only here, so it needs no locks. Work is
  meant to be submitted with `businessync.run { … }` (fire-and-forget) or `businessync.call { … }` (returns a
  `Future`). Handlers for business-side events are registered with `subscribe` (today: `@Subscribe` + `register`).
- **UI Thread** — owns all GUI updates. GUI components are touched only here. Work is meant to be submitted with
  `businessync.runOnUi { … }` / `callOnUi`, and UI-side event handlers with `subscribeOnUi`. The actual UI thread is
  the GUI toolkit's event thread (Swing EDT now; JavaFX Application Thread is the migration target — see TODOs `#99`
  referencing the JavaFX move). The toolkit-specific submission is meant to be supplied via `BusinessyncUiHandler`.
- **Cross-thread rule.** Never mutate domain state from the UI thread or GUI state from the Business Thread directly;
  always hop via the appropriate `Businessync` method. Results computed on worker threads (Scala `Future`s — e.g. file
  I/O) must be funneled back onto the Business Thread before touching domain state.

Other threads in the wider system (documented on the wiki, not owned by this module): **Track threads** (one per
`Track`, so each instrument's MIDI flows on a single thread), **MIDI threads** (managed by CoreMidi4J), and **worker
threads** (the `Future` execution context).

**Current reality vs. the model.** No dedicated Business or UI thread is started by this module yet. `run`/`runIf`
execute inline on the caller; `call` runs on the global `ExecutionContext`; `publish` dispatches synchronously on the
caller's thread; `runOnUi`/`callOnUi`/`handleOnUi` are unimplemented (`???`). In practice this means the threading
*contracts* are expressed at call sites (e.g. `TuningService.changeTuning` wraps its body in `businessync.run`,
`TrackService.open` uses `businessync.callAsync`) but are not yet *enforced* by `Businessync`. The call sites are
written against the target API so that, once the real dispatcher exists, behavior tightens without touching callers.

## Planned design (issue #90)

Issue [#90 "Add businessync infrastructure"](https://github.com/calinburloiu/microtonalist/issues/90) (milestone
*Architecture*) specifies the full design. Status below is verified against the current source.

- **Async calls on both threads** — `run` (fire-and-forget, `Unit`) and `call` (`Future`-wrapped result), each in a
  Business and a UI variant. *Partially present as method signatures; not backed by real thread dispatch.*
- **Class-keyed pub/sub with base-class matching** — `subscribe`/`subscribeOnUi` keyed by event class, where
  subscribing to a base class catches all subclasses. *Base-class matching already works via Guava `EventBus` +
  `@Subscribe`; the `subscribe`/`subscribeOnUi` methods themselves are stubs.*
- **Pluggable UI-thread submission** — a configurable lambda that runs work on the UI thread. *Type
  (`BusinessyncUiHandler`) exists but is not yet consumed by `Businessync`.*
- **Sequential consistency of UI updates** — *Not implemented.* The plan:
  - The business thread drains a concurrent queue of updates; calls are modeled as special events on that queue.
  - Each UI→business update increments a UI-side **version** (a global counter) attached to the queued update; the
    business thread (the *consumer*) advances its own version as it dequeues, so `businessVersion <= uiVersion`.
  - Events published by the business thread carry its current business version.
  - A UI subscription may **opt in** to sequential consistency; if so, the UI rejects an incoming event whose attached
    business version is strictly less than the UI's own version (i.e. a stale response).
  - For updates handled **asynchronously** on the business thread (e.g. I/O), the business version may advance before
    the async work finishes, which would break consistency. The developer must then **explicitly attach the originating
    business version** when publishing the response event, or use a special max version that the UI always accepts.
  - To force this discipline, publishing an event that has UI subscribers **from an unmanaged thread** (neither UI nor
    business) is intended to throw from `publish`, requiring an explicit version.

Because none of the version/queue/sequential-consistency machinery exists in the code yet, these behaviors are
**planned and subject to change**; this document will be updated when the implementation lands. The numerous
`// TODO #90` markers in `Businessync.scala` and at the `@Subscribe` call sites track that work.

## Dependencies

- **Application dependencies: none.** In `build.sbt`, the `businessync` project has no `.dependsOn(...)`. It is a leaf
  in the application dependency graph, alongside `intonation` and `common`.
- **Third-party dependency:** Guava (`com.google.common.eventbus.EventBus`), declared as `guava` in `build.sbt`, plus
  the common logging stack inherited from `commonDependencies`.
- **Modules that depend on `businessync`** (directly, per `build.sbt`):
  - `scMidi` (`sc-midi`) — publishes `MidiEvent`s from `MidiManager` / `MidiDeviceHandle`.
  - `tuner` — `TuningSession`/`TuningService`, `TrackSession`/`TrackService`, `TrackManager`, `TunerModule` publish and
    subscribe to `TuningEvent`/`TrackEvent`.
  - `app` — `MicrotonalistApp` constructs the `Businessync` instance and wires it into the modules below.
  - `cli` — depends transitively via `scMidi`; `MicrotonalistToolApp` constructs a `Businessync` for `MidiManager`.
  - `format` — receives a `Businessync` (via `FormatModule`) and publishes `ScaleLossyConversionEvent`; the dependency
    is supplied transitively (e.g. through `tuner`/`composition`) rather than as a direct `dependsOn`.
  - `ui` — `TuningListFrame` subscribes to `TuningIndexUpdatedEvent`; reached transitively via `tuner`.

The instance is created once in the application entry point (`MicrotonalistApp.run`,
`app/.../MicrotonalistApp.scala:79`) and injected into `FormatModule`, `TunerModule`, `MidiManager`, etc., so a single
event bus/threading context is shared across the whole running app.
