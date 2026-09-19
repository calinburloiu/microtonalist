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
  `MidiEvent` along with it or leave two direction types side by side. Keeping one type is also what makes an event's
  `direction` and a method's `direction` the same value by construction — see D9.
- **D3 — `None` is rejected by every implementation; `InputOutput` is legal in principle and rejected by
  `JavaMidiManager`.** This is the one new behaviour. `None` names no endpoint under any implementation, so the
  *trait* states that passing it throws an `IllegalArgumentException`. `InputOutput` is a different case: it is a
  legal thing to ask of a manager built over bidirectional endpoints, such as a future MIDI 2.0 one, so the trait
  leaves it open and warns that an implementation may not support it. An implementation that keeps input and output in
  separate endpoints — which is every implementation today — rejects it, and `JavaMidiManager` does. Rejection throws,
  rather than returning an empty result or doing nothing, because it is a programming error and not a runtime
  condition: an empty `deviceIds(InputOutput)` would read as "no such devices" and hide the bug.
- **D4 — Both checks are code in the implementation; only the `None` one is a promise of the trait.** Every
  rejection is a single catch-all in `JavaMidiManager.endpointOf` (Section 4) — nothing is added to `MidiDirection`'s
  companion, and the trait stays abstract. What differs is the contract each check answers to: rejecting `None` is
  owed by every implementation, so the trait states it; rejecting `InputOutput` is this manager's, so its own ScalaDoc
  states it, and a future UMP manager accepting `InputOutput` breaks no promise. Which directions are addressable is
  an implementation's business, and that manager should not have to work around a check baked into the shared type or
  the trait. That this design's one implementation happens to reject both values does not make them one rule.
- **D5 — Method names drop the direction prefix, keeping today's stems except for the two listings D5a renames.**
  They then coincide with the names `JavaMidiManager.MidiEndpoint` already uses privately, so the trait and its
  implementation's internals finally speak one vocabulary — and where D5a or D5b renames a trait method, the private
  `MidiEndpoint` method is renamed with it, to keep that property.
- **D5a — The two handle listings are named `openDeviceHandles` and `deviceHandlesRequestedToOpen`.** Dropping the
  direction prefix would otherwise leave `openDevices(direction)` one letter away from `openDevice(deviceId,
  direction)` while meaning something unrelated — list the handles that are open, versus take a reference. Naming what
  they return removes the collision. See Section 3.1 for the cost that was weighed against it.
- **D5b — The single-device accessor is `deviceOf`, not `deviceHandleOf`.** The trait's noun for a device the manager
  holds is already "device": `openDevice` and `closeDevice` deal in `MidiDeviceHandle`s without saying so, a handle
  being simply how this API hands a consumer a device. Spelling `Handle` out in one accessor made it the odd member of
  the `…Of` family it belongs to, beside `deviceInfoOf`. The two listings of D5a keep their `Handles` suffix as the
  documented exception: they carry it to escape a collision, not because this trait names its return types.
- **D6 — The reference-counted pair is `openDevice` / `closeDevice`, not `open` / `close`.** `close()` is
  `AutoCloseable`'s and means "shut down the manager"; overloading it with `close(deviceId, direction)`, which means
  "release one reference to a device", would put two unrelated meanings on one name. `openDevice` is named to match.
- **D7 — No parameter object and no forward-compatibility scaffolding for groups or Function Blocks.** See Section 6.
- **D8 — The APIs of `MidiDeviceHandle`, `MidiDeviceInfo`, `MidiEvent` and `MidiDirection` itself are untouched.**
  The change is confined to the manager's API surface, its implementation, and the call sites. The ScalaDoc *text* of
  `MidiDeviceHandle` and `MidiEvent` does change — the first names the four methods being renamed, the second states a
  contract this design rewrites (D9) — but no member of either does.
