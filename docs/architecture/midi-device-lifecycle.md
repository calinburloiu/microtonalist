# MIDI device lifecycle: available/unavailable, open/closed, attached/detached

Three pairs of terms describe where a MIDI device stands at any moment, and plain English uses *connect* for all
three — which is why none of them is called that. This document fixes the vocabulary: each pair answers a different
question, and knowing one tells you nothing about the other two.

| Pair | Question it answers | Applies to | Owned by |
| ---- | ------------------- | ---------- | -------- |
| **available** / **unavailable** | Is the device present in the system? | a MIDI device only | `MidiDeviceHandle` (`sc-midi`) |
| **open** / **closed** | Does the application hold the device reserved for I/O? | a MIDI device only | `MidiManager` (`sc-midi`) |
| **attached** / **detached** | Is a receiver wired to a transmitter, so messages flow? | a track input/output: a device **or** another track | `MidiProcessor` (`sc-midi`) |

Use exactly these words in code, ScalaDocs, log messages and documentation, and avoid *connect* / *disconnect*
altogether for all three: *connected* is not distinguishable from *open* without reading this document, and it reads
just as naturally for a receiver that was wired up. Likewise, never say *attached* for a device the platform reports
as present.

## Available / unavailable

**Available** means *present in the system*: the platform reports the device, so it can be resolved and opened. It
says nothing about whether Microtonalist is using it.

- A `MidiManager` implementation discovers this on `refresh()` — invoked explicitly and whenever the platform reports
  an environment change — by reconciling the devices it scans against the handles it holds.
- It is the manager, never a consumer, that makes a `MidiDeviceHandle` available or unavailable (`becomeAvailable` /
  `becomeUnavailable`); a consumer only reads `isAvailable`. `MidiDeviceHandle.info` is defined exactly while the
  handle is available, which is why `isInputDevice` / `isOutputDevice` / `direction` are unknown (`false` / `None`)
  while it is not.
- Reported on the [Businessync](businessync/README.md) bus as `MidiDeviceAvailableEvent` /
  `MidiDeviceUnavailableEvent`, with `MidiDeviceFailedToBecomeAvailableEvent` /
  `MidiDeviceFailedToBecomeUnavailableEvent` in their place on failure.
- Unplugging a cable, a virtual port disappearing, or a device being replugged are all changes of *this* pair.

A handle can exist for a device that is not available: a track can ask for a device that is not plugged in yet, and
the handle waits for it.

## Open / closed

**Open** means *the application reserved the device for I/O*: Java Sound holds it open, and messages can actually go
out through `handle.receiver` or come in through `handle.transmitter`.

