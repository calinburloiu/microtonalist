# `ui` module architecture

> **Temporary implementation.** The current GUI is built with **Java Swing**. Architecture-milestone issue
> [#99](https://github.com/calinburloiu/microtonalist/issues/99) plans to migrate it to **JavaFX**, so most of what is
> described here (the Swing frame, the `@Subscribe` event wiring) is expected to be replaced. The stable parts are the
> responsibility and the business-layer contract (`TuningService` / `TuningSession`, Businessync events).

## Responsibility

The `ui` module is the desktop graphical user interface of Microtonalist — a thin presentation layer on top of `tuner`.
It lets the user see the sequence of tunings derived from the loaded composition and switch the active tuning, while the
actual tuning logic and MIDI output live in `tuner`. It owns no domain state: it reads tuning state from the business
layer and forwards user intent (tuning changes) back to it. Today it is intentionally small — a single Swing window.

Package: `org.calinburloiu.music.microtonalist.ui`.

## Key types

### `TuningListFrame`

The single Swing window — the "Microtuner" frame opened at startup by `app`. It is a `JFrame` constructed with a
`TuningService` and wired four ways:

- **Display.** It renders the tunings as a single-selection `JList[String]`, its model seeded from
  `tuningService.tunings` and showing each tuning's `name`.
- **Selection → business layer.** A `ListSelectionListener` reacts to a settled selection (ignoring the adjusting/`-1`
  transient states) and calls `tuningService.changeTuning(IndexTuningChange(index))`.
- **Keyboard shortcuts.** A `KeyListener` adds navigation: `Up`/`Down` wrap around at the ends, number keys `1`–`9` jump
  to the corresponding tuning.
- **Business → display.** An event handler subscribed to `TuningIndexUpdatedEvent` updates the selected row when the
  tuning index changes elsewhere (e.g. a pedal-driven `TuningChanger`), keeping the GUI in sync with the session.

### Collaborators (in `tuner`)

The frame talks only to these `tuner`-module types — the stable contract for the planned JavaFX rewrite:

- **`TuningService`** — the `@ThreadSafe` façade the frame is constructed with. It uses `changeTuning` (runs the
  mutation on the business thread) and the **deprecated, not-thread-safe** `tunings` accessor, called once at
  construction to seed the list model (kept only until the JavaFX migration, `// TODO #99`; new UI code should obtain
  the list from `TuningsUpdatedEvent` instead).
- **`TuningSession`** — holds the mutable `tunings` and current `tuningIndex`, publishing `TuningIndexUpdatedEvent` /
  `TuningsUpdatedEvent`.
- **`TuningIndexUpdatedEvent`** / **`TuningsUpdatedEvent`** and **`IndexTuningChange`** — the events the frame observes
  and the change command it issues on selection.

## UI ↔ business threading boundary

The intended model (see the [`businessync` doc](../businessync/README.md) and the [Threading
Model](https://github.com/calinburloiu/microtonalist/wiki/Threading-Model) wiki) separates a **business thread** (all
domain/MIDI logic) from a **UI thread** (all widget updates): the UI should mutate domain state only through services
that hop to the business thread, apply widget updates via `runOnUi`, and receive events via `subscribeOnUi`.

**Current reality (transitional, tracked by [#90](https://github.com/calinburloiu/microtonalist/issues/90) and #99):**
the UI-thread methods on `Businessync` are still stubs, and `run` runs inline. So `TuningListFrame` registers with the
bus the legacy way — `app` calls `businessync.register(frame)` and the handler is annotated with Guava's own
`@Subscribe` (marked `// TODO #90` for replacement by `businessync.subscribe`). Consequently there is **no real
UI-thread marshalling yet**: `TuningIndexUpdatedEvent` is delivered on the publisher's thread and the `JList` is updated
from there rather than the Swing EDT — a known gap that the Businessync (#90) and JavaFX (#99) work will close. New UI
code should be written against the intended `run`/`runOnUi`/`subscribeOnUi` API so the migration is mechanical.

## Data flow (this module's slice)

```
User clicks/keys in JList
  → ListSelectionListener
  → tuningService.changeTuning(IndexTuningChange(i))   (business thread, via businessync.run)
  → TuningSession.tuningIndex = i
  → publish TuningIndexUpdatedEvent                     (Businessync / Guava EventBus)
  → TuningListFrame event handler                       (@Subscribe; today: publisher's thread)
  → listComponent.setSelectedIndex(i)                  (Swing widget update)
```

The same path also feeds non-UI tuning changes (e.g. `PedalTuningChanger`) back into the list selection, keeping the
displayed selection consistent with the active tuning.

## Dependencies

Its only direct dependency is `tuner`; `businessync`, `common`, and `sc-midi` arrive transitively through it (the frame
uses Businessync types via that path). Externally it uses Java Swing and Guava's `EventBus`/`@Subscribe`. Coverage
thresholds are currently 0 with `// TODO #182` to raise them — appropriate for code slated for replacement under #99.

**Depended on by** `app`, which constructs the frame, registers it on the bus, and shows it. No other module depends on
`ui`.
