# MIDI device lifecycle: connected/disconnected, open/closed, attached/detached

Three pairs of terms describe where a MIDI device stands at any moment, and plain English uses *connect* for all
three. This document fixes the vocabulary: each pair answers a different question, and knowing one tells you nothing
about the other two.

| Pair | Question it answers | Applies to | Owned by |
| ---- | ------------------- | ---------- | -------- |
| **connected** / **disconnected** | Is the device present in the system? | a MIDI device only | `MidiDeviceHandle` (`sc-midi`) |
| **open** / **closed** | Does the application hold the device reserved for I/O? | a MIDI device only | `MidiManager` (`sc-midi`) |
| **attached** / **detached** | Is a receiver wired to a transmitter, so messages flow? | a track input/output: a device **or** another track | `MidiProcessor` (`sc-midi`) |

Use exactly these words in code, ScalaDocs, log messages and documentation. In particular, never say *connected* for
a receiver that was wired up, and never say *attached* for a device the platform reports as present.

## Connected / disconnected

**Connected** means *available to the system*: the platform reports the device, so it can be resolved and opened. It
says nothing about whether Microtonalist is using it.

- A `MidiManager` implementation discovers this on `refresh()` — invoked explicitly and whenever the platform reports
  an environment change — by reconciling the devices it scans against the handles it holds.
- It is the manager, never a consumer, that connects and disconnects a `MidiDeviceHandle`; a consumer only reads
  `isConnected`. `MidiDeviceHandle.info` is defined exactly while the handle is connected, which is why
  `isInputDevice` / `isOutputDevice` / `direction` are unknown (`false` / `None`) while it is not.
- Reported on the [Businessync](businessync/README.md) bus as `MidiDeviceConnectedEvent` /
  `MidiDeviceDisconnectedEvent`, with `MidiDeviceFailedToConnectEvent` / `MidiDeviceFailedToDisconnectEvent` in their
  place on failure.
- Unplugging a cable, a virtual port disappearing, or a device being replugged are all changes of *this* pair.

A handle can exist for a device that is not connected: a track can ask for a device that is not plugged in yet, and
the handle waits for it.

## Open / closed

**Open** means *the application reserved the device for I/O*: Java Sound holds it open, and messages can actually go
out through `handle.receiver` or come in through `handle.transmitter`.

