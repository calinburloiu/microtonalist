# Hot plugging MIDI devices — design

- **Date:** 2026-09-15
- **Base commit:** `bf7fa10879af54e2e0b8980c267e0b5d74cd3c2a` (branch `refactoring/sc-midi-javamidi-tests`)
- **Branch:** `feature/hot-plug-midi-devices`, stacked on `refactoring/sc-midi-javamidi-tests`
- **Issues resolved:** #131 (Allow hot plugging MIDI devices) and #288 (MidiManager orphans an open MidiDeviceHandle
  when its device is unplugged). They are separate issues, not parent and sub-issue.
- **Follow-ups created:** #302 (react to MIDI device failures before the environment reports them) and #303 (restore
  the current tuning when a track's tuner is reset).

## 1. Problem

#131 wants the MIDI devices named in a tracks file to work whether or not they are connected when the tracks are
built: a device plugged in later is picked up, and a device unplugged and plugged back in resumes working, without
rebuilding the tracks. `MidiManager` and `MidiDeviceHandle` already document that contract, but `JavaMidiManager` does
not implement it (#288):

- `refresh()` never hands a newly connected device to a handle already requested for it, and never tells a handle that
  its device got disconnected; `purgeDisconnectedDevices` closes the Java device behind the handle's back and drops the
  handle, so a replugged device gets a new handle while the `Track` keeps the dead one.
- `openDevice` on a disconnected device does not open the handle, so the `Closed → WaitingToOpen → Open` path is
  unreachable.
- `openDevice` on an already open handle calls `onConnect`, which closes the Java device while the handle keeps
  reporting `State.Open`.
- `closeDevice` releases a single reference and then drops the handle whatever its reference count, and ignores a
  handle that is not open.
- `state`, `isOpen` and `isConnected` contradict each other after an unplug, a failed open or a failed close.

Designing the fix turned up two more gaps:

- `Track.close()` calls `handle.close()` directly and never `MidiManager.closeInput` / `closeOutput`, so the manager's
  close path is unused in production and the manager cannot keep its bookkeeping right.
- Even with handles that reconnect, nothing re-initialises an output instrument that (re)opens after its track was
  built: `TunerProcessor` sends `Tuner.reset()` only when a receiver is added to its transmitter, and the device's
  receiver never changes. Likewise, nothing releases the notes held on the output when an input device disappears
  mid-performance.

## 2. Goals and non-goals

**Goals**

- Handles survive disconnection and reconnect to the device they stand for; `state`, `isConnected` and `isOpen` agree
  at all times.
- Only the `MidiManager` changes a handle's state; consumers inspect it and use its `receiver` / `transmitter`.
- Reference counting works end to end, through the manager.
- `MidiEvent`s describe every transition, with the direction of the endpoint they concern.
- `tuner` resets the tuner of a track whose output device opens after the track was built, and releases the output of
  a track whose input device gets disconnected.

**Non-goals** (see [Section 8](#8-out-of-scope-and-follow-ups))

- Detecting a vanished device from a failed send before CoreMIDI4J reports it, and recovering a handle left unusable by
  a failed open (#302).
- Restoring the current tuning after a tuner reset (#303).
- Running the event handlers on the business thread (#90) and driving tracks on their own threads (#121).

## 3. `sc-midi` API contract

### 3.1 `MidiDeviceHandle` is read-only

- `open()`, `close()` and `AutoCloseable` are removed from the trait. Consumers can only inspect a handle (`id`,
  `info`, `isInputDevice`, `isOutputDevice`, `endpointType`, `state`, `isConnected`, `isOpen`, `isOpenRequested`) and
  use it for MIDI I/O (`receiver`, `transmitter`).
- `isConnected` and `isOpen` become concrete members of the trait, derived from `state`: `state.isConnected` and
  `state == State.Open`. They can no longer disagree with `state` (today `JavaMidiDeviceHandle.isOpen` reads through to
  the Java device).
- A handle is **live** while its manager holds it, which is exactly while its state is not `Closed`. A handle that
  reaches `Closed` is forgotten by the manager and stays `Closed` for good; a later `openInput` / `openOutput` of the
  same id returns a new instance. A consumer must not keep a handle after it released its references to it.

### 3.2 `MidiManager` semantics

Mirrored for inputs and outputs; the input half is described.

- `openInput(id)` takes one reference to the device and returns its live handle, creating one if there is none. The
  handle is `Open` if the device is connected and opens successfully, `WaitingToOpen` if the device is not connected,
  and `Connected` if the device failed to open (see [Section 4.4](#44-transitions-are-transactional)).
- `closeInput(id)` releases one reference. It does nothing when there is no live handle for `id` requested to open.
  When the last reference is released, the handle moves to `Connected`, or to `Closed` (and is forgotten) if its
  device is not connected.
- `inputDeviceHandleOf(id)` returns the live handle for `id`: requested to open, or connected, or both. Since the
  manager creates a handle for every connected device (Section 4.1), a connected device nobody opened has a handle in
  `Connected`.
- `inputOpenedDevices` returns the live handles with `isOpenRequested`.
- `isInputAvailable`, `inputDeviceInfoOf`, `inputDeviceIds` and `inputDevicesInfo` derive from the live handles that
  are connected.
- `close()` releases every reference held through the manager, so every device the manager opened ends up closed, and
  stops watching the environment.

### 3.3 `MidiEvent`

- The seven device transition events — `MidiDeviceConnectedEvent`, `MidiDeviceDisconnectedEvent`,
  `MidiDeviceOpenedEvent`, `MidiDeviceClosedEvent`, `MidiDeviceFailedToDisconnectEvent`, `MidiDeviceFailedToOpenEvent`
  and `MidiDeviceFailedToCloseEvent` — gain an `endpointType: MidiEndpointType` field after `deviceId`. It is always `MidiEndpointType.Input` or
  `MidiEndpointType.Output`: the endpoint of the manager whose handle made the transition. The existing enum is reused
  rather than adding a two-case one; the restriction is documented on the field. A device working in both directions
  is reported once per direction, by two events that now differ.
- `MidiDeviceFailedToConnectEvent` keeps no direction: it is published when resolving a device fails, before its
  direction is known.
- Each event reports one transition of one handle, and a failure event replaces its success event:
  - `Connected` / `Disconnected` on `connect` / `disconnect`; `FailedToDisconnect` instead of `Disconnected` when
    releasing the device fails — the handle ends up disconnected either way.
  - `Opened` on every entry into `Open`, including a `WaitingToOpen` handle whose device gets connected;
    `FailedToOpen` instead when opening fails.
  - `Closed` on every exit from `Open`, whether by releasing the last reference or by a disconnection or device swap;
    `FailedToClose` instead when closing the device fails on release or on a swap.
  - An open handle whose device gets unplugged publishes `Closed`, then `Disconnected` (or only `FailedToDisconnect`
    if releasing the device fails).
- The ScalaDoc of `MidiEvent` no longer says a subscriber must ask the manager for the direction.

## 4. Java Sound implementation (`javamidi`)

### 4.1 One registry of live handles per endpoint

- Each `MidiEndpoint` holds a single map `MidiDeviceId → JavaMidiDeviceHandle` containing exactly its live handles. It
  replaces both `connectedDevices` and `openedDevicesMap` (this is the first bullet of #131, now that handles are kept
  for every connected device).
- `JavaMidiDeviceHandle` is created with the direction of the endpoint that owns it (`MidiEndpointType.Input` or
  `Output`), which its events carry as `endpointType`. It holds the resolved `MidiDevice` and its `MidiDeviceInfo`
  while connected.
- A manager-wide `ReentrantLock` (through `Locking`) serialises `refresh()`, `openInput` / `openOutput`,
  `closeInput` / `closeOutput`, `close()` and the registry reads. The lock order is always manager, then handle. This
  also removes the race #288 describes in `purgeDisconnectedDevices`.
- Resolving devices through the `JavaMidiEnvironment` happens before taking the lock; only the reconciliation runs
  under it.
- Sending MIDI never takes either lock: the handle's receiver keeps reading a volatile Java `Receiver`.

### 4.2 `refresh()` reconciliation

`refresh()` resolves every device once, as today, groups the resolved devices per endpoint by id — the device resolved
last for an id wins, as today — and reconciles each endpoint, inputs first:

| Resolved now? | Live handle | Action | Events |
|---|---|---|---|
| yes | none | Create a handle and `connect` it → `Connected`. | `Connected` |
| yes | `WaitingToOpen` | `connect` → opens the device → `Open`; on failure → `Connected`. | `Connected`, `Opened` or `FailedToOpen` |
| yes, same `MidiDevice` instance | `Connected` / `Open` | Nothing (the info is updated silently). | — |
| yes, another instance | `Connected` | Swap the device silently. | — |
| yes, another instance | `Open` | Close the old device, open the new one. | `Closed` or `FailedToClose`, then `Opened` or `FailedToOpen` |
| no | `Open` | `disconnect` → `WaitingToOpen`. | `Closed`, `Disconnected` (or `FailedToDisconnect`) |
| no | `Connected` | `disconnect` → `Closed`; forget the handle. | `Disconnected` (or `FailedToDisconnect`) |
| no | `WaitingToOpen` | Nothing. | — |

Why instance identity: CoreMIDI4J keeps one `MidiDevice` instance per endpoint for as long as the endpoint stays
present, closes a vanished device itself (before the manager's listener runs) and creates a new instance when an
endpoint reappears. So a different instance under an id that stays present means the device was replugged or swapped
between two refreshes. The swap of an open handle publishes `Closed` / `Opened` but not `Disconnected` / `Connected`,
which keeps the current rule that only the first refresh that sees an id reports it connected, while the `Opened`
event still lets `tuner` reset the tuner of the swapped instrument.

### 4.3 Open, close and the handle's commands

- `JavaMidiDeviceHandle` keeps `open()` and `close()` and renames `onConnect` / `onDisconnect` to `connect(info,
  device)` / `disconnect()`, matching the labels of the `State` diagram. All four are `private[javamidi]` commands,
  called only by the manager. A fifth `private[javamidi]` command, `closeAll()`, releases every reference held, for
  `JavaMidiManager.close()`.
- `openDevice(id)`: get or create the live handle, `open()` it, register it. If it ends up `WaitingToOpen`, the manager
  logs that the device is not connected and will be opened once it gets connected.
- `closeDevice(id)`: if the live handle is requested to open, `close()` it; forget it if it is now `Closed`.
- `close()`: release every reference of every handle, forget the handles that are now `Closed`, publish, then close
  the environment subscription.
- `open()` and `close()` keep their reference counting: only the first `open()` and the last `close()` make a
  transition. `close()` with no reference held does nothing.
- `connect(info, device)` still rejects an info that does not correspond to the handle's id with
  `IllegalArgumentException`, leaving the handle unchanged.
- `disconnect()` closes the device whether or not the handle opened it (closing a Java Sound device that is not open,
  or that CoreMIDI4J already closed, is harmless) and clears the device, its info and the obtained Java `Receiver`.

### 4.4 Transitions are transactional

Following the failure rule of the `MidiDeviceHandle.State` ScalaDoc (a failed transition sets to false the property it
concerns):

- **Open.** `doOpen` opens the device, obtains the Java `Receiver` of an output device and subscribes to the
  transmitter of an input device, and only then sets `State.Open`. On any exception it closes the device as far as it
  can, clears the obtained receiver, sets `State.Connected`, resets the reference count to 0 (no longer requested to
  open) and reports `FailedToOpen`. A handle that gets its device back — on `connect` or on a swap — always goes
  through `doOpen`, which obtains a new receiver, since closing a Java Sound device closes all of its receivers.
  A track holding a handle whose device failed to open keeps an unusable handle until the tracks are rebuilt; track
  management is meant to act on `MidiDeviceFailedToOpenEvent` (#302).
- **Close.** When closing the device throws on the last `close()`, the handle still moves to `Connected` and reports
  `FailedToClose`.
- **Disconnect.** When closing the device throws on `disconnect()`, the handle still ends up disconnected
  (`WaitingToOpen` or `Closed`) and reports `FailedToDisconnect`.

### 4.5 Events are published after the locks are released

A handle's commands no longer publish: each returns the `MidiEvent`s its transition produced, in order. The manager
collects the events of an operation — including `MidiDeviceFailedToConnectEvent` from resolution and
`MidiEnvironmentChangedEvent` — and publishes them in order after releasing its lock. Guava's `EventBus` delivers
events synchronously on the publishing thread, and `TrackManager`'s handlers send MIDI, so a handler must never run
inside the manager's or a handle's lock.

Logging stays where each transition happens; the log messages whose wording or place changes have their tests updated
accordingly (e.g. "warn that a device to open is not connected").

### 4.6 A send that hits a closed Java receiver

When a device vanishes, a message can reach a Java `Receiver` that Java Sound already closed, which throws
`IllegalStateException` before CoreMIDI4J reports the change. The handle's receiver catches it and drops the message,
logging a warning for the first dropped message after each open and at debug level afterwards, so that a dense stream
does not flood the log. The catch carries `// TODO #302`, the issue that replaces it with a flow informing the manager.
Unplugging a device mid-performance therefore no longer throws `TunerException` on the input device's thread.

## 5. `tuner`

### 5.1 `TunerProcessor.reset()` and `TuningChangeProcessor.reset()`

- `TunerProcessor` gains a public `reset()`: it calls `tuner.reset()` and sends the returned messages to every receiver
  of its transmitter, as `onConnect` does for newly connected receivers.
- `TuningChangeProcessor` gains a public `reset()` that calls `reset()` on each of its `TuningChanger`s.

### 5.2 `Track`

- `close()` switches back to 12-EDO as today, then releases its devices through `midiManager.closeInput(id)` for a
  `DeviceTrackInputSpec` and `midiManager.closeOutput(id)` for a `DeviceTrackOutputSpec`, instead of closing the
  handles.
- `resetTuner()` calls `reset()` on its `TunerProcessor`, if any. It does not restore the current tuning; until #303,
  the output instrument plays in 12-EDO until the next tuning change.
- `releaseInput()`:
  1. sends `AllNotesOffMidiMsg` on each of the 16 channels (0–15) straight to the receivers of the track's output
     (the pipeline transmitter), bypassing the tuner, so that it reaches every channel the tuner may have used, such as
     MPE Member Channels, which `MpeTuner` would discard at its input;
  2. resets the track's `TuningChangeProcessor`, if any, so that a trigger pedal held when the input disappeared does
     not swallow the first press after it comes back;
  3. calls `resetTuner()`; tuners such as `MpeTuner` clear their note state in `reset()`.

The track keeps no state of its own about held notes.

### 5.3 `TrackManager`

- `replaceAllTracks` clears `tracks` after closing the old tracks and before building the new ones, so that an event
  published while the new tracks open their devices never reaches a closed track. A device already connected when its
  track is built needs no reset from an event: the pipeline's initial connection of the device receiver already sends
  the tuner's `reset()` messages.
- A `@Subscribe` handler for `MidiEvent` (Guava dispatches subtypes to it) reacts to:
  - `MidiDeviceOpenedEvent(id, Output)`: `resetTuner()` on every track whose output is `DeviceTrackOutputSpec` with
    `midiDeviceId == id`;
  - `MidiDeviceDisconnectedEvent(id, Input)` and `MidiDeviceFailedToDisconnectEvent(id, Input, _)`: `releaseInput()`
    on every track whose input is `DeviceTrackInputSpec` with `midiDeviceId == id`;
  - anything else: nothing.
- `TrackManager` stores no tuning.
- The handler carries `// TODO #90`, like `onTuningChanged`, noting that Guava runs it on the publishing thread —
  today CoreMIDI4J's notification thread — while `TrackManager` is meant to be used on the business thread only. The
  caveat is documented in TODOs only, not in ScalaDoc. The reset calls carry `// TODO #303`.
- `TunerModule` already registers the `TrackManager` on the bus; `app` needs no change.

## 6. Testing

Strict TDD: every behaviour below starts as a failing test. The tests that #301 pinned under `ignore` with
`TODO #288` / `TODO #131` are the first red tests; they are updated for the renamed commands and for events now
published by the handle's transitions (e.g. an open handle's disconnection now reports `Closed` and `Disconnected`).

**`sc-midi`**

- `MidiDeviceHandleTest`: the test double loses `open` / `close`; `isConnected` and `isOpen` follow `state` for every
  state.
- `JavaMidiDeviceHandleTest`:
  - `connect` / `disconnect` for every row of the `State` diagram, with the events and `endpointType` they report;
  - a failed open and a failed receiver roll back to `Connected` with the device closed and no reference held, so a
    later `close()` does nothing;
  - `connect` with the same instance changes nothing; with another instance, a connected handle swaps silently and an
    open handle closes the old device and opens the new one, obtaining a new receiver;
  - a failed close on release still leaves `Connected`; a failed close on disconnect still leaves the handle
    disconnected;
  - a send that hits a closed Java receiver is dropped, warning once per open.
- `JavaMidiManagerTest` (both directions through its shared behaviours):
  - a connected device nobody opened has a live handle in `Connected`; an unplugged one is forgotten;
  - opening a disconnected device returns a `WaitingToOpen` handle that opens when the device gets connected;
  - an open device that gets unplugged keeps its handle `WaitingToOpen`, and reopens with the device it gets when
    replugged;
  - two opens need two closes; a handle is forgotten once `Closed`; `…OpenedDevices` lists only handles requested to
    open; `close()` releases every reference and closes every device;
  - the exact event sequences, with directions, for plug, unplug, replug and swap; a device working in both
    directions reports one event per direction;
  - events are published in order after the operation.

**`tuner`**

- `TunerProcessorTest`: `reset()` sends the tuner's reset messages to every receiver.
- `TuningChangeProcessorTest`: `reset()` resets every changer.
- `TrackTest`: `close()` releases the devices through `closeInput` / `closeOutput`; `resetTuner()` sends the tuner's
  reset messages to the output; `releaseInput()` sends All Notes Off on the 16 channels to the output, then resets the
  changers and the tuner.
- New `TrackManagerTest`, publishing through a real `Businessync` over a Guava `EventBus` with the manager registered,
  over a stubbed `MidiManager`: an output `Opened` resets only the tracks on that device; an input `Opened` is ignored;
  an input `Disconnected` or `FailedToDisconnect` releases the matching tracks; an event published while
  `replaceAllTracks` builds tracks never reaches a closed track.

**Final checks:** the tests of every modified module, coverage through the `scoverage-inspector` skill (keeping the
`sc-midi` floor of 80% and every other module's floor), and the full suite.

## 7. Documentation and TODOs

- ScalaDocs of every changed public identifier: `MidiDeviceHandle` (including the `State` ScalaDoc where it mentions
  `open` / `close`), `MidiManager`, `MidiEvent` and its device events, `JavaMidiManager`, `JavaMidiDeviceHandle`,
  `Track`, `TrackManager`, `TunerProcessor`, `TuningChangeProcessor`.
- `docs/architecture/sc-midi/README.md`: the device handling section (read-only handles, live handles, the registry,
  the lock), the "How MIDI devices are opened, enumerated, and used" steps (closing through the manager), the device
  lifecycle and events section (direction, one event per transition, `TrackManager` as the first subscriber), and the
  removal of the #288 note under "Notes / subject to change".
- `docs/architecture/tuner/README.md`: how tracks react to their devices opening and disconnecting, and the new
  subject-to-change items (#90 for the handler's thread, #303 for the tuning after a reset).
- Remove every `TODO #288` and `TODO #131`. Add `TODO #302` at the dropped send, `TODO #90` at the `MidiEvent` handler
  and `TODO #303` where tuners are reset on device events.

## 8. Out of scope and follow-ups

- #302: the manager learning about a failed send before CoreMIDI4J reports the change, and track management handling
  a handle left unusable by `MidiDeviceFailedToOpenEvent`.
- #303: restoring the current tuning after a track's tuner is reset.
- #90: delivering events on the business thread; #121: running tracks on their own threads.

## 9. Delivery

- Work on `feature/hot-plug-midi-devices`, stacked on `refactoring/sc-midi-javamidi-tests`; nothing is pushed.
- Commit messages are prefixed with `[#131][#288]`, showing two separate issues.
- The implementation plan follows this design in the same directory.
