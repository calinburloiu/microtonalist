# Unifying `MidiManager`'s Input and Output API Behind a `MidiDirection` Parameter (Design)

- **Date**: 2026-09-19
- **Issue**: [#307](https://github.com/calinburloiu/microtonalist/issues/307) — "Unify MidiManager's input and output
  API behind a MidiDirection parameter", sub-issue of
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `b625d98` — "[#131][#288] Warn when two devices of a direction share an id", the top of
  `feature/hot-plug-midi-devices` ([#304](https://github.com/calinburloiu/microtonalist/pull/304)). The work of this
  document is stacked on that branch.
- **Purpose**: decide the shape of the unified `MidiManager` API and record why, so that the implementation plan can
  be written from it. The change is a behaviour-preserving refactoring with exactly one new behaviour (Section 3.2).

## 1. Why

[`2026-09-07-midi2-outlook.md`](2026-09-07-midi2-outlook.md), Section 3.3, notes in passing:

> The `MidiManager` trait's input/output split does not fit bidirectional UMP endpoints with Function Blocks exactly.

That observation is the trigger, but the split costs something already, before any MIDI 2.0 code exists.

`MidiManager` declares **20 members**: `refresh()`, `close()`, and **nine mirrored pairs** —
`isInputAvailable` / `isOutputAvailable`, `inputDeviceInfoOf` / `outputDeviceInfoOf`, `inputDeviceIds` /
`outputDeviceIds`, `inputDevicesInfo` / `outputDevicesInfo`, `openInput` / `openOutput`, `inputDeviceHandleOf` /
`outputDeviceHandleOf`, `inputOpenDevices` / `outputOpenDevices`, `inputDevicesRequestedToOpen` /
`outputDevicesRequestedToOpen`, `closeInput` / `closeOutput`. Each pair carries the same ScalaDoc written twice, with
one word changed.

The mirroring propagates:

- **`JavaMidiManager`** already keeps the two directions behind one private `MidiEndpoint(direction)` class. All 18
  overrides are therefore one-liners that differ only in naming `inputEndpoint` or `outputEndpoint`. The
  implementation has the right abstraction; the trait does not expose it.
- **`JavaMidiManagerTest`** carries a private `Endpoint` trait with **nine delegating methods** and two implementing
  objects, `Input` and `Output` — roughly 80 lines whose entire purpose is to run one set of behaviours against both
  halves of a mirrored API. Its own ScalaDoc says so: *"the behaviours of each half are shared (see `deviceEndpoint`)
  and run once per `Endpoint`."*
- **Adding a method to the trait means adding two**, and remembering to mirror it in the test's `Endpoint` as well.

A direction is data, not a naming convention. Passing it as a parameter removes the duplication at every level at
once.

## 2. Decisions

- **D1 — One method per pair, taking `direction: MidiDirection` as the last parameter.** Nine pairs become nine
  methods; `refresh()` and `close()` are unchanged. `deviceId` stays first where present, so the existing argument
  reads the same way and the direction is the added qualifier.
- **D2 — The parameter is the existing `MidiDirection` enum, not a new two-value type.** `MidiEvent` already carries
  `MidiDirection` with the same "one of the manager's two endpoints" meaning, so the module keeps one direction
  vocabulary. A narrower `enum MidiEndpointDirection { case Input, Output }` would make the illegal values
  unrepresentable, but it would need a third value the moment UMP endpoints arrive (Section 6), and it would pull
  `MidiEvent` along with it or leave two direction types side by side.
- **D3 — Only `Input` and `Output` are accepted; `None` and `InputOutput` throw `IllegalArgumentException`.** This is
  the one new behaviour. It is a programming error, not a runtime condition, so it throws rather than returning an
  empty result or doing nothing — an empty `deviceIds(InputOutput)` would read as "no such devices" and hide the bug.
- **D4 — The rejection lives in the implementation, not in `MidiDirection`'s companion or in the trait.** Which
  directions are addressable is an implementation's business: a future UMP manager accepts `InputOutput` for a
  bidirectional endpoint, and it should not have to work around a check baked into the shared type. The *trait's*
  ScalaDoc states the contract that holds for every implementation that keeps devices in separate input and output
  endpoints, which today is all of them.
- **D5 — Method names drop the direction prefix and otherwise keep today's stems.** They then coincide exactly with
  the names `JavaMidiManager.MidiEndpoint` already uses privately, so the trait and its implementation's internals
  finally speak one vocabulary.
- **D6 — The reference-counted pair is `openDevice` / `closeDevice`, not `open` / `close`.** `close()` is
  `AutoCloseable`'s and means "shut down the manager"; overloading it with `close(deviceId, direction)`, which means
  "release one reference to a device", would put two unrelated meanings on one name. `openDevice` is named to match.
- **D7 — No parameter object and no forward-compatibility scaffolding for groups or Function Blocks.** See Section 6.
- **D8 — The APIs of `MidiDeviceHandle`, `MidiDeviceInfo`, `MidiEvent` and `MidiDirection` itself are untouched.**
  The change is confined to the manager's API surface, its implementation, and the call sites. `MidiDeviceHandle`'s
  ScalaDoc text does change, because it names the four methods being renamed (Section 5.1), but no member of it does.

## 3. The trait

### 3.1 Signatures

```scala
trait MidiManager extends AutoCloseable {

  def refresh(): Unit

  def isDeviceAvailable(deviceId: MidiDeviceId, direction: MidiDirection): Boolean

  def deviceInfoOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceInfo]

  def deviceIds(direction: MidiDirection): Seq[MidiDeviceId]

  def devicesInfo(direction: MidiDirection): Seq[MidiDeviceInfo]

  def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle

  def deviceHandleOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle]

  def openDevices(direction: MidiDirection): Seq[MidiDeviceHandle]

  def devicesRequestedToOpen(direction: MidiDirection): Seq[MidiDeviceHandle]

  def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit

  override def close(): Unit
}
```

Twenty members become eleven. Every ScalaDoc that was written twice is written once, with a single `@param direction`
line replacing the word that used to differ between the two copies.

**Accepted cost.** `openDevice(deviceId, direction)` (take a reference) and `openDevices(direction)` (list the handles
that are open) differ by one letter and mean different things. The alternative considered was renaming the two
listings to `openDeviceHandles` and `deviceHandlesRequestedToOpen`, which is unambiguous but verbose and diverges from
both today's names and `MidiEndpoint`'s. Keeping the stems was chosen deliberately; the ScalaDocs of the two make the
difference explicit, and their types differ (`MidiDeviceHandle` vs `Seq[MidiDeviceHandle]`).

### 3.2 The direction contract

The trait's ScalaDoc gains a paragraph stating, once, what every method's `direction` parameter means and what it
accepts:

> The `direction` selects which of the manager's two endpoints the device is kept in, because a platform may expose
> two endpoints — two handles — for the same physical device, one for input and the other for output, under a single
> `MidiDeviceId`. Only `MidiDirection.Input` and `MidiDirection.Output` name an endpoint; `MidiDirection.None` and
> `MidiDirection.InputOutput` describe what a device is capable of, not where the manager keeps it, and passing either
> throws an `IllegalArgumentException`. An implementation over bidirectional endpoints, such as a future MIDI 2.0 one,
> may widen this to `InputOutput`.

Note the deliberate asymmetry with `MidiDeviceHandle.direction` and `MidiDeviceInfo.direction`, which are the same
type and *do* take all four values: those say what the device is capable of, this says where the manager filed it.
Section 7 records the open question about that.

## 4. `JavaMidiManager`

The two `MidiEndpoint` fields stay as they are. A `Map[MidiDirection, MidiEndpoint]` was considered and rejected: it
buys nothing, because `refresh()` and `close()` name both endpoints explicitly anyway, and it would turn every
delegation into a lookup that can fail.

One private dispatcher carries D3 and D4:

```scala
private def endpointOf(direction: MidiDirection): MidiEndpoint = direction match {
  case MidiDirection.Input => inputEndpoint
  case MidiDirection.Output => outputEndpoint
  case other => throw IllegalArgumentException(
    s"A MIDI device is kept in an input or an output endpoint, not in $other!")
}
```

The 18 overrides become nine, each still a one-liner under the same lock as before:

```scala
override def deviceIds(direction: MidiDirection): Seq[MidiDeviceId] = withLock {
  endpointOf(direction).deviceIds
}

override def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle = withLockThenPublish {
  endpointOf(direction).openDevice(deviceId)
}

override def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit = withLockThenPublish {
  ((), endpointOf(direction).closeDevice(deviceId))
}
```

**Locking is unaffected.** `endpointOf` reads two `val` fields and does not touch handle state, so whether it runs
inside or outside `withLock` makes no difference; it runs inside, where the delegation already is. A rejected
direction therefore throws from inside the lock, which `withLock` releases on the way out as it does for any other
throw. The publish-after-unlock discipline of `withLockThenPublish` is untouched.

**Nothing else in `JavaMidiManager` changes.** `refresh()`, `resolveDevice`, `close()`, the `MidiEndpoint` class and
`JavaMidiDeviceHandle` are all out of scope.

## 5. Consumers, tests, documentation

### 5.1 Production call sites

Six in total, all outside `sc-midi`:

| File | Sites | Change |
| ---- | ----- | ------ |
| `tuner/…/Track.scala` | 4 (lines 51, 60, 115, 119) | `openInput(id)` → `openDevice(id, MidiDirection.Input)`, and the three siblings. The `DeviceTrackInputSpec` / `DeviceTrackOutputSpec` branches may collapse into one now that only the direction differs; whether they do is decided when the code is in front of us, and it is not a goal. |
| `cli/…/MidiDevicesCommand.scala` | 2 | `inputDevicesInfo` / `outputDevicesInfo` → `devicesInfo(direction)`. It already prints one endpoint at a time through `printMidiDevicesByEndpoint`, so the direction becomes another argument it passes through. |

`MidiDeviceHandle`'s class ScalaDoc references `MidiManager.openInput` / `openOutput` / `closeInput` / `closeOutput`
(lines 23–24) and must be updated with them.

### 5.2 Tests

- **`JavaMidiManagerTest`** is where most of the work and most of the payoff is. The `Endpoint` trait loses all nine
  delegating methods, keeping only `direction` and `newDevice`; `Input` and `Output` shrink accordingly, or collapse
  into a table of `(direction, newDevice)` driven by the `TableDrivenPropertyChecks` the suite already mixes in. The
  shared behaviours in `deviceEndpoint` call `manager.deviceIds(direction)` and friends directly. The class ScalaDoc,
  which explains the `Endpoint` indirection, is rewritten to match.
- **New cases** pin D3: each of the nine methods rejects `MidiDirection.None` and `MidiDirection.InputOutput` with an
  `IllegalArgumentException`. A table over the nine methods keeps this to one test rather than nine.
- **`MidiDevicesCommandTest`** stubs `MidiManager` through ScalaMock's older `MockFactory`, and reaches the
  parameterless `inputDevicesInfo` / `outputDevicesInfo` through the eta-expansion workaround
  `(() => midiManager.inputDevicesInfo).when().returns(…)`. Because `devicesInfo(direction)` takes a parameter, those
  become ordinary stubbings that match on the direction, which is a small simplification rather than a cost. The
  suite is one of the `AnyFlatSpec` ones not yet migrated by
  [#299](https://github.com/calinburloiu/microtonalist/issues/299), so any case touched there keeps that style.
- **`TrackTest`** (8 sites) and **`TrackManagerTest`** (4 sites) follow `Track`'s call sites mechanically.

### 5.3 Documentation

- `docs/architecture/sc-midi/README.md` — the `MidiManager` paragraph ("a per-direction API mirrored for input and
  output…"), the `MidiDeviceHandle` ownership bullet, and steps 2–5 of "How MIDI devices are opened, enumerated, and
  used".
- `docs/architecture/tuner/README.md`, `docs/architecture/cli/README.md`,
  `docs/architecture/midi-device-lifecycle.md` — each mentions the old method names.
- `2026-09-07-midi2-outlook.md` is **not** revised. It is a dated snapshot pinned to `5b235e6`, and its Section 3.3
  observation and Section 5 out-of-scope list both remain true statements about the state they describe. This
  document is the follow-up, and links back to it.

## 6. What MIDI 2.0 would add later, and why nothing is reserved for it now

The question this design had to answer: could groups and Function Blocks be added later as optional parameters?

**Mechanically, yes.** `sc-midi` is not published as a library, so there is no binary compatibility to preserve, and
every implementor of the trait lives in this repository. Adding a parameter with a default value to a trait method
keeps all call sites compiling and requires updating only the implementations' signatures.

**But groups and Function Blocks are unlikely to arrive as parameters to these methods at all**, which is why no
scaffolding — in particular no `(deviceId, direction)` parameter object — is introduced now:

- **Group is a per-message coordinate.** The outlook's Section 3.1 already has every MIDI 2.0 Channel Voice case class
  carrying a `group` alongside its `channel`. A group is where a message is addressed, not which device is opened, so
  it belongs on `Midi2Msg` and not on `openDevice`.
- **Function Blocks are device description.** A block carries its own direction and spans one or more groups, so
  enumerating the blocks of an endpoint is richer `MidiDeviceInfo` plus, possibly, a new method — not an extra
  argument threaded through the nine existing ones.
- **The parameter that genuinely changes meaning is `direction`.** A UMP endpoint is bidirectional, so `direction`
  stops being "which of two registries" and becomes "which side of one endpoint, or both". D2 is what lets that happen
  by widening the accepted values of a type that is already there, instead of changing the type.

Everything in this section about UMP is drawn from the outlook document, which states (its Section 6) that its
message layouts and numbers must be verified against the MIDI Association specifications before implementation. That
caveat applies here too: this section justifies *not* building something, so being directionally right is enough, but
it must not be read as settled fact about UMP.

## 7. Open decision

**`MidiDeviceHandle.direction` versus the new `direction` parameter.** The handle's `direction` means *what the device
is capable of* — `InputOutput` for a bidirectional piano — while the manager's new parameter of the same name and type
means *which endpoint*. The two never mix at the type level, but they read alike at a call site.

Two options, to be settled before implementation:

1. **Leave it.** Document the distinction in the ScalaDocs of both and in the `sc-midi` README. Smallest blast radius.
2. **Rename the handle's to `deviceDirection`.** Distinct at every call site, but it touches `MidiDeviceHandle`,
   `MidiDeviceInfo.direction`, `JavaMidiDeviceHandle` (which already has a separate `managerDirection` that the rename
   would sit next to), and their tests and docs — a second rename riding along with this one.

This document does not choose. If no choice is recorded before the plan is written, option 1 applies, since it is the
one consistent with D8.

## 8. Order of work

The bulk of this is a rename under a green suite, and the plan should not dress it up as red/green. The one genuinely
new behaviour is D3, and it does get a failing test first, in the order the root `CLAUDE.md` prescribes for a change
that must compile before it can fail for the right reason:

1. **Red.** Write the D3 rejection tests against the new signatures. They do not compile, so `MidiManager` gains the
   unified methods as the thinnest possible stub (`???` bodies) and `JavaMidiManager` overrides them the same way.
   Confirm the tests fail because nothing rejects anything, not because something is unimplemented.
2. **Green.** Implement `endpointOf` with its rejecting catch-all and the nine delegating overrides.
3. **Refactor, suite green throughout.** Migrate `Track`, `MidiDevicesCommand` and the four test suites to the
   unified methods, then delete the old 18 trait methods and their overrides. The compiler finds every site; nothing
   here is behavioural.
4. **Simplify the tests.** Collapse `JavaMidiManagerTest`'s `Endpoint` indirection now that the API it existed for is
   gone.
5. **Documentation.** Section 5.3.

Steps 1–2 and 3 cannot be reordered — the old methods can only be deleted once nothing calls them — but step 3's
migration and deletion can be one commit per module if that reads better in review.

## 9. Non-goals

- Groups, Function Blocks, UMP, and any MIDI 2.0 code (Section 6).
- A parameter object or any other forward-compatibility scaffolding (D7).
- Changes to `MidiEvent`, `MidiDirection`, `MidiDeviceInfo`, or `MidiDeviceHandle`'s own API (D8), except the
  `MidiDeviceHandle` ScalaDoc references in Section 5.1 and whatever Section 7 decides.
- Anything in `JavaMidiManager` beyond the overrides and `endpointOf` — in particular `refresh()`, the reconciliation,
  and the locking and publishing discipline (Section 4).
- The `#306` two-devices-under-one-id problem, which is about keying the registries and is untouched by this change.

## 10. Verification

- `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`, then `tuner`, `cli`, and finally
  `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- Coverage of `sc-midi` must not drop; since this is a rename plus one new throwing branch that is itself tested, it
  should rise slightly as the mirrored overrides collapse. Checked with the `scoverage-inspector` skill.
- The refactoring is behaviour-preserving, so any test that needs its *assertions* changed — as opposed to its call
  syntax — is a signal that something was missed, and should be raised rather than adjusted.
