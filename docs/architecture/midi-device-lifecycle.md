# MIDI device lifecycle: connected, open, attached

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
  `isInputDevice` / `isOutputDevice` / `endpointType` are unknown (`false` / `None`) while it is not.
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
  return and act on the handle of a `MidiDeviceId`.
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
to each receiver that attaches, and restores 12-EDO on each receiver that detaches, so an output instrument is
initialised when it joins and left in a consistent state when it leaves. Only a `MidiProcessor`'s transmitter runs
this protocol — a `MidiDeviceHandle`'s transmitter is a plain `ConcurrentMidiTransmitter`, so attaching to a device's
input fires no hooks.

## How the three relate

Attaching and detaching are what a track does with its own wiring; opening and closing are what it asks the
`MidiManager` to do on its behalf. A `Track` pairs them:

1. **Built.** It takes a reference with `openInput` / `openOutput` — an *open request*, which opens the device now or
   as soon as it gets connected — and then attaches: its receiver to the input device's transmitter, the output
   device's receiver to its pipeline.
2. **Closed.** It detaches from both — the output device receiver leaving the pipeline is what makes `TunerProcessor`
   send it the 12-EDO messages — and only then issues the *close request* with `closeInput` / `closeOutput`. The
   device is closed only if that released the last reference; another track sharing it keeps it open.

A track must detach on close, and not merely release its devices: a released handle whose device is still connected
stays live, and a track built later for the same device gets that same handle. A closed track left attached would go
on receiving from the input and sending to the output next to its replacement.

The three pairs stay independent throughout:

- **Attaching does not open.** A receiver may be attached to a handle that is disconnected or closed; it simply gets
  nothing, and starts working without re-wiring once the device is usable. This is what lets a track survive its
  device being unplugged and plugged back in.
- **Detaching does not close**, and **closing does not detach**. They are separate calls, and `Track.close()` makes
  both.
- **Disconnecting does not detach.** When a device vanishes, its handle reports *closed* and then *disconnected*
  while every receiver stays attached, ready for the device to come back.

## Related documents

- [`sc-midi/README.md`](sc-midi/README.md) — `MidiManager`, `MidiDeviceHandle`, the `MidiEvent` family, and the MIDI
  plumbing (`MidiProcessor`, transmitters, receivers).
- [`tuner/README.md`](tuner/README.md) — `Track`, `TrackManager`, `TunerProcessor`, and how tracks react to device
  changes.
