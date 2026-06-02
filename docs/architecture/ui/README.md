# `ui` module architecture

> **Temporary implementation.** The current GUI is built with **Java Swing**. Architecture-milestone issue
> [#99](https://github.com/calinburloiu/microtonalist/issues/99) plans to migrate the GUI to **JavaFX**, so most of
> what is described here (the Swing frame, the `@Subscribe` event wiring) is expected to be replaced. Treat the Swing
> specifics as transitional; the responsibility and the business-layer contract (`TuningService` / `TuningSession`,
> Businessync events) are the stable parts.

## Responsibility

The `ui` module is the desktop graphical user interface of Microtonalist. It is a thin presentation layer on top of the
`tuner` module: it lets the user see the sequence of tunings derived from the loaded composition and switch the
currently active tuning, while the actual tuning logic and MIDI output live in `tuner`.

Today the module is intentionally small — a single Swing window. It owns no domain state; it reads tuning state from
the business layer and forwards user intent (tuning changes) back to it.

Package: `org.calinburloiu.music.microtonalist.ui`.

## Key types

### `TuningListFrame`

`ui/src/main/scala/org/calinburloiu/music/microtonalist/ui/TuningListFrame.scala:28`

The single Swing window of the application — the "Microtuner" frame opened at startup by the `app` module. It is a
`JFrame` constructed with a `TuningService` and wired as follows:

- **Display.** It renders the tunings as a single-selection `JList[String]` inside a `JScrollPane`
  (`TuningListFrame.scala:34`–`82`). The list model is backed by `tuningService.tunings`, showing each tuning's `name`.
- **Selection → business layer.** A `ListSelectionListener` reacts to user selection and calls
  `tuningService.changeTuning(IndexTuningChange(index))` (`TuningListFrame.scala:43`–`49`), guarding against the
  adjusting/`-1` transient states so only a settled selection triggers a change.
- **Keyboard shortcuts.** A `KeyListener` adds quality-of-life navigation (`TuningListFrame.scala:51`–`80`): `Up`/`Down`
  wrap around at the ends of the list, and number keys `1`–`9` jump to the corresponding tuning index.
- **Frame setup.** Closing the window exits the JVM (`WindowConstants.EXIT_ON_CLOSE`) and the window is sized 480×640
  (`TuningListFrame.scala:84`–`86`).
- **Business → display (event handler).** `onTuningChanged(TuningIndexUpdatedEvent)` (`TuningListFrame.scala:88`–`95`)
  updates the selected list row when the tuning index changes elsewhere (e.g. a pedal-driven `TuningChanger`), keeping
  the GUI in sync with the session.

### Collaborators (in `tuner`)

The frame talks only to these `tuner`-module types; they are the stable contract for the planned JavaFX rewrite:

- `TuningService` (`tuner/.../TuningService.scala:33`) — `@ThreadSafe` façade the frame is constructed with. The frame
  uses two members:
  - `changeTuning(EffectiveTuningChange)` — runs the mutation on the business thread via `businessync.run { … }`; the
    frame supplies `IndexTuningChange(index)` (`PreviousTuningChange` / `NextTuningChange` also exist).
  - `tunings: Seq[Tuning]` — **deprecated and not thread-safe** (`TuningService.scala:41`), kept only until the JavaFX
    migration (`// TODO #99`). The frame still calls it once at construction to seed its list model
    (`TuningListFrame.scala:30`). New UI code should obtain the list from `TuningsUpdatedEvent` instead.
- `TuningSession` (`tuner/.../TuningSession.scala`) — holds the mutable `tunings` sequence and current `tuningIndex`;
  publishes `TuningIndexUpdatedEvent` when the index changes and `TuningsUpdatedEvent` when the sequence changes.
- `TuningIndexUpdatedEvent` / `TuningsUpdatedEvent` (`tuner/.../TuningEvent.scala`) — Businessync events carrying
  `tuningIndex` and `currentTuning`. The frame currently subscribes only to `TuningIndexUpdatedEvent`.
- `IndexTuningChange` (`tuner/.../TuningChange.scala`) — the change command the frame issues on selection.

## UI ↔ business threading boundary

The intended model (see the [`businessync` architecture doc](../businessync/README.md) and the project
[Threading Model](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model) wiki) separates a **business
thread** (all domain/MIDI logic) from a **UI/GUI thread** (all widget updates). Under that model the UI should:

- mutate domain state only through services that hop to the business thread (`businessync.run { … }`), never directly;
- apply widget updates on the UI thread via `businessync.runOnUi { … }`;
- receive events via `businessync.subscribeOnUi(…)` so handlers land on the UI thread.

**Current reality (transitional, tracked by [#90](https://github.com/calinburloiu/microtonalist/issues/90) and #99):**

- `Businessync.runOnUi`, `subscribeOnUi`, `subscribe`, `handleOnUi`, and `callOnUi` are still stubs (`???` / empty
  bodies) — `businessync/.../Businessync.scala:68`,`80`,`105`. `run` simply invokes the function inline
  (`Businessync.scala:92`), and `publish` posts straight to a Guava `EventBus` (`Businessync.scala:36`).
- Because of that, `TuningListFrame` registers with the bus the legacy way: `app` calls
  `businessync.register(tuningListFrame)` (`app/.../MicrotonalistApp.scala:97`), which delegates to
  `eventBus.register(obj)` (`Businessync.scala:83`), and the frame's handler is annotated with Guava's own
  `com.google.common.eventbus.Subscribe` (`TuningListFrame.scala:19`,`89`). The `// TODO #90` on that annotation marks
  it for replacement by `businessync.subscribe` once Businessync is implemented.
- Consequently there is **no real UI-thread marshalling yet**: `TuningIndexUpdatedEvent` is delivered on whatever thread
  calls `publish` (the publisher's thread), and the `JList` is updated from there rather than from the Swing EDT. This
  is a known gap that the Businessync (#90) and JavaFX (#99) work will close. New UI code should be written against the
  intended `run`/`runOnUi`/`subscribeOnUi` API so the migration is mechanical.

## Data flow (this module's slice)

```
User clicks/keys in JList
  → ListSelectionListener
  → tuningService.changeTuning(IndexTuningChange(i))   (business thread, via businessync.run)
  → TuningSession.tuningIndex = i
  → publish TuningIndexUpdatedEvent                     (Businessync / Guava EventBus)
  → TuningListFrame.onTuningChanged(...)                (@Subscribe; today: publisher's thread)
  → listComponent.setSelectedIndex(i)                  (Swing widget update)
```

The same `TuningIndexUpdatedEvent` path also feeds non-UI tuning changes (e.g. `PedalTuningChanger`) back into the
list selection, keeping the displayed selection consistent with the active tuning.

## Dependencies

From `build.sbt` (`lazy val ui`, `build.sbt:110`–`120`):

- **Direct dependency:** `tuner` only (`.dependsOn(tuner)`).
- **Transitive:** `businessync`, `common`, and `sc-midi` arrive via `tuner` (`build.sbt:171`–`176`). The frame uses
  Businessync types through this transitive path; it does not declare a direct `businessync` dependency.
- **External:** Java Swing (JDK `javax.swing`), Guava's `EventBus`/`@Subscribe` (`com.google.common.eventbus`, pulled in
  via the dependency graph), and `scala-logging` (`StrictLogging`).
- `AssemblyPlugin` is disabled for this module (it produces no fat JAR of its own).
- Coverage thresholds are currently `stmt = 0, branch = 0` with `// TODO #182` to raise them toward 80% — appropriate
  for code slated for replacement under #99.

**Depended on by:** `app` (`build.sbt:52`–`63`), which constructs `TuningListFrame`, registers it on the event bus, and
makes it visible (`app/.../MicrotonalistApp.scala:96`–`98`). No other module depends on `ui`.