- Requested through `MidiManager.openInput` / `openOutput` and released through `closeInput` / `closeOutput`, which
  return and act on the handle of a `MidiDeviceId`. Attaching an input or output is what triggers the request and
  detaching it what triggers the release — see [How the three relate](#how-the-three-relate).
- **Both are reference-counted.** `openInput` / `openOutput` take one reference; `closeInput` / `closeOutput` release
  one. The device is really closed only when the last reference goes, because several tracks may share one device —
  releasing one of them must not silence the others.
- A request for a device that is not connected does not fail: the handle moves to `WaitingToOpen` and opens by itself
  once the device gets connected.
- Reported as `MidiDeviceOpenedEvent` / `MidiDeviceClosedEvent`, or `MidiDeviceFailedToOpenEvent` /
  `MidiDeviceFailedToCloseEvent`.

The two pairs above are the two axes of `MidiDeviceHandle.State`, whose four cases are every combination of them
(`isConnected` × `isOpenRequested`): `Closed`, `Connected`, `WaitingToOpen` and `Open`. The device is usable only in
`Open`. See the state diagram in the `MidiDeviceHandle.State` ScalaDoc and
[Device handling](sc-midi/README.md#device-handling).

## Attached / detached

**Attached** means *wired into the MIDI graph*: a `MidiReceiver` is among the receivers of a transmitter, so messages
flow to it. Detaching removes it. This is the only pair that also applies to a track input or output that is **not a
device** — a track fed by another track (`FromTrackInputSpec` / `ToTrackOutputSpec`) attaches and detaches like any
other, with nothing to connect or open.

For a `Track` the two directions are wired in opposite senses:

- **Input.** The track's own receiver attaches to the input device's transmitter
  (`handle.transmitter.addReceiver(track.receiver)`).
- **Output.** The output device's receiver attaches to the track pipeline's transmitter
  (`pipeline.transmitter`) — as an initial receiver when the track is built, or later via `addReceiver` for a
  downstream track.

`MidiProcessor` runs an **attach / detach protocol** over every change of its transmitter's receivers, calling
`onDetach(removed)` before the change and `onAttach(added)` after it, with exactly the receivers the change affects,
followed by `onReceiversChanged(all)`. `TunerProcessor` is the main client: it sends the tuner's `reset()` messages
to each receiver that attaches, so an output instrument is initialised when it joins. It today also restores 12-EDO
on each receiver that detaches, which [#305](#subject-to-change-305) moves onto the *close*, where it belongs. Only a
`MidiProcessor`'s transmitter runs this protocol — a `MidiDeviceHandle`'s transmitter is a plain
`ConcurrentMidiTransmitter`, so attaching to a device's input fires no hooks.

## How the three relate

The pairs are not independent. The wiring drives the device requests, and the platform drives the rest:

> **Attaching requests opening; detaching requests closing.**

A track attaches an input or output whether or not its device is connected, and that attach is what asks the
`MidiManager` for the device with `openInput` / `openOutput`; detaching is what asks for its release with
`closeInput` / `closeOutput`. Today `Track` makes the two calls by hand — opening in its constructor, releasing at
the end of `close()` — and [#305](#subject-to-change-305) makes the attach and the detach initiate them.

What this does *not* mean is that a request is its effect:

- **An open request does not necessarily open now.** If the device is not connected, the handle waits in
  `WaitingToOpen` and opens by itself once the device gets connected. This is what lets a track be wired to a device
  that is not plugged in yet, and lets it survive one being unplugged and plugged back in — the wiring keeps
  working, with no re-attach.
- **A close request does not necessarily close.** It releases one reference; the device closes only when the last one
  goes, so another track sharing it keeps it open.
- **Neither ever connects or disconnects anything.** Only the platform decides that pair, and `refresh()` reports it.
- **Disconnecting does not detach.** When a device vanishes, its handle reports *closed* and then *disconnected*
  while every receiver stays attached, ready for the device to come back.

Two situations are therefore errors rather than states to design for:

- **Attached to a `Closed` handle.** Attaching is what takes a handle out of `Closed`, so an input or output that is
  attached is always at least requested to open: `WaitingToOpen` while its device is not connected, `Open` once it
  is. Attaching to a closed device is meaningless, not merely inert.
- **Closed while still attached.** A device must be detached before it is released; closing one a track is still
  attached to should be logged as an error. Nothing checks this today — `Track.close()` simply observes the rule,
  detaching from both devices before it calls `closeInput` / `closeOutput`.

A track must detach on close, and not merely release its devices: a released handle whose device is still connected
stays live, and a track built later for the same device gets that same handle. A closed track left attached would go
on receiving from the input and sending to the output next to its replacement.

It follows that the **courtesy 12-EDO messages belong to the close, not to the detach**: an output another track
still holds open must keep playing in the tuning it is in, so detaching one track from it has to leave it alone.
Today `TunerProcessor.onDetach` sends them on every detach regardless, which is one of the things
[#305](#subject-to-change-305) fixes.

## Subject to change (#305)

[#305](https://github.com/calinburloiu/microtonalist/issues/305), under the *Track Management* milestone, keeps this
vocabulary but rewires what drives what. The notes below are forward-looking; everything above describes the code as
it is today.

- **The attach and the detach initiate the requests themselves.** The pairing is already the rule (see
  [How the three relate](#how-the-three-relate)), but `Track` implements it by hand today, opening in its
  constructor and releasing at the end of `close()`; it will no longer open and attach as two separate steps.
- **Reactions key off what happened to the device, not off the wiring alone:**
    - An input that gets **attached or connected** needs no handling.
    - An input that gets **detached or disconnected** is released, the two cases handled identically: All Notes Off
      to every output, then the `Tuner` and the `TuningChanger` are reset.
    - An output that gets **attached while open** triggers a reset, so it learns the current configuration and the
      current tuning.
    - An output that gets **detached and thereby becomes closed** triggers a reset *and* gets the courtesy 12-EDO
      messages. An output that stays open because another track still holds it gets neither.
- **`Tuner` changes shape.** `tune` and `reset` become `final` on the trait, delegating to new `onTune` / `onReset`
  hooks. `reset` re-states the *current* tuning and the *current* configuration instead of reverting to 12-EDO and
  to the configuration passed to the constructor — so a newly attached output learns where things actually stand —
  while still clearing the internal note state. Rendering the 12-EDO courtesy messages becomes a separate read-only
  method that reads the configuration but mutates nothing.

## Related documents

- [`sc-midi/README.md`](sc-midi/README.md) — `MidiManager`, `MidiDeviceHandle`, the `MidiEvent` family, and the MIDI
  plumbing (`MidiProcessor`, transmitters, receivers).
- [`tuner/README.md`](tuner/README.md) — `Track`, `TrackManager`, `TunerProcessor`, and how tracks react to device
  changes.