- Requested through `MidiManager.openDevice` and released through `closeDevice`, which return and act on the handle
  of a `MidiDeviceId` for the use their `MidiDirection` requests. A track requests what it attaches and releases what
  it detaches — today by making both calls itself — see [How the three relate](#how-the-three-relate).
- **Both are reference-counted.** `openDevice` takes one reference; `closeDevice` releases one. The device is really
  closed only when the last reference goes, because several tracks may share one device — releasing one of them must
  not silence the others.
- A request for a device that is not available does not fail: the handle moves to `WaitingToOpen` and opens by itself
  once the device becomes available.
- Reported as `MidiDeviceOpenedEvent` / `MidiDeviceClosedEvent`, or `MidiDeviceFailedToOpenEvent` /
  `MidiDeviceFailedToCloseEvent`.

The two pairs above are the two axes of `MidiDeviceHandle.State`, whose four cases are every combination of them
(`isAvailable` × `isOpenRequested`): `Closed`, `Available`, `WaitingToOpen` and `Open`. The device is usable only in
`Open`. See the state diagram in the `MidiDeviceHandle.State` ScalaDoc and
[Device handling](sc-midi/README.md#device-handling).

## Attached / detached

**Attached** means *wired into the MIDI graph*: a `MidiReceiver` is among the receivers of a transmitter, so messages
flow to it. Detaching removes it. This is the only pair that also applies to a track input or output that is **not a
device** — a track fed by another track (`FromTrackInputSpec` / `ToTrackOutputSpec`) attaches and detaches like any
other, with nothing to make available or open.

For a `Track` the two directions are wired in opposite senses:

- **Input.** The track's own receiver attaches to the input device's transmitter
  (`handle.transmitter.addReceiver(track.receiver)`).
- **Output.** The output device's receiver attaches to the track pipeline's transmitter
  (`pipeline.transmitter`) — as an initial receiver when the track is built, or later via `addReceiver` for a
  downstream track.

`MidiProcessor` runs an **attach / detach protocol** over every change of its transmitter's receivers, calling
`onDetach(removed)` before the change and `onAttach(added)` after it, with exactly the receivers the change affects,
followed by `onReceiversChanged(all)`. `TunerProcessor` is the main client: it sends the tuner's `reset()` messages
to each receiver that attaches, so an output instrument is initialised, and tuned to the tuner's current tuning, when
it joins. It today also restores 12-EDO on each receiver that detaches, which [#305](#subject-to-change-305) moves onto
the *close*, where it belongs. Only a `MidiProcessor`'s transmitter runs this protocol — a `MidiDeviceHandle`'s
transmitter is a plain `ConcurrentMidiTransmitter`, so attaching to a device's input fires no hooks.

## How the three relate

The pairs are not independent. The wiring drives the device requests, and the platform drives the rest:

> **What a track attaches, it requests open; what it detaches, it releases.**

A track attaches an input or output whether or not its device is available, and that attach is what asks the
`MidiManager` for the device with `openDevice`; detaching is what asks for its release with `closeDevice`. Today
`Track` makes the two calls by hand — opening in its constructor, releasing at the end of `close()` — and
[#305](#subject-to-change-305) makes the attach and the detach initiate them.

What this does *not* mean is that a request is its effect:

- **An open request does not necessarily open now.** If the device is not available, the handle waits in
  `WaitingToOpen` and opens by itself once the device becomes available. This is what lets a track be wired to a device
  that is not plugged in yet, and lets it survive one being unplugged and plugged back in — the wiring keeps
  working, with no re-attach.
- **A close request does not necessarily close.** It releases one reference; the device closes only when the last one
  goes, so another track sharing it keeps it open.
- **Neither ever makes anything available or unavailable.** Only the platform decides that pair, and `refresh()`
  reports it.
- **Becoming unavailable does not detach.** When a device vanishes, its handle reports *closed* and then *unavailable*
  while every receiver stays attached, ready for the device to come back.

Two situations are therefore errors rather than states to design for:

- **Attached to a `Closed` handle.** A track requests its handle open, so an input or output it attached is always
  at least requested to open: `WaitingToOpen` while its device is not available, `Open` once it is. The wiring itself
  is inert on a handle that is not open — `MidiDeviceHandle` supports it and simply carries nothing — so this is a
  sign of a wiring bug rather than an error the handle rejects.
- **Closed while still attached.** A device must be detached before it is released; closing one a track is still
  attached to should be logged as an error. Nothing checks this today — `Track.close()` simply observes the rule,
  detaching from both devices before it calls `closeDevice` for each.

A track must detach on close, and not merely release its devices: a released handle whose device is still available
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
    - An input that gets **attached, or becomes available**, needs no handling.
    - An input that gets **detached, or becomes unavailable**, is released, the two cases handled identically: All
      Notes Off to every output, then the `Tuner` and the `TuningChanger` are reset.
    - An output that gets **attached while open** triggers a reset, so it learns the current configuration and the
      current tuning.
    - An output that gets **detached and thereby becomes closed** triggers a reset *and* gets the courtesy 12-EDO
      messages. An output that stays open because another track still holds it gets neither.
- **`Tuner` keeps changing shape.** `tune` and `reset` are already `final` on the trait, delegating to the `onTune` /
  `onReset` hooks, and `reset` already re-states the *current* tuning (#322). It will also re-state the *current*
  configuration instead of reverting to the configuration passed to the constructor — so a newly attached output
  learns where things actually stand — while still clearing the internal note state. Rendering the 12-EDO courtesy
  messages becomes a separate read-only method that reads the configuration but mutates nothing: today they come
  from `tune(Tuning.Standard)`, which also makes 12-EDO the tuner's current tuning.

## Related documents

- [`sc-midi/README.md`](sc-midi/README.md) — `MidiManager`, `MidiDeviceHandle`, the `MidiEvent` family, and the MIDI
  plumbing (`MidiProcessor`, transmitters, receivers).
- [`tuner/README.md`](tuner/README.md) — `Track`, `TrackManager`, `TunerProcessor`, and how tracks react to device
  changes.