- **D9 — A `MidiEvent`'s `direction` is restated as the endpoint's, on the same terms as D3, instead of as "always
  `Input` or `Output`".** An event's `direction` is its handle's `requestedDirection`, which is the direction of the
  endpoint the manager filed the device in — by construction the very value a consumer passes to the methods of
  Section 3.1 to address it. So the two now say the same thing, and the event's ScalaDoc must not keep claiming
  more: "always `Input` or `Output`, never another value" is a property of the implementations that exist, not of the
  event. The claim moves where D3 put its twin, from the type to the implementation. Section 5.1 has the wording.

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

  def deviceOf(deviceId: MidiDeviceId, direction: MidiDirection): Option[MidiDeviceHandle]

  def openDeviceHandles(direction: MidiDirection): Seq[MidiDeviceHandle]

  def deviceHandlesRequestedToOpen(direction: MidiDirection): Seq[MidiDeviceHandle]

  def closeDevice(deviceId: MidiDeviceId, direction: MidiDirection): Unit

  override def close(): Unit
}
```

Twenty members become eleven. Every ScalaDoc that was written twice is written once, with a single `@param direction`
line replacing the word that used to differ between the two copies.

**The two listings and their cost (D5a).** Mechanically dropping the direction prefix would have given
`openDevices(direction)` next to `openDevice(deviceId, direction)`: one letter apart, and meaning "list the handles
that are open" versus "take a reference to this device". Differing ScalaDocs and return types do not undo a name that
invites a misreading at the call site, so the two listings name what they return instead. The accepted costs are that
they are longer than their siblings, and that `openDeviceHandles` can still be read for a moment as an imperative
("open the device handles") — the `Handles` suffix and the `Seq` return settle it, and no method of this trait opens
more than one device, so the imperative reading has nowhere to go. `deviceHandlesRequestedToOpen` has neither problem.

The private `MidiEndpoint.openDevices` / `devicesRequestedToOpen` are renamed to match, and so is its
`deviceHandleOf`, which becomes `deviceOf` (D5). That also puts a noun between it and the neighbouring `handleOf`,
which gets or creates rather than looks up — today the two differ only by a prefix.

### 3.2 The direction contract

The trait's ScalaDoc gains a paragraph stating, once, what every method's `direction` parameter means and what it
accepts:

> The `direction` selects which endpoint of the manager the device is kept in. An implementation may expose separate
> input and output endpoints for the same physical device — two handles under a single `MidiDeviceId`, as the Java
> Sound one does — or a single bidirectional endpoint, as a MIDI 2.0 one would.
>
> `MidiDirection.None` names no endpoint and always throws an `IllegalArgumentException`. The other three values name
> one in principle, but an implementation supports only those that match how it keeps its devices, and throws an
> `IllegalArgumentException` for the rest: one with separate endpoints — every implementation today, including
> `JavaMidiManager` — accepts `Input` and `Output` and rejects `InputOutput`. Each implementation documents which
> values it accepts.

Note the deliberate asymmetry with `MidiDeviceHandle.direction` and `MidiDeviceInfo.direction`, which are the same
type and describe *what the device is capable of*, while this one says *where the manager filed it*. Section 7 records
why that asymmetry needs no rename.

## 4. `JavaMidiManager`

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

**`deviceOf` meets another `deviceOf` here (D5b), and the fix is a follow-up.**
`JavaMidiEnvironment.deviceOf(info: MidiDevice.Info): MidiDevice` returns the *platform's* device, and `resolveDevice`
calls it (line 120); the manager now also overrides `deviceOf(deviceId, direction): Option[MidiDeviceHandle]`, which
returns *this API's*. The two share a file and are told apart only by their receiver and their arguments.

The resolution is to make the Java Sound side explicit throughout `javamidi` — `javaDeviceOf`, `javaDevice`, and so on
— which the package already does for `resolveDevice(javaInfo: MidiDevice.Info, …)` and for `asJava` / `asScala`. It
is the right change for a package whose whole point is isolating `javax.sound.midi` (#278), and it stands on its own
reasons, not on this one.

It is **not done here**, because it reaches well past this design: `JavaMidiDeviceHandle` (`_device`, `device`,
`heldDevice` and the locals of `connect`, `hold` and `closeOpenDevice`), `JavaMidiEnvironment` with both its
implementations, `JavaMidiConverters`' `MidiDevice` extensions, and the tests of each — four files this change never
otherwise opens, folded into a diff that already turns 18 trait methods into 9 and rewrites a test suite. Its own
issue under #278, its own review. Until then the overlap is tolerable: inside `javamidi`, "device" already means the
Java Sound object in `resolveDevice`, `ConnectedDevice.device` and `closeOpenDevice`, so the file's reader is not
newly misled — there is simply one more name for them to keep straight.

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

**`MidiEvent`'s ScalaDoc (D9).** Its class ScalaDoc says an event's `direction` is "always `MidiDirection.Input` or
`MidiDirection.Output`", and each of the seven events that carry one repeats "`Input` or `Output`, never another
value" in its `@param`. That becomes the endpoint contract of Section 3.2, stated once:

> A device event identifies its device by `MidiDeviceId` and, except for `MidiDeviceFailedToConnectEvent`, tells in
> its `direction` the direction of the endpoint it concerns — the same value that addresses that endpoint in
> `MidiManager`'s methods, and so one of those the publishing implementation keeps its devices in. An implementation
> with separate input and output endpoints publishes only `MidiDirection.Input` and `MidiDirection.Output`, and lists
> one physical device once per direction (see `MidiManager`), so a device that works in both directions is reported
> once for each of them, by two events that differ in their `direction`.

The seven `@param direction` copies shrink to one line each — the direction of the endpoint whose handle made the
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
  shared behaviours in `deviceEndpoint` call `manager.deviceIds(direction)` and friends directly. The class ScalaDoc,
  which explains the `Endpoint` indirection, is rewritten to match.
- **New cases** pin D3, in `JavaMidiManagerTest` because both rejections are this implementation's: each of the nine
  methods rejects `MidiDirection.None` — the trait's contract — and `MidiDirection.InputOutput` — this manager's own
  restriction — with an `IllegalArgumentException`. A table over the nine methods, crossed with the two values, keeps
  this to one test rather than eighteen.
- **`MidiDevicesCommandTest`** (renamed to `MidiDevicesCliCommandTest`, Section 5.1) stubs `MidiManager` through
  ScalaMock's older `MockFactory`, and reaches the
  parameterless `inputDevicesInfo` / `outputDevicesInfo` through the eta-expansion workaround
  `(() => midiManager.inputDevicesInfo).when().returns(…)`. Because `devicesInfo(direction)` takes a parameter, those
  become ordinary stubbings that match on the direction, which is a small simplification rather than a cost. The
  suite is one of the `AnyFlatSpec` ones not yet migrated by
  [#299](https://github.com/calinburloiu/microtonalist/issues/299), so any case touched there keeps that style.
- **`TrackTest`** (8 sites) and **`TrackManagerTest`** (4 sites) follow `Track`'s call sites mechanically.

### 5.3 Documentation

- `docs/architecture/sc-midi/README.md` — the `MidiManager` paragraph ("a per-direction API mirrored for input and
  output…"), the `MidiDeviceHandle` ownership bullet, steps 2–5 of "How MIDI devices are opened, enumerated, and
  used", and the `MidiEvent` bullet that repeats the "always `Input` or `Output`" claim D9 rewrites (line 136).
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
- **The parameter that genuinely changes meaning is `direction`.** A UMP endpoint is bidirectional, so `direction`
  stops being "which of two registries" and becomes "which side of one endpoint, or both". D2 is what lets that happen
  by widening the accepted values of a type that is already there, instead of changing the type.

Everything in this section about UMP is drawn from the outlook document, which states (its Section 6) that its
message layouts and numbers must be verified against the MIDI Association specifications before implementation. That
caveat applies here too: this section justifies *not* building something, so being directionally right is enough, but
it must not be read as settled fact about UMP.

## 7. Settled: `MidiDeviceHandle.direction` keeps its name

**The question.** The handle's `direction` means *what the device is capable of* — `InputOutput` for a bidirectional
piano — while the manager's new parameter of the same name and type means *which endpoint*. The two never mix at the
type level, but they read alike at a call site. The options were to leave it, or to rename the handle's to
`deviceDirection`.

**Settled: leave it**, by the work merged from `feature/hot-plug-midi-devices` after this document was first drafted:

- `cec25c3` renamed `JavaMidiDeviceHandle.managerDirection` to **`requestedDirection`**. That was the name the second
  option was meant to disambiguate from, and the rename disambiguated it from the other side: a handle now has
  `direction` (what the device can do) next to `requestedDirection` (the endpoint it was requested for), and the
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
   `MidiEndpoint.openDevices` / `devicesRequestedToOpen` to match D5a.
3. **Refactor, suite green throughout.** Migrate `Track`, `MidiDevicesCommand` and the four test suites to the
   unified methods, then delete the old 18 trait methods and their overrides. The compiler finds every site; nothing
   here is behavioural.
4. **Simplify the tests.** Collapse `JavaMidiManagerTest`'s `Endpoint` indirection now that the API it existed for is
   gone.
5. **Documentation.** Section 5.3, plus the `MidiEvent` ScalaDoc of D9 — which is a source file but carries no code
   change, so it belongs here and not in steps 1–3.
6. **Rider, on its own commit.** Rename `MidiDevicesCommand` to `MidiDevicesCliCommand` with its test and the three
   references of Section 5.1. It is independent of steps 1–5 and can go first or last; last keeps the two renames
   from being read as one.

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
- Anything in `JavaMidiManager` beyond the overrides and `endpointOf` — in particular `refresh()`, the reconciliation,
  and the locking and publishing discipline (Section 4).
- The `#306` two-devices-under-one-id problem, which is about keying the registries and is untouched by this change.
- Prefixing the Java Sound-typed identifiers of `javamidi` with `java` (`javaDeviceOf`, `javaDevice`, …), which D5b
  makes worth doing and Section 4 defers to an issue of its own under #278.

## 10. Verification

- `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`, then `tuner`, `cli`, and finally
  `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`.
- Coverage of `sc-midi` must not drop; since this is a rename plus one new throwing branch that is itself tested, it
  should rise slightly as the mirrored overrides collapse. Checked with the `scoverage-inspector` skill.
- The refactoring is behaviour-preserving, so any test that needs its *assertions* changed — as opposed to its call
  syntax — is a signal that something was missed, and should be raised rather than adjusted.
