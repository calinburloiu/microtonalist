# Unifying `MidiManager`'s Input and Output API Behind a `MidiDirection` Parameter (Design)

- **Date**: 2026-09-19
- **Issue**: [#307](https://github.com/calinburloiu/microtonalist/issues/307) — "Unify MidiManager's input and output
  API behind a MidiDirection parameter", sub-issue of
  [#278](https://github.com/calinburloiu/microtonalist/issues/278)
- **Base commit**: `8405acf` — the merge of `feature/hot-plug-midi-devices`
  ([#304](https://github.com/calinburloiu/microtonalist/pull/304)) at `209e1c0` into this document's branch. The work
  of this document is stacked on that branch. The design was first written against `b625d98`; the five commits merged
  since settle Section 7.
- **Purpose**: decide the shape of the unified `MidiManager` API and record why, so that the implementation can be
  carried out from it. The change is a behaviour-preserving refactoring with exactly one new behaviour (Section 3.2).
  It is also the plan: Section 8 is the order of work and Section 10 the verification, and no separate plan document
  is written.
- **Revised**: 2026-09-19, after the review of [#308](https://github.com/calinburloiu/microtonalist/pull/308) at
  `e07a08a`. The public ScalaDocs no longer describe the `direction` parameter as the endpoint of the manager a device
  is kept in, since how an implementation organises its devices is not the API's business: they describe it as *how
  the caller wants to use a device* — as an input, an output or both. Sections 3.2, 5.1, 6 and 7 and D2, D3 and D9
  are restated in those terms; the endpoints that remain are `JavaMidiManager`'s internals and MIDI 2.0's UMP
  endpoints.

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
  `MidiDirection` with the same meaning, the use of the device an event concerns, so the module keeps one direction
  vocabulary. A narrower `enum MidiEndpointDirection { case Input, Output }` would make the illegal values
  unrepresentable, but it would need a third value the moment UMP endpoints arrive (Section 6), and it would pull
  `MidiEvent` along with it or leave two direction types side by side. Keeping one type is also what makes an event's
  `direction` and a method's `direction` the same value by construction — see D9.
- **D3 — `None` is rejected by every implementation; `InputOutput` is legal in principle and rejected by
  `JavaMidiManager`.** This is the one new behaviour. `None` requests no use of a device under any implementation, so
  the *trait* states that passing it throws an `IllegalArgumentException`. `InputOutput` is a different case: it is a
  legal thing to ask of a manager built over bidirectional endpoints, such as a future MIDI 2.0 one, so the trait
  leaves it open and warns that an implementation may not support it. An implementation that keeps input and output in
  separate endpoints — which is every implementation today — rejects it, and `JavaMidiManager` does. Rejection throws,
  rather than returning an empty result or doing nothing, because it is a programming error and not a runtime
  condition: an empty `deviceIdsFor(InputOutput)` would read as "no such devices" and hide the bug.
- **D4 — Both checks are code in the implementation; only the `None` one is a promise of the trait.** Every
  rejection is a single catch-all in `JavaMidiManager.endpointOf` (Section 4.1) — nothing is added to `MidiDirection`'s
  companion, and the trait stays abstract. What differs is the contract each check answers to: rejecting `None` is
  owed by every implementation, so the trait states it; rejecting `InputOutput` is this manager's, so its own ScalaDoc
  states it, and a future UMP manager accepting `InputOutput` breaks no promise. Which directions are addressable is
  an implementation's business, and that manager should not have to work around a check baked into the shared type or
  the trait. That this design's one implementation happens to reject both values does not make them one rule.
- **D5 — Method names drop the direction prefix and otherwise keep today's stems, adjusted by D5a–D5c.** The stems
  are those `JavaMidiManager.MidiEndpoint` already uses privately, so the trait and its implementation's internals
  speak one vocabulary; `MidiEndpoint` follows D5b but not D5c (Section 3.1).
- **D5a — No method of the trait names a handle.** The trait's noun for a device the manager holds is "device", and a
  handle is simply how this API hands one over — `openDevice` and `closeDevice` already return and take
  `MidiDeviceHandle`s without saying so. Naming the return type in some methods and not in the others would make the
  exception, not the rule, the thing a reader has to remember.
- **D5b — The single-device accessor is `deviceOf`, not `deviceHandleOf`** (D5a). It belongs to the `…Of` family
  beside `deviceInfoOf`.
- **D5c — A method whose only parameter is `direction` takes a `For` suffix: `deviceIdsFor`, `devicesInfoFor`,
  `openDevicesFor`, `devicesRequestedToOpenFor`.** The suffix reads the direction into the name — "the device ids for
  a direction" — and marks these four as listings. It also cures `openDevices`, which without it reads as an order
  and sits one letter from `openDevice`. `Of` stays with the methods keyed by a device id (`deviceOf`,
  `deviceInfoOf`), so the suffix tells which kind of method it is before its arguments are read.
- **D10 — `javamidi`'s Java Sound-typed identifiers take a `java` prefix, here and not in a later issue.** D5b's
  `deviceOf` would otherwise share a file with `JavaMidiEnvironment.deviceOf`, which returns a
  `javax.sound.midi.MidiDevice`. The sweep is mechanical and compiler-checked, and stacking one more issue and PR
  under #278 to carry it would cost more review depth than the rename costs to carry here. Section 4.2 lists it and
  states where it stops.
- **D6 — The reference-counted pair is `openDevice` / `closeDevice`, not `open` / `close`.** `close()` is
  `AutoCloseable`'s and means "shut down the manager"; overloading it with `close(deviceId, direction)`, which means
  "release one reference to a device", would put two unrelated meanings on one name. `openDevice` is named to match.
- **D7 — No parameter object and no forward-compatibility scaffolding for groups or Function Blocks.** See Section 6.
- **D8 — The APIs of `MidiDeviceHandle`, `MidiDeviceInfo`, `MidiEvent` and `MidiDirection` itself are untouched.**
  The change is confined to the manager's API surface, its implementation, and the call sites. The ScalaDoc *text* of
  `MidiDeviceHandle` and `MidiEvent` does change — the first names the four methods being renamed, the second states a
  contract this design rewrites (D9) — but no member of either does.
- **D9 — A `MidiEvent`'s `direction` is restated as the use of the device it concerns, on the same terms as D3,
  instead of as "always `Input` or `Output`".** An event's `direction` is its handle's `requestedDirection`, the use
  the device was requested for — by construction the very value a consumer passes to the methods of Section 3.1 to
  request that use. So the two now say the same thing, and the event's ScalaDoc must not keep claiming
  more: "always `Input` or `Output`, never another value" is a property of the implementations that exist, not of the
  event. The claim moves where D3 put its twin, from the type to the implementation. Section 5.1 has the wording.

## 3. The trait

### 3.1 Signatures

```scala
trait MidiManager extends AutoCloseable {

  def refresh(): Unit

  def isDeviceAvailable(deviceId: MidiDeviceId, direction: MidiDirection): Boolean

  def deviceInfoOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceInfo]

  def deviceIdsFor(direction: MidiDirection): Seq[MidiDeviceId]

  def devicesInfoFor(direction: MidiDirection): Seq[MidiDeviceInfo]

  def openDevice(deviceId: MidiDeviceId, direction: MidiDirection): MidiDeviceHandle

  def deviceOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle]

  def openDevicesFor(direction: MidiDirection): Seq[MidiDeviceHandle]

  def devicesRequestedToOpenFor(direction: MidiDirection): Seq[MidiDeviceHandle]

  def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit

  override def close(): Unit
}
```

Twenty members become eleven. Every ScalaDoc that was written twice is written once, with a single `@param direction`
line replacing the word that used to differ between the two copies.

**How the names were reached (D5a, D5c).** Plainly dropping the direction prefix left `openDevices(direction)` one
letter from `openDevice(deviceId, direction)`, and read as an imperative, though it only lists the devices that are
open. Naming the listings `openDeviceHandles` and `deviceHandlesRequestedToOpen` was tried and reverted, because it
made them the only methods naming a handle (D5a). The `For` suffix (D5c) settles it: `openDevicesFor(direction)` reads
as "the open devices for a direction", not as an order to open them.

The private `MidiEndpoint` keeps its unsuffixed names (`deviceIds`, `devicesInfo`, `openDevices`,
`devicesRequestedToOpen`): it takes no direction, since it *is* one direction, so there is nothing for `For` to refer
to. Only its `deviceHandleOf` changes, becoming `deviceOf` (D5b). That also puts a noun between it and the
neighbouring `handleOf`, which gets or creates rather than looks up — today the two differ only by a prefix.

### 3.2 The direction contract

The trait's ScalaDoc gains a paragraph stating, once, what every method's `direction` parameter means and what it
accepts:

> The `direction` a method takes tells how the caller wants to use a device: as an input (`MidiDirection.Input`), as
> an output (`MidiDirection.Output`) or as both (`MidiDirection.InputOutput`). It is a request: the `direction` of a
> `MidiDeviceHandle` or of a `MidiDeviceInfo` tells instead what the device itself is capable of, and a caller may
> request less than that. A MIDI 2.0 implementation, for instance, would let it request only the input of a device
> that works in both directions, and so a projection of that device.
>
> `MidiDirection.None` requests no use of a device and always throws an `IllegalArgumentException`. Of the other three
> values, an implementation accepts only those it can serve, throws an `IllegalArgumentException` for the rest, and
> documents which values it accepts. Every implementation today, including `JavaMidiManager`, accepts `Input` and
> `Output` and rejects `InputOutput`.

Note the deliberate asymmetry with `MidiDeviceHandle.direction` and `MidiDeviceInfo.direction`, which are the same
type and describe *what the device is capable of*, while this one says *how the caller wants to use it*. Section 7
records why that asymmetry needs no rename.

The paragraph first said that the `direction` *selects which endpoint of the manager the device is kept in*. The
review of #308 dropped that: it described how `JavaMidiManager` stores its handles, which is not the trait's business,
whereas the parameter states what the caller wants to do with the device. Why `JavaMidiManager` rejects `InputOutput`
is its own ScalaDoc's to say (Section 4.1).

## 4. The `javamidi` implementation

### 4.1 `JavaMidiManager`

The two `MidiEndpoint` fields stay as they are. A `Map[MidiDirection, MidiEndpoint]` was considered and rejected: it
buys nothing, because `refresh()` and `close()` name both endpoints explicitly anyway, and it would turn every
delegation into a lookup that can fail.

One private dispatcher carries D3 and D4. Its message says that *this manager* keeps its devices in two endpoints,
since that is what makes the value wrong here:

```scala
private def endpointOf(direction: MidiDirection): MidiEndpoint = direction match {
  case MidiDirection.Input => inputEndpoint
  case MidiDirection.Output => outputEndpoint
  case other => throw IllegalArgumentException(
    s"This MIDI manager keeps a device in an input or an output endpoint, not in $other!")
}
```

The class ScalaDoc of `JavaMidiManager` states which values it accepts, as the trait's contract requires.

The 18 overrides become nine, each still a one-liner under the same lock as before:

```scala
override def deviceIdsFor(direction: MidiDirection): Seq[MidiDeviceId] = withLock {
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

**Nothing else in `JavaMidiManager` changes**, beyond the `java` prefix of Section 4.2. `refresh()`, `close()`, the
reconciliation and the `MidiEndpoint` class keep their logic.

### 4.2 The `java` prefix (D10)

D5b puts `MidiManager.deviceOf(deviceId, direction): Option[MidiDeviceHandle]` next to
`JavaMidiEnvironment.deviceOf(info: MidiDevice.Info): MidiDevice`, which returns the *platform's* device and which
`resolveDevice` calls (line 120). Rather than let one word mean two things in one file, every identifier in `javamidi`
whose type comes from `javax.sound.midi` says so:

| Where | Today | Becomes |
| ----- | ----- | ------- |
| `JavaMidiEnvironment` + `CoreMidi4JEnvironment` + `FakeJavaMidiEnvironment` | `deviceInfos`, `deviceOf(info)` | `javaDeviceInfos`, `javaDeviceOf(javaInfo)` |
| `JavaMidiManager` | `resolveDevice(javaInfo, events): Option[MidiDevice]`, `ConnectedDevice(info, device)` | `resolveJavaDevice(…)`, `ConnectedDevice(info, javaDevice)` — `info` stays, being the module's own `MidiDeviceInfo` |
| `JavaMidiDeviceHandle` | `_device`, `private[javamidi] def device`, and the `device` / `heldDevice` parameters and locals of `connect`, `hold`, `doOpen`, `closeOpenDevice` | `_javaDevice`, `javaDevice`, `javaDevice` / `heldJavaDevice` |
| `JavaMidiConverters` | `extension (info: MidiDevice.Info)`, `extension (device: MidiDevice)` | `javaInfo`, `javaDevice` |

The package already reads this way in places — `resolveDevice(javaInfo: MidiDevice.Info, …)`, `asJava` / `asScala`,
`javaMaxConnections` — so this finishes a convention rather than inventing one, in a package whose whole purpose is
isolating `javax.sound.midi` (#278).

**Where it stops.** The rule is *the type comes from `javax.sound.midi`*, so `MidiDeviceInfo`, `MidiDeviceId` and the
handles keep their plain names, and so do the suites' `FakeMidiDevice` locals (`device`, `repluggedDevice`,
`inputDevice`, …), whose declared type already names the fake and which sit beside no `MidiDevice` of the module's
own. `JavaMidiDeviceHandleTest`'s dozen `handle.device` assertions follow the getter rename mechanically, as the
compiler dictates.

This is a rename, entirely compiler-checked, and it is confined to `javamidi` — `device` is `private[javamidi]`, so
nothing outside the package names any of this.

## 5. Consumers, tests, documentation

### 5.1 Production call sites

Six in total, all outside `sc-midi`:

| File | Sites | Change |
| ---- | ----- | ------ |
| `tuner/…/Track.scala` | 4 (lines 51, 60, 115, 119) | `openInput(id)` → `openDevice(id, MidiDirection.Input)`, and the three siblings. The `DeviceTrackInputSpec` / `DeviceTrackOutputSpec` branches may collapse into one now that only the direction differs; whether they do is decided when the code is in front of us, and it is not a goal. |
| `cli/…/MidiDevicesCommand.scala` | 2 | `inputDevicesInfo` / `outputDevicesInfo` → `devicesInfoFor(direction)`. It already prints one endpoint at a time through `printMidiDevicesByEndpoint`, so the direction becomes another argument it passes through. |

`MidiDeviceHandle`'s class ScalaDoc references `MidiManager.openInput` / `openOutput` / `closeInput` / `closeOutput`
(lines 23–24) and must be updated with them.

**`MidiEvent`'s ScalaDoc (D9).** Its class ScalaDoc says an event's `direction` is "always `MidiDirection.Input` or
`MidiDirection.Output`", and each of the seven events that carry one repeats "`Input` or `Output`, never another
value" in its `@param`. That becomes the direction contract of Section 3.2, stated once:

> A device event identifies its device by `MidiDeviceId` and, except for `MidiDeviceFailedToConnectEvent`, tells in
> its `direction` the use of the device it concerns — the same value a caller passes to `MidiManager`'s methods to
> request that use, and so one of those the publishing implementation accepts. An implementation that accepts only
> `MidiDirection.Input` and `MidiDirection.Output` publishes only those, so a device that works in both directions is
> reported once for each of them, by two events that differ in their `direction`.

The seven `@param direction` copies shrink to one line each — the use of the device by the handle that made the
transition, pointing at the class ScalaDoc for the values it takes — which removes the same duplication this design
removes everywhere else.

`JavaMidiDeviceHandle` is **not** touched: its `requestedDirection` `@param` and its `require` do restrict the value
to `Input` or `Output`, and that is right, because it is the Java Sound implementation restricting its own handle
(D4).

**Rider: `MidiDevicesCommand` → `MidiDevicesCliCommand`** (with `MidiDevicesCommandTest`). Not part of unifying the
direction API, but the class is being edited anyway and the name is worth fixing while it is open: bare `Command` next
to a `Midi` prefix reads as a MIDI command — a message's status byte — rather than as a subcommand of
`microtonalist-cli`. The package `…microtonalist.cli` makes `Cli` redundant only for someone who is already looking at
the package clause; at a use site, and in the `cli` architecture doc, the name stands alone. Its three references are
`MicrotonalistToolApp` (a ScalaDoc link and the call), the test class, and `docs/architecture/cli/README.md`. It is a
rename of its own and belongs in its own commit, with no other change riding in it.

### 5.2 Tests

- **`JavaMidiManagerTest`** is where most of the work and most of the payoff is. The `Endpoint` trait loses all nine
  delegating methods, keeping only `direction` and `newDevice`; `Input` and `Output` shrink accordingly, or collapse
  into a table of `(direction, newDevice)` driven by the `TableDrivenPropertyChecks` the suite already mixes in. The
  shared behaviours in `deviceEndpoint` call `manager.deviceIdsFor(direction)` and friends directly. The class ScalaDoc,
  which explains the `Endpoint` indirection, is rewritten to match.
- **New cases** pin D3, in `JavaMidiManagerTest` because both rejections are this implementation's: each of the nine
  methods rejects `MidiDirection.None` — the trait's contract — and `MidiDirection.InputOutput` — this manager's own
  restriction — with an `IllegalArgumentException`. A table over the nine methods, crossed with the two values, keeps
  this to one test rather than eighteen.
- **`MidiDevicesCommandTest`** (renamed to `MidiDevicesCliCommandTest`, Section 5.1) stubs `MidiManager` through
  ScalaMock's older `MockFactory`, and reaches the
  parameterless `inputDevicesInfo` / `outputDevicesInfo` through the eta-expansion workaround
  `(() => midiManager.inputDevicesInfo).when().returns(…)`. Because `devicesInfoFor(direction)` takes a parameter, those
  become ordinary stubbings that match on the direction, which is a small simplification rather than a cost. The
  suite is one of the `AnyFlatSpec` ones not yet migrated by
  [#299](https://github.com/calinburloiu/microtonalist/issues/299), so any case touched there keeps that style.
- **`TrackTest`** (8 sites) and **`TrackManagerTest`** (4 sites) follow `Track`'s call sites mechanically.

### 5.3 Documentation

- `docs/architecture/sc-midi/README.md` — the `MidiManager` paragraph ("a per-direction API mirrored for input and
  output…"), the `MidiDeviceHandle` ownership bullet, steps 2–5 of "How MIDI devices are opened, enumerated, and
  used", the `MidiEvent` bullet that repeats the "always `Input` or `Output`" claim D9 rewrites (line 136), and the
  `JavaMidiEnvironment` paragraph, which names the seam's `deviceInfos` and `deviceOf(info)` that D10 prefixes.
- `docs/architecture/tuner/README.md`, `docs/architecture/cli/README.md`,
  `docs/architecture/midi-device-lifecycle.md` — each mentions the old method names. The `cli` one also names
  `MidiDevicesCommand` four times (lines 23, 27, 32, 46), which the rider of Section 5.1 renames.
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
- **The parameter that genuinely changes is `direction`, in the values it accepts.** It already tells how the caller
  wants to use a device (Section 3.2). A UMP endpoint is bidirectional, so a MIDI 2.0 manager would accept
  `InputOutput` as well, and would serve `Input` or `Output` as a projection of such an endpoint onto one of its
  sides. D2 is what lets that happen by widening the accepted values of a type that is already there, instead of
  changing the type.

Everything in this section about UMP is drawn from the outlook document, which states (its Section 6) that its
message layouts and numbers must be verified against the MIDI Association specifications before implementation. That
caveat applies here too: this section justifies *not* building something, so being directionally right is enough, but
it must not be read as settled fact about UMP.

## 7. Settled: `MidiDeviceHandle.direction` keeps its name

**The question.** The handle's `direction` means *what the device is capable of* — `InputOutput` for a bidirectional
piano — while the manager's new parameter of the same name and type means *how the caller wants to use it*. The two
never mix at the type level, but they read alike at a call site. The options were to leave it, or to rename the
handle's to `deviceDirection`.

**Settled: leave it**, by the work merged from `feature/hot-plug-midi-devices` after this document was first drafted:

- `cec25c3` renamed `JavaMidiDeviceHandle.managerDirection` to **`requestedDirection`**. That was the name the second
  option was meant to disambiguate from, and the rename disambiguated it from the other side: a handle now has
  `direction` (what the device can do) next to `requestedDirection` (the use it was requested for), and the
  manager's new parameter is the latter meaning. Renaming `direction` on top of that would re-open a distinction that
  is already drawn.
- `13dba0e` removed the sentences in `MidiDirection`'s ScalaDoc and the `sc-midi` README that said a manager's
  endpoints and a `MidiEvent`'s direction are only `Input` or `Output`, leaving `MidiDirection` a plain "what a MIDI
  endpoint can do" type. That is also what makes D3's wording in Section 3.2 possible: the restriction is the
  manager's to state, not the type's.

So D8 stands unchanged, and no member of `MidiDeviceHandle` or `MidiDeviceInfo` is touched. What remains is the
documentation half of option 1: the ScalaDocs of the trait (Section 3.2) and the `sc-midi` README draw the
distinction in words.

## 8. Order of work

The bulk of this is a rename under a green suite, and the plan should not dress it up as red/green. The one genuinely
new behaviour is D3, and it does get a failing test first, in the order the root `CLAUDE.md` prescribes for a change
that must compile before it can fail for the right reason:

1. **Red.** Write the D3 rejection tests in `JavaMidiManagerTest` against the new signatures, as one table over the
   nine methods crossed with `None` and `InputOutput`. They do not compile, so `MidiManager` gains the unified methods
   as the thinnest possible stub (`???` bodies) and `JavaMidiManager` overrides them the same way. Confirm the tests
   fail because nothing rejects anything, not because something is unimplemented.
2. **Green.** Implement `endpointOf` with its rejecting catch-all and the nine delegating overrides, and rename
   `MidiEndpoint.deviceHandleOf` to `deviceOf` to match D5b.
3. **Refactor, suite green throughout.** Migrate `Track`, `MidiDevicesCommand` and the four test suites to the
   unified methods, then delete the old 18 trait methods and their overrides. The compiler finds every site; nothing
   here is behavioural.
4. **Simplify the tests.** Collapse `JavaMidiManagerTest`'s `Endpoint` indirection now that the API it existed for is
   gone.
5. **Documentation.** Section 5.3, plus the `MidiEvent` ScalaDoc of D9 — which is a source file but carries no code
   change, so it belongs here and not in steps 1–3.
6. **The `java` prefix, on its own commit.** The sweep of Section 4.2 (D10). It comes after step 3, so that the
   trait's names are final and the only `deviceOf` left to disambiguate is the one that stays.
7. **Rider, on its own commit.** Rename `MidiDevicesCommand` to `MidiDevicesCliCommand` with its test and the three
   references of Section 5.1. It is independent of steps 1–6 and can go first or last; last keeps the renames from
   being read as one.

Steps 1–2 and 3 cannot be reordered — the old methods can only be deleted once nothing calls them — but step 3's
migration and deletion can be one commit per module if that reads better in review.

**This section is the plan.** No separate plan document is written: the change is one mechanical rename plus one new
throwing branch, its call sites are inventoried in Section 5, and its verification is Section 10. A plan document
would restate them.

## 9. Non-goals

- Groups, Function Blocks, UMP, and any MIDI 2.0 code (Section 6).
- A parameter object or any other forward-compatibility scaffolding (D7).
- Changes to `MidiEvent`, `MidiDirection`, `MidiDeviceInfo`, or `MidiDeviceHandle`'s own API (D8), except the
  ScalaDoc text of `MidiDeviceHandle` and of `MidiEvent` (Section 5.1). Section 7 is settled and adds nothing here.
- Any change to what an event *carries*: D9 rewrites what the ScalaDoc claims about `MidiEvent.direction`, and no
  event gains, loses or changes a field, nor does any implementation publish a direction it did not publish before.
- Any *behaviour* in `javamidi`: the overrides, `endpointOf` and the renames of Section 4.2 are the whole of it, and
  `refresh()`, the reconciliation, and the locking and publishing discipline keep working exactly as they do.
- The `#306` two-devices-under-one-id problem, which is about keying the registries and is untouched by this change.
- Renaming anything in `javamidi` beyond the `java` prefix of Section 4.2 — in particular the types themselves,
  `MidiDeviceInfo` and `MidiDeviceId`, which are the module's own and keep their plain names (D10).

## 10. Verification

- `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`, then `tuner`, `cli`, and finally
  `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- Coverage of `sc-midi` must not drop; since this is a rename plus one new throwing branch that is itself tested, it
  should rise slightly as the mirrored overrides collapse. Checked with the `scoverage-inspector` skill.
- The refactoring is behaviour-preserving, so any test that needs its *assertions* changed — as opposed to its call
  syntax — is a signal that something was missed, and should be raised rather than adjusted.
