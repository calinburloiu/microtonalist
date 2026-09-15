# Hot plugging MIDI devices Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-09-15
- **Base commit:** `a55e9d4` (branch `feature/hot-plug-midi-devices`, stacked on `refactoring/sc-midi-javamidi-tests`)
- **Issues:** #131 and #288 (two separate issues; commit prefix `[#131][#288]`)

**Goal:** MIDI devices named in a tracks file work whether or not they are connected when the tracks are built, and
keep working across unplug / replug, without rebuilding the tracks.

**Architecture:** `MidiDeviceHandle` becomes read-only and only `JavaMidiManager` changes its state. Each manager
endpoint keeps one registry of live `JavaMidiDeviceHandle`s, reconciled on every `refresh()` under a manager-wide lock.
Handle commands return the `MidiEvent`s of their transitions, and the manager publishes them after it releases the
lock. In `tuner`, `TrackManager` subscribes to `MidiEvent`: it resets the tuner of tracks whose output device opens and
releases the output of tracks whose input device disconnects.

**Tech Stack:** Scala 3 (brace syntax), sbt 1 via `sbtn`, Java Sound + CoreMIDI4J, Guava `EventBus` (through
`Businessync`), ScalaTest 3.2 (`AnyWordSpec` in `javamidi` tests, `AnyFlatSpec` in `tuner`), ScalaMock 7.5
(`org.scalamock.stubs.Stubs` in `javamidi` tests and `TrackManagerTest`, `MockFactory` in the other `tuner` tests),
logback `LogCapture` from `common-test-utils`.

**Spec:** [`2026-09-15-hot-plug-midi-devices-design.md`](2026-09-15-hot-plug-midi-devices-design.md). It is the source
of truth; read it before any task. Section numbers below (e.g. "design 4.2") refer to it.

## Global Constraints

- Strict TDD (red → green → refactor). A red test must fail on an assertion (or a `NotImplementedError` from a `???`
  stub), never on a compile error. Never commit red production code; never mix refactoring with behaviour changes.
- Compile with the Metals MCP: `mcp__metals__compile-module` with `module` = `sc-midi`, `sc-midi-test`, `tuner` or
  `tuner-test`. Run tests only through `sbtn`, always with `-- -oNCXEHLOPQRMWS`, e.g.
  `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandleTest -- -oNCXEHLOPQRMWS"`.
- Scala conventions (`docs/development/coding-conventions.md`): brace syntax, 2-space indent, 120-column lines, no
  `new`, no `return`, ScalaDoc on every public identifier, TODOs written `// TODO #<issue>`.
- Test conventions (`docs/development/test-conventions.md`): `// Given` / `// When` / `// Then` comments, no `if`
  around assertions (an `if` in a fixture is fine), fixtures for repeated setup.
- License headers: `.scala` files get them from the pre-commit hook; do not add them by hand.
- Commits: message prefix `[#131][#288]`, ending with the line
  `Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>`. Commit on `feature/hot-plug-midi-devices`.
  Do not push. Do not use git worktrees.
- Coverage floors (`build.sbt`): `sc-midi` 80% statements / 80% branches, `tuner` 80% / 80%. Never lower a floor.
- Out of scope: #302 (react to failures before CoreMIDI4J reports them, recover a handle that failed to open), #303
  (restore the current tuning after a tuner reset), #90 (business thread), #121 (track threads).
- Plan-level decisions taken inside the design's latitude:
  - **Handle direction parameter.** The constructor parameter of `JavaMidiDeviceHandle` holding its endpoint's
    direction is named `direction`. It cannot be `endpointType`, which `MidiDeviceHandle` already defines as the
    directions the *device* works in.
  - **Logging placement** (design 4.5, "logging stays where each transition happens").
    - The handle logs every transition it makes: connected (debug), disconnected (warn), opened and closed (info),
      each failure (error), and dropped messages (warn once per open, then debug). Each of these messages names the
      handle's `direction` ("input" / "output").
    - The manager logs only what it decides itself: the environment change, a failed resolution, a device requested
      while not connected, and closing all MIDI connections.
  - **Swap on a `Connected` handle.** When a `Connected` handle is given another device instance, it only swaps the
    reference (design 4.2, "swap the device silently"). It does not close the old instance, which it never opened
    (CoreMIDI4J already closed a vanished one).

## File map

`sc-midi` (package `org.calinburloiu.music.scmidi`, under `sc-midi/src/{main,test}/scala/org/calinburloiu/music/scmidi/`):

| File | Change |
|---|---|
| `MidiDeviceHandle.scala` | Read-only trait; `isConnected` / `isOpen` derived from `state` (Task 1) |
| `MidiEvent.scala` | Seven device events gain `endpointType` (Task 2) |
| `MidiManager.scala` | ScalaDoc of the live-handle contract (Task 5) |
| `javamidi/JavaMidiDeviceHandle.scala` | `direction`; `connect` / `disconnect` / `open` / `close` / `closeAll` return events, transactional (Tasks 2–4) |
| `javamidi/JavaMidiManager.scala` | Registry of live handles, reconciliation, lock, publish after lock (Tasks 2, 3, 5) |
| test `MidiDeviceHandleTest.scala` | Test double loses `open` / `close`; state derivations (Task 1) |
| test `javamidi/JavaMidiDeviceHandleTest.scala` | Rewritten for the commands (Tasks 2–4) |
| test `javamidi/JavaMidiManagerTest.scala` | Live handles, event sequences, publishing (Tasks 2, 3, 5) |

`tuner` (package `org.calinburloiu.music.microtonalist.tuner`, under `tuner/src/{main,test}/scala/org/calinburloiu/music/microtonalist/tuner/`):

| File | Change |
|---|---|
| `Track.scala` | `close()` through the manager (Task 1); `resetTuner()`, `releaseInput()` (Task 7) |
| `TunerProcessor.scala`, `TuningChangeProcessor.scala` | Public `reset()` (Task 6) |
| `TrackManager.scala` | Clears tracks while replacing them; `MidiEvent` handler (Task 8) |
| test `FakeMidiDeviceHandle.scala` (new) | Open handle test double (Task 1) |
| test `RecordingMidiReceiver.scala` (new) | Receiver test double recording messages in order (Task 1) |
| test `TrackTest.scala`, `TunerProcessorTest.scala`, `TuningChangeProcessorTest.scala` | New behaviours (Tasks 1, 6, 7) |
| test `TrackManagerTest.scala` (new) | Event handling over a real `Businessync` (Task 8) |

Docs: `docs/architecture/sc-midi/README.md`, `docs/architecture/tuner/README.md` (Task 9).

---

### Task 1: Read-only `MidiDeviceHandle`; tracks release their devices through the manager

Design 3.1 and 5.2 (first bullet). The two changes are one task because removing `close()` from the trait breaks
`Track.close()` at compile time.

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiDeviceHandle.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala:89-97`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/MidiDeviceHandleTest.scala`
- Create: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/FakeMidiDeviceHandle.scala`
- Create: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/RecordingMidiReceiver.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala`

**Interfaces:**
- Produces: `trait MidiDeviceHandle` without `open()`, `close()` or `AutoCloseable`, with concrete
  `def isConnected: Boolean = state.isConnected` and `def isOpen: Boolean = state == MidiDeviceHandle.State.Open`.
- Produces: `JavaMidiDeviceHandle.open(): Unit` and `close(): Unit`, now `private[javamidi]` (no `override`).
- Produces (test): `class FakeMidiDeviceHandle(id: MidiDeviceId, receiver: MidiReceiver = RecordingMidiReceiver())`
  — an `Open` handle with a fresh `ConcurrentMidiTransmitter`.
- Produces (test): `class RecordingMidiReceiver extends MidiReceiver` with `messages: Seq[MidiMsg]` and `clear(): Unit`.

- [ ] **Step 1: Create the tuner test doubles**

`tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/RecordingMidiReceiver.scala`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.MidiReceiver
import org.calinburloiu.music.scmidi.message.MidiMsg

import scala.collection.mutable

/** A [[MidiReceiver]] test double that records the messages sent to it, in order. It is not thread-safe. */
class RecordingMidiReceiver extends MidiReceiver {
  private val _messages: mutable.Buffer[MidiMsg] = mutable.ArrayBuffer()

  /** The messages sent so far, in order. */
  def messages: Seq[MidiMsg] = _messages.toSeq

  /** Forgets the messages sent so far. */
  def clear(): Unit = _messages.clear()

  override def send(message: MidiMsg, timeStamp: Long): Unit = {
    _messages += message
  }
}
```

`tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/FakeMidiDeviceHandle.scala`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import org.calinburloiu.music.scmidi.{ConcurrentMidiTransmitter, MidiDeviceHandle, MidiDeviceId, MidiDeviceInfo,
  MidiReceiver}

/**
 * An open [[MidiDeviceHandle]] test double, so that a test can give a track devices through a stubbed
 * [[org.calinburloiu.music.scmidi.MidiManager]].
 *
 * @param id       The identifier of the device.
 * @param receiver Where the messages sent to the device go.
 */
class FakeMidiDeviceHandle(override val id: MidiDeviceId,
                           override val receiver: MidiReceiver = RecordingMidiReceiver()) extends MidiDeviceHandle {
  override val info: Option[MidiDeviceInfo] = None

  override val state: MidiDeviceHandle.State = MidiDeviceHandle.State.Open

  override val transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
}
```

This does not compile until Step 3, which removes `open` / `close` from the trait and makes `isConnected` / `isOpen`
concrete.

- [ ] **Step 2: Write the failing tests**

In `MidiDeviceHandleTest.scala`, replace the `InfoOnlyHandle` double with one that knows only its info and state, and
add a section. The whole class becomes:

```scala
class MidiDeviceHandleTest extends AnyFlatSpec with Matchers with TableDrivenPropertyChecks {

  /** A handle that only knows its info and its state; the members under test derive from them. */
  private class TestHandle(override val info: Option[MidiDeviceInfo] = None,
                           override val state: MidiDeviceHandle.State = MidiDeviceHandle.State.Closed)
    extends MidiDeviceHandle {
    override def id: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    override def receiver: MidiReceiver = NoOpMidiReceiver()

    override def transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  }

  private def info(transmittersLimit: MidiConnectionLimit, receiversLimit: MidiConnectionLimit): MidiDeviceInfo =
    MidiDeviceInfo("CoreMIDI4J - FP-90", "Roland", "Digital piano", "1.0", transmittersLimit, receiversLimit)

  behavior of "endpointType"

  it should "derive the directions from the info while connected and report none while disconnected" in {
    // (body unchanged, except `InfoOnlyHandle(info)` becomes `TestHandle(info = info)`)
  }

  behavior of "isConnected, isOpen and isOpenRequested"

  it should "derive from the state, the device being open for use only in Open" in {
    // Given
    val cases = Table[MidiDeviceHandle.State, Boolean, Boolean, Boolean](
      ("state", "isConnected", "isOpen", "isOpenRequested"),
      (MidiDeviceHandle.State.Closed, false, false, false),
      (MidiDeviceHandle.State.Connected, true, false, false),
      (MidiDeviceHandle.State.WaitingToOpen, false, false, true),
      (MidiDeviceHandle.State.Open, true, true, true)
    )

    forAll(cases) { (state, isConnected, isOpen, isOpenRequested) =>
      // When
      val handle = TestHandle(state = state)

      // Then
      handle.isConnected shouldBe isConnected
      handle.isOpen shouldBe isOpen
      handle.isOpenRequested shouldBe isOpenRequested
    }
  }

  behavior of "State"

  // (the existing "cover every combination …" test, unchanged)
}
```

In `TrackTest.scala`, add the imports `org.calinburloiu.music.scmidi.MidiDeviceId`, then add a device fixture after
`Fixture` and a `close` section at the end of the class:

```scala
  /** A track over an input device and an output device, both opened through a stubbed manager. */
  trait DeviceFixture {
    val inputDeviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
    val outputDeviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

    val tuner: Tuner = stub[Tuner]
    (() => tuner.reset()).when().returns(Seq(initMessage))
    tuner.tune.when(*).returns(Seq.empty)

    val outputReceiver: RecordingMidiReceiver = RecordingMidiReceiver()
    val midiManager: MidiManager = stub[MidiManager]
    midiManager.openInput.when(inputDeviceId).returns(FakeMidiDeviceHandle(inputDeviceId))
    midiManager.openOutput.when(outputDeviceId).returns(FakeMidiDeviceHandle(outputDeviceId, outputReceiver))

    val tuningService: TuningService = stub[TuningService]
    val spec: TrackSpec = TrackSpec("track", "Track", input = Some(DeviceTrackInputSpec(inputDeviceId, None)),
      tuner = Some(tuner), output = Some(DeviceTrackOutputSpec(outputDeviceId, None)))
    val track: Track = Track(spec = spec, midiManager = midiManager, tuningService = tuningService)
  }
```

```scala
  behavior of "close"

  it should "release its input and output devices through the MIDI manager" in new DeviceFixture {
    // When
    track.close()

    // Then
    midiManager.closeInput.verify(inputDeviceId).once()
    midiManager.closeOutput.verify(outputDeviceId).once()
  }

  it should "release nothing through the MIDI manager when it has no device" in new Fixture {
    // When
    track.close()

    // Then
    midiManager.closeInput.verify(*).never()
    midiManager.closeOutput.verify(*).never()
  }
```

- [ ] **Step 3: Stub the production code so that the tests compile**

In `MidiDeviceHandle.scala`: drop `extends AutoCloseable` from the trait, delete the `open()` and `close()` members
with their ScalaDoc, and replace the abstract `isConnected` / `isOpen` with `def isConnected: Boolean = ???` and
`def isOpen: Boolean = ???`.

In `JavaMidiDeviceHandle.scala`: change `override def open(): Unit` to `private[javamidi] def open(): Unit` and
`override def close(): Unit` to `private[javamidi] def close(): Unit`. Delete `override def isConnected` and
`override def isOpen`.

In `Track.scala`, delete the two lines `inputDeviceHandle.foreach(_.close())` and
`outputDeviceHandle.foreach(_.close())`.

Run `mcp__metals__compile-module` for `sc-midi-test` and `tuner-test`. Expected: success.

- [ ] **Step 4: Run the tests to verify they fail**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.MidiDeviceHandleTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. "derive from the state …" throws `NotImplementedError`.

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. "release its input and output devices …" reports that `closeInput` was never called.

- [ ] **Step 5: Implement**

In `MidiDeviceHandle.scala`, replace the trait's ScalaDoc and the stubbed members:

```scala
/**
 * Handle to a single MIDI device, identified by a [[MidiDeviceId]].
 *
 * A [[MidiManager]] creates the handles and is the only one to change their state. A consumer requests a device with
 * [[MidiManager.openInput]] or [[MidiManager.openOutput]], which return its handle, and releases it with
 * [[MidiManager.closeInput]] or [[MidiManager.closeOutput]]; both are reference-counted. The consumer only inspects the
 * handle and uses it for MIDI I/O.
 *
 * The device is not required to be connected to the system when it is requested: the manager informs the handle when
 * the device gets connected or disconnected, and [[info]] is defined only while the device is connected. A handle
 * requested while its device is not connected waits in [[MidiDeviceHandle.State.WaitingToOpen]] and opens once the
 * device gets connected.
 *
 * A handle is live while its manager holds it, which is exactly while its [[state]] is not
 * [[MidiDeviceHandle.State.Closed]]. A handle that reaches `Closed` is forgotten by its manager and stays `Closed` for
 * good: requesting the same device again returns a new handle, so a consumer must not keep a handle after it released
 * its references to it.
 *
 * A handle exposes a [[MidiReceiver]] and a [[ConcurrentMidiTransmitter]] via [[receiver]] and [[transmitter]]. They
 * can be wired while the device is disconnected or closed, in which case they do nothing; once the device becomes
 * usable, the wiring works without any change.
 *
 * [[state]] tells the current state of the handle and of its device; see [[MidiDeviceHandle.State]] for the
 * transitions. [[org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandle]] is the Java Sound implementation.
 */
trait MidiDeviceHandle {
```

```scala
  /**
   * Checks whether the MIDI device is currently connected to the system.
   *
   * @return True if the device is connected, i.e. the [[state]] is [[MidiDeviceHandle.State.Connected]] or
   *         [[MidiDeviceHandle.State.Open]]; false otherwise.
   */
  def isConnected: Boolean = state.isConnected

  /**
   * Determines if the MIDI device is currently open for use.
   *
   * @return True if the [[state]] is [[MidiDeviceHandle.State.Open]], false otherwise.
   */
  def isOpen: Boolean = state == MidiDeviceHandle.State.Open
```

In the `isOpenRequested` ScalaDoc, replace "i.e. an [[open]] transition succeeded and no [[close]] transition has
happened since" with "i.e. an `open` transition succeeded and no `close` transition has happened since".

In the ScalaDoc of `enum State`, replace `where a later [[MidiDeviceHandle.open]] cannot open an unreliable
connection.` with `where a later request to open the device waits for it to get connected again.`. In
`@param isOpenRequested`, replace `an [[MidiDeviceHandle.open]] transition succeeded and no
[[MidiDeviceHandle.close]] transition has happened since` with `an `open` transition succeeded and no `close`
transition has happened since`.

In `Track.scala`, `close()` becomes:

```scala
  override def close(): Unit = {
    logger.info(s"Closing track $id...")

    logger.info(s"Switching back to 12-EDO for track $id...")
    tune(Tuning.Standard)

    spec.input.foreach {
      case DeviceTrackInputSpec(midiDeviceId, _) => midiManager.closeInput(midiDeviceId)
      case _ => // Not a device: nothing to release
    }
    spec.output.foreach {
      case DeviceTrackOutputSpec(midiDeviceId, _) => midiManager.closeOutput(midiDeviceId)
      case _ => // Not a device: nothing to release
    }
  }
```

In the `Track` class ScalaDoc, change `@param midiManager` to: "Used to open the input and output MIDI devices named
by the spec, and to release them when the track is closed."

- [ ] **Step 6: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"` and `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. The ignored `TODO #288` / `TODO #131` tests stay ignored.

- [ ] **Step 7: Commit**

```bash
git add sc-midi/src tuner/src
git commit -m "$(cat <<'EOF'
[#131][#288] Make MidiDeviceHandle read-only and release track devices through the manager

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Device events carry the direction of their endpoint

Design 3.3 (first three bullets) and 4.1 (second bullet).

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiEvent.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandleTest.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManagerTest.scala`

**Interfaces:**
- Consumes: Task 1's `JavaMidiDeviceHandle` (`open` / `close` are `private[javamidi]`).
- Produces: `MidiDeviceConnectedEvent(deviceId: MidiDeviceId, endpointType: MidiEndpointType)`, and likewise
  `MidiDeviceDisconnectedEvent`, `MidiDeviceOpenedEvent`, `MidiDeviceClosedEvent`. The failure events are
  `MidiDeviceFailedToDisconnectEvent(deviceId, endpointType, cause: Exception)`, and likewise
  `MidiDeviceFailedToOpenEvent` and `MidiDeviceFailedToCloseEvent`. `MidiDeviceFailedToConnectEvent(deviceId, cause)`
  is unchanged.
- Produces: `JavaMidiDeviceHandle private[javamidi](id: MidiDeviceId, direction: MidiEndpointType, businessync:
  Businessync)`, with `private[javamidi] val direction`. Task 3 removes `businessync`.

- [ ] **Step 1: Write the failing tests**

In `JavaMidiManagerTest.scala`:
- Add `val endpointType: MidiEndpointType` to `trait Direction`. Implement it as `MidiEndpointType.Input` in
  `object Input` and `MidiEndpointType.Output` in `object Output`.
- In `deviceEndpoint`, add a second argument to every device event: `MidiDeviceConnectedEvent(id)` becomes
  `MidiDeviceConnectedEvent(id, direction.endpointType)`, and the same for the `Disconnected` and `Opened` events.
- In the `refresh`, `An environment change` and other sections, pass the direction the test uses:
  `MidiEndpointType.Input` for `Input.newDevice` / `openInput` and `MidiEndpointType.Output` for `Output.newDevice` /
  `openOutput`.
- Rename "list the input and the output endpoint of one physical device under the same id" to "list the input and the
  output endpoint of one physical device under the same id, reporting each direction". Delete its comment line
  `// The event carry no direction …`, and expect:

```scala
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(id, MidiEndpointType.Input),
        MidiDeviceConnectedEvent(id, MidiEndpointType.Output)
      )
```

In `JavaMidiDeviceHandleTest.scala`:
- The fixture creates `JavaMidiDeviceHandle(deviceId, MidiEndpointType.Output, businessync)`.
- Every failure event gets `MidiEndpointType.Output` as its second argument, e.g.
  `MidiDeviceFailedToCloseEvent(deviceId, MidiEndpointType.Output, failure)`, including those inside `ignore`d tests.

- [ ] **Step 2: Add the fields, with a placeholder direction**

In `MidiEvent.scala`, add `endpointType: MidiEndpointType` after `deviceId` in the seven events. In
`JavaMidiDeviceHandle`, add the constructor parameter `private[javamidi] val direction: MidiEndpointType` between `id`
and `businessync`. In `JavaMidiManager.MidiEndpoint.openDevice`, create the handle with
`JavaMidiDeviceHandle(deviceId, endpointType, businessync)`.

As the red-phase stub, every event construction in `JavaMidiManager` and `JavaMidiDeviceHandle` passes
`MidiEndpointType.None` as its `endpointType`. Compile `sc-midi-test`. Expected: success.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.* -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The event assertions report `none` where `input` / `output` is expected.

- [ ] **Step 4: Implement**

Replace the placeholder:
- In `JavaMidiManager.MidiEndpoint`, pass `endpointType`: `MidiDeviceConnectedEvent(id, endpointType)`,
  `MidiDeviceDisconnectedEvent(previousId, endpointType)`, `MidiDeviceClosedEvent(deviceId, endpointType)` and
  `MidiDeviceOpenedEvent(deviceId, endpointType)`.
- In `JavaMidiDeviceHandle`, pass `direction` to the three failure events.

Add `require` as the first statement of the `JavaMidiDeviceHandle` class body:

```scala
  require(direction == MidiEndpointType.Input || direction == MidiEndpointType.Output,
    s"The direction of a JavaMidiDeviceHandle must be input or output; got $direction!")
```

with this test in `"A new JavaMidiDeviceHandle" should` (add the import
`org.scalatest.prop.TableDrivenPropertyChecks` and mix it into the class):

```scala
    "reject a direction other than input or output" in {
      // Given
      val directions = Table("direction", MidiEndpointType.None, MidiEndpointType.InputOutput)

      forAll(directions) { direction =>
        // When / Then
        an[IllegalArgumentException] should be thrownBy
          JavaMidiDeviceHandle(deviceId, direction, stub[Businessync])
      }
    }
```

(Write this test before the `require` and watch it fail, as a mini red/green cycle.)

ScalaDoc:
- In `JavaMidiDeviceHandle`, add
  `@param direction The direction of the endpoint of the manager that owns the handle, [[MidiEndpointType.Input]] or [[MidiEndpointType.Output]], which the events of the handle carry as their `endpointType`. It is not [[endpointType]], which tells the directions the device works in.`
- Replace the ScalaDoc of `MidiEvent` with:

```scala
/**
 * Base class for all MIDI events emitted by a [[MidiManager]] implementation.
 *
 * A device event identifies its device by [[MidiDeviceId]] and, except for [[MidiDeviceFailedToConnectEvent]], tells
 * in its `endpointType` the direction of the endpoint it concerns, always [[MidiEndpointType.Input]] or
 * [[MidiEndpointType.Output]]. A [[MidiManager]] keeps the two directions apart, because the platform may expose one
 * physical device once per direction (see [[MidiManager]]), so a device that works in both directions is reported
 * once for each of them, by two events that differ in their `endpointType`.
 *
 * Each event reports one transition of one handle, and a failure event replaces its success event.
 */
```

- On each of the seven events, add
  `@param endpointType The direction of the endpoint whose handle made the transition: [[MidiEndpointType.Input]] or [[MidiEndpointType.Output]], never another value.`
- On `MidiDeviceFailedToConnectEvent`, add a sentence: "It carries no direction: it is published when resolving the
  device fails, before its direction is known."

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"` and `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src
git commit -m "$(cat <<'EOF'
[#131][#288] Report the endpoint direction in the MIDI device events

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `JavaMidiDeviceHandle` transitions return their events and are transactional

Design 4.3, 4.4, 4.5 (the handle half). The handle stops publishing. Its five `private[javamidi]` commands return the
events of their transitions, and every failure completes the transition it concerns. Task 5 rewrites
`JavaMidiManager`; this task changes it only enough to keep it working over the new commands.

**Files:**
- Modify (rewrite): `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala` (`MidiEndpoint.openDevice`, `closeDevice`)
- Test (rewrite): `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandleTest.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManagerTest.scala` (one logging test)

**Interfaces:**
- Consumes: Task 2's events with `endpointType`, and `direction`.
- Produces: `JavaMidiDeviceHandle private[javamidi](id: MidiDeviceId, direction: MidiEndpointType)`. There is no
  `businessync` any more. Its commands, all `private[javamidi]` and all returning the events in order:
  - `connect(info: MidiDeviceInfo, device: MidiDevice): Seq[MidiEvent]`: throws `IllegalArgumentException` for an info
    of another id.
  - `disconnect(): Seq[MidiEvent]`
  - `open(): Seq[MidiEvent]`
  - `close(): Seq[MidiEvent]`
  - `closeAll(): Seq[MidiEvent]`
- `onConnect` and `onDisconnect` no longer exist.

**Transitions the tests pin** (`Output` direction in the fixture; `C` = `MidiDeviceConnectedEvent`, `D` =
`…DisconnectedEvent`, `O` = `…OpenedEvent`, `X` = `…ClosedEvent`, `F…` = the matching failure event):

| Command | From | To | Events |
|---|---|---|---|
| `connect(d)` | `Closed` | `Connected` | `C` |
| `connect(d)` | `WaitingToOpen` | `Open`, or `Connected` with 0 references on failure | `C`, then `O` or `FOpen` |
| `connect(same)` | `Connected` / `Open` | unchanged (info updated) | — |
| `connect(other)` | `Connected` | `Connected` (reference swapped, old device untouched) | — |
| `connect(other)` | `Open` | `Open`, or `Connected` with 0 references on failure | `X` or `FClose`, then `O` or `FOpen` |
| `disconnect()` | `Open` | `WaitingToOpen` | `X`, `D`; or only `FDisconnect` |
| `disconnect()` | `Connected` | `Closed` | `D`; or `FDisconnect` |
| `disconnect()` | `Closed` / `WaitingToOpen` | unchanged | — |
| `open()` (first reference) | `Closed` | `WaitingToOpen` | — |
| `open()` (first reference) | `Connected` | `Open`, or `Connected` with 0 references on failure | `O` or `FOpen` |
| `close()` (last reference) / `closeAll()` | `Open` | `Connected` | `X` or `FClose` |
| `close()` (last reference) / `closeAll()` | `WaitingToOpen` | `Closed` | — |
| `close()` / `closeAll()` with no reference held | any | unchanged | — |

- [ ] **Step 1: Write the failing tests**

Replace the whole body of `JavaMidiDeviceHandleTest.scala` (keep the license header) with:

```scala
package org.calinburloiu.music.scmidi.javamidi

import ch.qos.logback.classic.Level
import org.calinburloiu.music.microtonalist.common.LogCapture
import org.calinburloiu.music.microtonalist.common.LogCapture.*
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{CcMidiMsg, NoteOnMidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec

import javax.sound.midi.{MidiUnavailableException, ShortMessage}

class JavaMidiDeviceHandleTest extends AnyWordSpec with Matchers with TableDrivenPropertyChecks with Stubs {

  private val deviceId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")

  private val direction: MidiEndpointType = MidiEndpointType.Output

  private val failure: Exception = MidiUnavailableException("The device is busy")

  private val connected: MidiEvent = MidiDeviceConnectedEvent(deviceId, direction)
  private val disconnected: MidiEvent = MidiDeviceDisconnectedEvent(deviceId, direction)
  private val opened: MidiEvent = MidiDeviceOpenedEvent(deviceId, direction)
  private val closed: MidiEvent = MidiDeviceClosedEvent(deviceId, direction)

  private val noteOn: NoteOnMidiMsg = NoteOnMidiMsg(2, MidiNote.C4, 100)

  /** A second note on of the same pitch, with the zero velocity that conventionally releases it. */
  private val secondNoteOn: NoteOnMidiMsg = NoteOnMidiMsg(2, MidiNote.C4, 0)

  /** The sustain pedal (CC 64) pressed, as the tests expect it converted from Java Sound. */
  private val sustainOn: CcMidiMsg = CcMidiMsg(3, 64, 127)

  /** The sustain pedal (CC 64) released, as the tests expect it converted from Java Sound. */
  private val sustainOff: CcMidiMsg = CcMidiMsg(3, 64, 0)

  /**
   * An output handle over [[device]], which is not yet connected to it. The parameters configure the device.
   */
  private abstract class Fixture(maxTransmitters: Int = -1,
                                 maxReceivers: Int = -1,
                                 openFailure: Option[Exception] = None,
                                 closeFailure: Option[Exception] = None,
                                 receiverFailure: Option[Exception] = None) {
    val handle: JavaMidiDeviceHandle = JavaMidiDeviceHandle(deviceId, direction)
    val device: FakeMidiDevice = FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = maxTransmitters,
      maxReceivers = maxReceivers, openFailure = openFailure, closeFailure = closeFailure,
      receiverFailure = receiverFailure)

    /** Informs the handle that `connectedDevice` got connected, as [[JavaMidiManager]] does. */
    def connect(connectedDevice: FakeMidiDevice = device): Seq[MidiEvent] =
      handle.connect(connectedDevice.asMidiDeviceInfo, connectedDevice)

    /** Another instance of the device, as CoreMIDI4J creates one when the device is replugged or swapped. */
    def newDevice(): FakeMidiDevice = FakeMidiDevice(deviceId.name, deviceId.vendor)
  }

  "A new JavaMidiDeviceHandle" should {
    "be closed and disconnected, with no device and no info" in new Fixture {
      // Then
      handle.id shouldEqual deviceId
      handle.direction shouldEqual direction
      handle.state shouldEqual State.Closed
      handle.isConnected shouldBe false
      handle.isOpen shouldBe false
      handle.isOpenRequested shouldBe false
      handle.device shouldBe empty
      handle.info shouldBe empty
    }

    "reject a direction other than input or output" in {
      // Given
      val directions = Table("direction", MidiEndpointType.None, MidiEndpointType.InputOutput)

      forAll(directions) { direction =>
        // When / Then
        an[IllegalArgumentException] should be thrownBy JavaMidiDeviceHandle(deviceId, direction)
      }
    }
  }

  "connect" should {
    "move a closed handle to Connected, exposing the device and its info without opening it" in new Fixture {
      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldEqual Seq(connected)
      handle.state shouldEqual State.Connected
      handle.device shouldEqual Some(device)
      handle.info shouldEqual Some(device.asMidiDeviceInfo)
      device.openCount shouldEqual 0
    }

    "open the device of a handle waiting to open" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldEqual Seq(connected, opened)
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
    }

    "roll a handle waiting to open back to Connected, with no reference held, when its device fails to open" in
      new Fixture(openFailure = Some(failure)) {
        // Given
        handle.open()

        // When
        val events: Seq[MidiEvent] = connect()

        // Then
        events shouldEqual Seq(connected, MidiDeviceFailedToOpenEvent(deviceId, direction, failure))
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        handle.close() shouldBe empty
      }

    "change nothing when a connected handle is given the same device again" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      device.closeCount shouldEqual 0
    }

    "change nothing when an open handle is given the same device again" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = connect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
      device.openCount shouldEqual 1
      device.closeCount shouldEqual 0
    }

    "swap the device of a connected handle silently" in new Fixture {
      // Given
      val swappedDevice: FakeMidiDevice = newDevice()
      connect()

      // When
      val events: Seq[MidiEvent] = connect(swappedDevice)

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      handle.device shouldEqual Some(swappedDevice)
      device.closeCount shouldEqual 0
    }

    "close the device of an open handle and open the one it is swapped for, obtaining a new receiver" in new Fixture {
      // Given
      val swappedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = connect(swappedDevice)
      handle.receiver.send(noteOn, 42L)

      // Then
      events shouldEqual Seq(closed, opened)
      handle.state shouldEqual State.Open
      device.isOpen shouldBe false
      swappedDevice.isOpen shouldBe true
      swappedDevice.receivedMessages.map { case (message, timeStamp) => (message.asScala, timeStamp) } shouldEqual
        Seq((noteOn, 42L))
    }

    "reject the info of another device, leaving the handle unchanged" in new Fixture {
      // Given
      val otherDevice: FakeMidiDevice = FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI")

      // When / Then
      an[IllegalArgumentException] should be thrownBy handle.connect(otherDevice.asMidiDeviceInfo, otherDevice)
      handle.state shouldEqual State.Closed
      handle.isConnected shouldBe false
    }
  }

  "disconnect" should {
    "close the device of an open handle and forget it along with its info, waiting to open it again" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldEqual Seq(closed, disconnected)
      device.isOpen shouldBe false
      handle.state shouldEqual State.WaitingToOpen
      handle.isOpenRequested shouldBe true
      handle.device shouldBe empty
      handle.info shouldBe empty
    }

    "move a connected handle to Closed, closing the device it did not open" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldEqual Seq(disconnected)
      handle.state shouldEqual State.Closed
      handle.device shouldBe empty
      device.closeCount shouldEqual 1
    }

    "report only a failure to disconnect a connected handle whose device fails to close, and still close it" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.disconnect()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToDisconnectEvent(deviceId, direction, failure))
        handle.state shouldEqual State.Closed
        handle.device shouldBe empty
      }

    "report only a failure to disconnect an open handle whose device fails to close, and still make it wait" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()
        handle.open()

        // When
        val events: Seq[MidiEvent] = handle.disconnect()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToDisconnectEvent(deviceId, direction, failure))
        handle.state shouldEqual State.WaitingToOpen
      }

    "make an open handle open the device it gets when it reconnects" in new Fixture {
      // Given
      val repluggedDevice: FakeMidiDevice = newDevice()
      connect()
      handle.open()
      handle.disconnect()

      // When
      val events: Seq[MidiEvent] = connect(repluggedDevice)

      // Then
      events shouldEqual Seq(connected, opened)
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
    }

    "change nothing on a handle that is not connected" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.disconnect()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.WaitingToOpen
    }
  }

  "open" should {
    "make a disconnected handle wait to open, without opening anything" in new Fixture {
      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.WaitingToOpen
      handle.isOpenRequested shouldBe true
      device.openCount shouldEqual 0
    }

    "open the device of a connected handle" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldEqual Seq(opened)
      handle.state shouldEqual State.Open
      device.isOpen shouldBe true
    }

    "open the device only on the first of several calls" in new Fixture {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.open()

      // Then
      events shouldBe empty
      device.openCount shouldEqual 1
      handle.state shouldEqual State.Open
    }

    "subscribe to the device's transmitter when the device is an input" in new Fixture {
      // Given
      connect()

      // When
      handle.open()

      // Then
      Option(device.transmitter.getReceiver) shouldBe defined
    }

    "not subscribe to the device's transmitter when the device is not an input" in new Fixture(maxTransmitters = 0) {
      // Given
      connect()

      // When
      handle.open()

      // Then
      handle.state shouldEqual State.Open
      Option(device.transmitter.getReceiver) shouldBe empty
    }

    "roll back to Connected, closing the device, with no reference held, when the device fails to open" in
      new Fixture(openFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.open()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, direction, failure))
        handle.state shouldEqual State.Connected
        handle.isOpenRequested shouldBe false
        device.closeCount shouldEqual 1
        handle.close() shouldBe empty
      }

    "roll back to Connected when the device fails to provide a receiver, dropping the messages sent" in
      new Fixture(receiverFailure = Some(failure)) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.open()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, direction, failure))
        handle.state shouldEqual State.Connected
        device.isOpen shouldBe false

        // When / Then
        noException should be thrownBy handle.receiver.send(noteOn, 42L)
        device.receivedMessages shouldBe empty
      }

    "report only the failure to open when closing the device on the way back fails too" in
      new Fixture(openFailure = Some(failure), closeFailure = Some(IllegalStateException("Cannot close"))) {
        // Given
        connect()

        // When
        val events: Seq[MidiEvent] = handle.open()

        // Then
        events shouldEqual Seq(MidiDeviceFailedToOpenEvent(deviceId, direction, failure))
        handle.state shouldEqual State.Connected
      }
  }

  "close" should {
    "close the device on the last of several closes only, returning the handle to Connected" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.open()

      // When
      val firstEvents: Seq[MidiEvent] = handle.close()

      // Then
      firstEvents shouldBe empty
      device.isOpen shouldBe true
      handle.state shouldEqual State.Open

      // When
      val lastEvents: Seq[MidiEvent] = handle.close()

      // Then
      lastEvents shouldEqual Seq(closed)
      device.isOpen shouldBe false
      handle.state shouldEqual State.Connected
    }

    "return a handle waiting to open to Closed, so that connecting the device no longer opens it" in new Fixture {
      // Given
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Closed

      // When
      val connectionEvents: Seq[MidiEvent] = connect()

      // Then
      connectionEvents shouldEqual Seq(connected)
      device.openCount shouldEqual 0
    }

    "report a failure to close the device, and still move to Connected" in new Fixture(closeFailure = Some(failure)) {
      // Given
      connect()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldEqual Seq(MidiDeviceFailedToCloseEvent(deviceId, direction, failure))
      handle.state shouldEqual State.Connected
    }

    "do nothing when no reference is held" in new Fixture {
      // Given
      connect()

      // When
      val events: Seq[MidiEvent] = handle.close()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Connected
      device.closeCount shouldEqual 0
    }
  }

  "closeAll" should {
    "release every reference, closing the device" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.closeAll()

      // Then
      events shouldEqual Seq(closed)
      handle.state shouldEqual State.Connected
      device.isOpen shouldBe false
      handle.close() shouldBe empty
    }

    "return a handle waiting to open to Closed" in new Fixture {
      // Given
      handle.open()
      handle.open()

      // When
      val events: Seq[MidiEvent] = handle.closeAll()

      // Then
      events shouldBe empty
      handle.state shouldEqual State.Closed
    }

    "do nothing when no reference is held" in new Fixture {
      // Given
      connect()

      // When / Then
      handle.closeAll() shouldBe empty
      handle.state shouldEqual State.Connected
    }
  }

  "receiver" should {
    // Keep these six tests of the current file unchanged:
    //   "convert each message to Java Sound and send it to the open device, with its time stamp"
    //   "obtain a single receiver from the device for all the messages it sends while the device is open"
    //   "send to the open device again after the device was closed and opened again"
    //   "drop the messages sent while the device is connected but not open"
    //   "drop the messages sent before the device is connected, instead of delivering them once it opens"
    //   "not ask a device that is not an output for a receiver"

    "drop the messages sent after the device was closed" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.close()

      // When / Then
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }

    "drop the messages when the device opened again fails to provide a receiver" in new Fixture {
      // Given
      connect()
      handle.open()
      handle.close()
      device.receiverFailure = Some(failure)

      // When
      handle.open()

      // Then
      handle.state shouldEqual State.Connected
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }
  }

  "transmitter" should {
    // Keep both tests of the current file unchanged.
  }

  "Logging" should {
    val loggerName = classOf[JavaMidiDeviceHandle].getName

    "report the connection of the device at debug level, with the connection limit of its direction" in {
      // Given
      val inputDevice = FakeMidiDevice("CoreMIDI4J - Seaboard", "ROLI", maxTransmitters = 2, maxReceivers = 0)
      val outputDevice = FakeMidiDevice(deviceId.name, deviceId.vendor, maxTransmitters = 0, maxReceivers = -1)
      val inputHandle = JavaMidiDeviceHandle(inputDevice.id, MidiEndpointType.Input)
      val outputHandle = JavaMidiDeviceHandle(outputDevice.id, MidiEndpointType.Output)

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        inputHandle.connect(inputDevice.asMidiDeviceInfo, inputDevice)
        outputHandle.connect(outputDevice.asMidiDeviceInfo, outputDevice)
      }

      // Then
      events.messagesAt(Level.DEBUG) shouldEqual Seq(
        """Input device "CoreMIDI4J - Seaboard" (ROLI) with 2 transmitters was connected.""",
        """Output device "CoreMIDI4J - FP-90" (Roland) with unlimited receivers was connected."""
      )
    }

    "report the disconnection of the device at warn level" in new Fixture {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.disconnect()
      }

      // Then
      events.messagesAt(Level.WARN) shouldEqual Seq("""Output device "CoreMIDI4J - FP-90" (Roland) was disconnected.""")
    }

    "report the opening and the closing of the device at info level" in new Fixture {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.open()
        handle.close()
      }

      // Then
      events.messagesAt(Level.INFO) shouldEqual Seq(
        """Successfully opened output device "CoreMIDI4J - FP-90" (Roland).""",
        """Successfully closed output device "CoreMIDI4J - FP-90" (Roland)."""
      )
    }

    "report a failure to open the device at error level, with its cause" in new Fixture(openFailure = Some(failure)) {
      // Given
      connect()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.open()
      }

      // Then
      events.failuresAt(Level.ERROR) shouldEqual
        Seq(("""Failed to open output device "CoreMIDI4J - FP-90" (Roland).""", Some(failure.getMessage)))
    }

    "report a failure to close the device at error level, with its cause" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()
        handle.open()

        // When
        val (_, events) = LogCapture.capturing(loggerName) {
          handle.close()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual
          Seq(("""Failed to close output device "CoreMIDI4J - FP-90" (Roland)!""", Some(failure.getMessage)))
      }

    "report a failure to disconnect from the device at error level, with its cause" in
      new Fixture(closeFailure = Some(failure)) {
        // Given
        connect()

        // When
        val (_, events) = LogCapture.capturing(loggerName) {
          handle.disconnect()
        }

        // Then
        events.failuresAt(Level.ERROR) shouldEqual Seq((
          """Failed to disconnect from output device "CoreMIDI4J - FP-90" (Roland)!""",
          Some(failure.getMessage)
        ))
      }
  }
}
```

In each test of the `receiver` and `transmitter` sections that is kept unchanged, the fixture's `connect()` now
returns events; ignoring them is fine. If an unchanged test called `handle.onConnect`, call `connect()` instead. Drop
the `Stub[Businessync]` fixture fields; `Stubs` stays for the `Stub[MidiReceiver]`s of the `transmitter` tests.

In `JavaMidiManagerTest.scala`, the manager no longer logs the opening and the closing of each device. Rename
"report the opening and the closing of devices at info level" to "report the closing of the MIDI connections at info
level" and expect:

```scala
      events.messagesAt(Level.INFO) shouldEqual Seq("Closing MIDI connections...", "Finished closing MIDI connections.")
```

- [ ] **Step 2: Stub the commands so that the tests compile**

In `JavaMidiDeviceHandle`:
- Remove the `businessync` constructor parameter and its import.
- Rename `onConnect` to `connect` and `onDisconnect` to `disconnect`.
- Change the return type of `connect`, `disconnect`, `open` and `close` to `Seq[MidiEvent]`, each with a `???` body.
- Add `private[javamidi] def closeAll(): Seq[MidiEvent] = ???`.

In `JavaMidiManager.MidiEndpoint`:
- `openDevice` creates `JavaMidiDeviceHandle(deviceId, endpointType)` and calls `deviceHandle.connect(…)` and
  `deviceHandle.open()`.
- `closeDevice` calls `openedDevice.close()`.

Compile `sc-midi-test`. Expected: success.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.* -- -oNCXEHLOPQRMWS"`
Expected: FAIL.
- Most `JavaMidiDeviceHandleTest` tests throw `NotImplementedError`.
- The two tests that do not touch the commands pass: "be closed and disconnected …" and "reject a direction …".
- `JavaMidiManagerTest` fails too, while the commands are stubs.

- [ ] **Step 4: Implement the handle**

Replace the body of `JavaMidiDeviceHandle.scala` (keep the license header) with:

```scala
package org.calinburloiu.music.scmidi.javamidi

import com.typesafe.scalalogging.LazyLogging
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*
import org.calinburloiu.music.scmidi.message.{Midi1Msg, Midi2Msg, MidiMsg}

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.ThreadSafe
import javax.sound.midi.{MidiDevice, MidiMessage, Receiver}
import scala.util.Try

/**
 * [[MidiDeviceHandle]] over a Java Sound [[MidiDevice]], for one direction of it.
 *
 * [[JavaMidiManager]] creates the instances, one per device per direction, and is the only one to change their state.
 * It does so through five commands named after the transitions of [[MidiDeviceHandle.State]]:
 *
 *   - [[connect]], with the device it resolved, when the physical device gets connected;
 *   - [[disconnect]] when the device gets disconnected;
 *   - [[open]] and [[close]], which take and release one reference to the device;
 *   - [[closeAll]], which releases every reference.
 *
 * Only while the device is connected are the [[MidiDevice]], via the [[device]] accessor, and the [[MidiDeviceInfo]],
 * via the [[info]] accessor, defined on the instance.
 *
 * A command publishes nothing: it returns the [[MidiEvent]]s of the transitions it made, in order, for the manager to
 * publish once it released its lock. A transition that fails still completes, setting to false the property it
 * concerns as [[MidiDeviceHandle.State]] describes, and reports the failure event instead of the success event.
 *
 * The handle is the only place where messages cross between the Scala model and Java Sound:
 *
 *   - [[receiver]] converts each [[Midi1Msg]] with `asJava` and sends it to the open device. A [[Midi2Msg]] is
 *     dropped with a warning, since a Java Sound device speaks MIDI 1.0 only.
 *   - The Java `Receiver` registered on the device's transmitter converts with `asScala` and fans out to the
 *     receivers of [[transmitter]].
 *
 * Sending never takes the lock of the handle.
 *
 * @param id        Unique identifier of the MIDI device.
 * @param direction The direction of the endpoint of the manager that owns the handle, [[MidiEndpointType.Input]] or
 *                  [[MidiEndpointType.Output]], which the events of the handle carry as their `endpointType`. It is
 *                  not [[endpointType]], which tells the directions the device works in.
 */
@ThreadSafe
class JavaMidiDeviceHandle private[javamidi](override val id: MidiDeviceId,
                                             private[javamidi] val direction: MidiEndpointType)
  extends MidiDeviceHandle, Locking, LazyLogging {
  require(direction == MidiEndpointType.Input || direction == MidiEndpointType.Output,
    s"The direction of a JavaMidiDeviceHandle must be input or output; got $direction!")

  private implicit val lock: Lock = ReentrantLock()

  @volatile private var _info: Option[MidiDeviceInfo] = None
  @volatile private var _device: Option[MidiDevice] = None
  /**
   * The receiver obtained from the device when it was last opened, if it is an output. A Java Sound device creates a
   * new receiver on each `getReceiver` call and keeps it until it is closed, so the handle obtains a single one per
   * open; closing the device closes it. It is defined only while the handle is open, which is what [[receiver]]
   * relies on to send without taking the lock.
   */
  @volatile private var deviceReceiver: Option[Receiver] = None

  private var _state: State = State.Closed

  private var openRefCount: Int = 0

  private lazy val _receiver: HandleReceiver = HandleReceiver()
  private lazy val _transmitter: ConcurrentMidiTransmitter = ConcurrentMidiTransmitter()
  private lazy val splitter: MidiSplitter = MidiSplitter(_transmitter)

  /** Inbound boundary: the Java receiver handed to the device's transmitter converts and fans out. */
  private lazy val inboundReceiver: Receiver = new Receiver {
    override def send(message: MidiMessage, timeStamp: Long): Unit = splitter.send(message.asScala, timeStamp)

    override def close(): Unit = {}
  }

  /** Outbound boundary: converts to Java Sound and sends to the open device. */
  private class HandleReceiver extends MidiReceiver {
    override def send(message: MidiMsg, timeStamp: Long): Unit = message match {
      case midi1Message: Midi1Msg =>
        deviceReceiver.foreach(_.send(midi1Message.asJava, timeStamp))
      case midi2Message: Midi2Msg =>
        logger.warn(s"Dropping $midi2Message sent to device $id: Java Sound devices speak MIDI 1.0 only.")
    }
  }

  override def info: Option[MidiDeviceInfo] = _info

  /**
   * Retrieves the Java Sound device behind this handle. It is `private[javamidi]`, a member of the implementation
   * only and never part of the [[MidiDeviceHandle]] API, so that no `javax.sound.midi` type escapes through it.
   *
   * @return The MIDI device while it is connected; otherwise, None.
   */
  private[javamidi] def device: Option[MidiDevice] = _device

  override def state: State = withLock {
    _state
  }

  override def receiver: MidiReceiver = _receiver

  override def transmitter: ConcurrentMidiTransmitter = _transmitter

  /**
   * Informs the handle that its device is connected to the system, as the latest environment scan resolved it.
   *
   * On a handle that is not connected, this is the `connect` transition: a [[State.Closed]] handle moves to
   * [[State.Connected]], and a [[State.WaitingToOpen]] handle opens the device. On a connected handle, the same
   * `device` instance only updates the info. Another instance means the device was replugged or swapped between two
   * scans: a [[State.Connected]] handle swaps it silently, and a [[State.Open]] handle closes the device it held and
   * opens the new one.
   *
   * @param info   Information about the connected MIDI device.
   * @param device The resolved Java Sound device, which [[JavaMidiManager]] obtains once per environment scan.
   * @return the events of the transitions made, in order.
   * @throws IllegalArgumentException if `info` does not correspond to the [[id]] of the handle, which is then left
   *                                  unchanged.
   */
  private[javamidi] def connect(info: MidiDeviceInfo, device: MidiDevice): Seq[MidiEvent] = withLock {
    require(id.correspondsToInfo(info), s"The given MidiDeviceInfo $info does not correspond to the " +
      s"JavaMidiDeviceHandle $id!")

    val previousDevice = _device
    _info = Some(info)
    _device = Some(device)

    (_state, previousDevice) match {
      case (State.Closed, _) =>
        _state = State.Connected
        logConnected(info)
        Seq(MidiDeviceConnectedEvent(id, direction))
      case (State.WaitingToOpen, _) =>
        logConnected(info)
        MidiDeviceConnectedEvent(id, direction) +: doOpen(device)
      case (State.Open, Some(openDevice)) if openDevice ne device =>
        closeOpenDevice(openDevice) ++ doOpen(device)
      case _ =>
        Seq.empty
    }
  }

  /**
   * Informs the handle that its device got disconnected from the system.
   *
   * The handle closes the device, whether or not it opened it, since closing a Java Sound device that is not open, or
   * that CoreMIDI4J already closed, is harmless. It then forgets the device and its info:
   *
   *   - an [[State.Open]] handle moves to [[State.WaitingToOpen]], keeping its references;
   *   - a [[State.Connected]] handle moves to [[State.Closed]];
   *   - a handle that is not connected is left unchanged.
   *
   * @return the events of the transitions made, in order: [[MidiDeviceClosedEvent]] for a handle that was open, then
   *         [[MidiDeviceDisconnectedEvent]]; or only [[MidiDeviceFailedToDisconnectEvent]] if closing the device fails,
   *         in which case the handle still ends up disconnected.
   */
  private[javamidi] def disconnect(): Seq[MidiEvent] = withLock {
    _device match {
      case Some(device) =>
        val wasOpen = _state == State.Open
        _state = if (wasOpen) State.WaitingToOpen else State.Closed
        deviceReceiver = None
        _device = None
        _info = None

        try {
          device.close()
          logger.warn(s"${direction.toString.capitalize} device $id was disconnected.")
          val closedEvents = if (wasOpen) Seq(MidiDeviceClosedEvent(id, direction)) else Seq.empty
          closedEvents :+ MidiDeviceDisconnectedEvent(id, direction)
        } catch {
          case exception: Exception =>
            logger.error(s"Failed to disconnect from $direction device $id!", exception)
            Seq(MidiDeviceFailedToDisconnectEvent(id, direction, exception))
        }
      case None =>
        Seq.empty
    }
  }

  /**
   * Takes one reference to the device. Only the first reference makes a transition: a [[State.Connected]] handle
   * opens the device, and a [[State.Closed]] handle moves to [[State.WaitingToOpen]], to open the device once it gets
   * connected.
   *
   * @return the event of the transition made: [[MidiDeviceOpenedEvent]], or [[MidiDeviceFailedToOpenEvent]] if the
   *         device fails to open; nothing if the device is not connected or if a reference was already held.
   * @see `MidiDevice.open()` from the Java MIDI API, which is called by this method to open the device.
   */
  private[javamidi] def open(): Seq[MidiEvent] = withLock {
    openRefCount += 1
    if (openRefCount > 1) {
      Seq.empty
    } else {
      _device match {
        case Some(device) =>
          doOpen(device)
        case None =>
          _state = State.WaitingToOpen
          Seq.empty
      }
    }
  }

  /**
   * Releases one reference to the device. Only releasing the last reference makes a transition, as [[closeAll]]
   * describes. With no reference held, it does nothing.
   *
   * @return the events of the transition made, as for [[closeAll]].
   * @see `MidiDevice.close()` from the Java MIDI API, which is called by this method to close the device.
   */
  private[javamidi] def close(): Seq[MidiEvent] = withLock {
    if (openRefCount > 1) {
      openRefCount -= 1
      Seq.empty
    } else {
      closeAll()
    }
  }

  /**
   * Releases every reference held to the device: an [[State.Open]] handle closes the device and moves to
   * [[State.Connected]], and a [[State.WaitingToOpen]] handle moves to [[State.Closed]]. With no reference held, it
   * does nothing.
   *
   * @return the event of the transition made: [[MidiDeviceClosedEvent]], or [[MidiDeviceFailedToCloseEvent]] if the
   *         device fails to close, in which case the handle still moves to [[State.Connected]]; nothing otherwise.
   */
  private[javamidi] def closeAll(): Seq[MidiEvent] = withLock {
    if (openRefCount == 0) {
      Seq.empty
    } else {
      openRefCount = 0
      _device match {
        case Some(device) =>
          _state = State.Connected
          closeOpenDevice(device)
        case None =>
          _state = State.Closed
          Seq.empty
      }
    }
  }

  /**
   * Opens `device`, obtaining the receiver of an output and subscribing to the transmitter of an input, and only then
   * moves to [[State.Open]]. On any failure, it closes the device as far as it can and rolls back to
   * [[State.Connected]] with no reference held, so that the handle is no longer requested to open.
   */
  private def doOpen(device: MidiDevice): Seq[MidiEvent] = {
    try {
      device.open()
      if (isOutputDevice) {
        deviceReceiver = Some(device.getReceiver)
      }
      if (isInputDevice) {
        device.getTransmitter.setReceiver(inboundReceiver)
      }

      _state = State.Open
      logger.info(s"Successfully opened $direction device $id.")
      Seq(MidiDeviceOpenedEvent(id, direction))
    } catch {
      case exception: Exception =>
        deviceReceiver = None
        // The failure to report is the one to open: a failure to close the device on the way back adds nothing to it.
        Try(device.close())
        _state = State.Connected
        openRefCount = 0

        logger.error(s"Failed to open $direction device $id.", exception)
        Seq(MidiDeviceFailedToOpenEvent(id, direction, exception))
    }
  }

  /** Closes the open `device` as the handle leaves [[State.Open]], leaving the state to the caller. */
  private def closeOpenDevice(device: MidiDevice): Seq[MidiEvent] = {
    deviceReceiver = None
    try {
      device.close()
      logger.info(s"Successfully closed $direction device $id.")
      Seq(MidiDeviceClosedEvent(id, direction))
    } catch {
      case exception: Exception =>
        logger.error(s"Failed to close $direction device $id!", exception)
        Seq(MidiDeviceFailedToCloseEvent(id, direction, exception))
    }
  }

  private def logConnected(info: MidiDeviceInfo): Unit = {
    logger.whenDebugEnabled {
      val (handlerType, connectionLimit) = if (direction == MidiEndpointType.Input) {
        ("transmitters", info.transmittersLimit)
      } else {
        ("receivers", info.receiversLimit)
      }

      logger.debug(s"${direction.toString.capitalize} device $id with $connectionLimit $handlerType was connected.")
    }
  }
}
```

- [ ] **Step 5: Keep the manager working over the new commands**

The manager keeps its own connection bookkeeping until Task 5. In `JavaMidiManager.MidiEndpoint`:

`openDevice` becomes:

```scala
    def openDevice(deviceId: MidiDeviceId): JavaMidiDeviceHandle = {
      val deviceHandle = openedDevicesMap.computeIfAbsent(deviceId, _ => JavaMidiDeviceHandle(deviceId, endpointType))

      Option(connectedDevices.get(deviceId)) match {
        case Some(connectedDevice) =>
          // TODO #288 The connection events are dropped, because updateDevices already reported the connection; the
          //   endpoint is going to hand every connected device to its handle from refresh instead.
          deviceHandle.connect(connectedDevice.info, connectedDevice.device)
          deviceHandle.open().foreach(businessync.publish)
        case None =>
          // TODO #288 The handle is not opened, so it does not wait to open and would not open once the device gets
          //   connected, contrary to MidiManager.openInput / openOutput.
          logger.warn(s"${endpointType.toString.capitalize} device $deviceId is not connected.")
      }

      deviceHandle
    }
```

`closeDevice` becomes:

```scala
    def closeDevice(deviceId: MidiDeviceId): Unit = {
      // TODO #288 This releases a single open of the handle and then drops it whatever its reference count, so a
      //   device another track still holds stays open but unmanaged, and close() never closes it; a handle that is not
      //   open, such as one requested while its device was disconnected, is neither released nor removed.
      deviceHandleOf(deviceId) match {
        case Some(openedDevice) if openedDevice.isOpen =>
          openedDevice.close().foreach(businessync.publish)
          openedDevicesMap.remove(deviceId)
        case _ => // Do nothing
      }
    }
```

Delete the old `TODO #288` above `deviceHandle.onConnect`. `connect` with the same instance no longer closes the
device, so that bug is gone.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS. The `JavaMidiManagerTest` tests under `TODO #288` stay ignored until Task 5.

- [ ] **Step 7: Refactor, then commit**

Check that no `TODO #131` remains in `JavaMidiDeviceHandleTest` and no `TODO #288` remains in `JavaMidiDeviceHandle`:
`git grep -n "TODO #131\|TODO #288" sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandleTest.scala`
(expect no output).

```bash
git add sc-midi/src
git commit -m "$(cat <<'EOF'
[#131][#288] Make the JavaMidiDeviceHandle transitions transactional and return their events

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: A send that hits a receiver Java Sound already closed is dropped

Design 4.6. When a device vanishes, Java Sound can close its receiver before CoreMIDI4J reports the change. A send to
that receiver throws `IllegalStateException`, and today that exception reaches the input device's thread as a
`TunerException`.

**Files:**
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandle.scala`
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiDeviceHandleTest.scala`

**Interfaces:**
- Consumes: Task 3's `JavaMidiDeviceHandle` (`doOpen`, `HandleReceiver`, `direction`).
- Produces: no API change. The drop is logged at warn level for the first dropped message after each open, and at
  debug level for the following ones.

- [ ] **Step 1: Write the failing tests**

In `JavaMidiDeviceHandleTest`, add to the `receiver` section:

```scala
    "drop a message that reaches a receiver Java Sound already closed, without throwing" in new Fixture {
      // Given
      connect()
      handle.open()
      // Java Sound closes the device, and its receivers, before the manager learns that the device is gone
      device.close()

      // When / Then
      noException should be thrownBy handle.receiver.send(noteOn, 42L)
      device.receivedMessages shouldBe empty
    }
```

and to the `Logging` section:

```scala
    "warn of the first message dropped after each open and report the following ones at debug level" in new Fixture {
      // Given
      connect()
      handle.open()
      device.close()

      // When
      val (_, events) = LogCapture.capturing(loggerName) {
        handle.receiver.send(noteOn, 42L)
        handle.receiver.send(secondNoteOn, 43L)
        handle.close()
        handle.open()
        device.close()
        handle.receiver.send(noteOn, 44L)
      }

      // Then
      val warning = """Dropping the messages sent to output device "CoreMIDI4J - FP-90" (Roland), which Java Sound """ +
        "already closed, until it opens again."
      events.messagesAt(Level.WARN) shouldEqual Seq(warning, warning)
      events.messagesAt(Level.DEBUG) shouldEqual
        Seq(s"""Dropping $secondNoteOn sent to output device "CoreMIDI4J - FP-90" (Roland), which Java Sound already """ +
          "closed.")
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Compile `sc-midi-test`, then run:
`sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiDeviceHandleTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The send throws `IllegalStateException: The receiver is closed`.

- [ ] **Step 3: Implement**

In `JavaMidiDeviceHandle.scala`:
- Add the import `java.util.concurrent.atomic.AtomicBoolean`.
- Add the field below `deviceReceiver`:

```scala
  /** Whether a message dropped since the device last opened was already reported at warn level. */
  private val hasWarnedOfDroppedMessage: AtomicBoolean = AtomicBoolean(false)
```

Change the `Midi1Msg` case of `HandleReceiver.send` to:

```scala
      case midi1Message: Midi1Msg =>
        for (javaReceiver <- deviceReceiver) {
          try {
            javaReceiver.send(midi1Message.asJava, timeStamp)
          } catch {
            // TODO #302 Inform the manager that the device is gone, instead of only dropping the message until
            //  CoreMIDI4J reports the change.
            case _: IllegalStateException => logDroppedMessage(midi1Message)
          }
        }
```

Add, next to `logConnected`:

```scala
  private def logDroppedMessage(message: Midi1Msg): Unit = {
    if (hasWarnedOfDroppedMessage.compareAndSet(false, true)) {
      logger.warn(s"Dropping the messages sent to $direction device $id, which Java Sound already closed, until it " +
        "opens again.")
    } else {
      logger.debug(s"Dropping $message sent to $direction device $id, which Java Sound already closed.")
    }
  }
```

In `doOpen`, add `hasWarnedOfDroppedMessage.set(false)` right before `_state = State.Open`.

In the class ScalaDoc, after "Sending never takes the lock of the handle.", add: "A message that reaches a receiver
Java Sound already closed, because the device vanished before the manager learned of it, is dropped: the first one
after each open is logged at warn level, and the following ones at debug level."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add sc-midi/src
git commit -m "$(cat <<'EOF'
[#131][#288] Drop the messages sent to a Java receiver that is already closed

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `JavaMidiManager` keeps a registry of live handles, reconciled under one lock

Design 3.2, 4.1, 4.2, 4.3 (the manager half) and 4.5. This fixes #288 and turns the ignored `TODO #288` tests of
`JavaMidiManagerTest` into running tests.

**Files:**
- Modify (rewrite): `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManager.scala`
- Modify: `sc-midi/src/main/scala/org/calinburloiu/music/scmidi/MidiManager.scala` (ScalaDoc only)
- Test: `sc-midi/src/test/scala/org/calinburloiu/music/scmidi/javamidi/JavaMidiManagerTest.scala`

**Interfaces:**
- Consumes: Task 3's commands `connect` / `disconnect` / `open` / `close` / `closeAll`, each returning
  `Seq[MidiEvent]`, and `JavaMidiDeviceHandle(id, direction)`.
- Produces: no public API change. The `MidiManager` behaviour of design 3.2:
  - `openInput` / `openOutput` return the live handle.
  - `closeInput` / `closeOutput` release one reference.
  - `…DeviceHandleOf` returns any live handle.
  - `…OpenedDevices` returns the handles with `isOpenRequested`.
  - `close()` releases every reference.
  - Events are published in order after the lock is released.

- [ ] **Step 1: Write the failing tests**

In `JavaMidiManagerTest.scala`:

1. Delete the class-level `// TODO #288 …` comment (three lines).
2. Add the imports `java.util.concurrent.{CompletableFuture, TimeUnit}`, `scala.collection.mutable` and
   `org.calinburloiu.businessync.BusinessyncEvent`.
3. Replace `Fixture` with a version whose subscriber can be set by a test:

```scala
  private trait Fixture {
    /** What the bus does with each published event, besides recording it; a test may replace it. */
    var onPublish: BusinessyncEvent => Unit = _ => ()

    val businessync: Stub[Businessync] = stub[Businessync]
    businessync.publish.returns(event => onPublish(event))

    val environment: FakeJavaMidiEnvironment = FakeJavaMidiEnvironment()

    /** Creates the manager, which scans the devices plugged into [[environment]] so far. */
    def newManager(): JavaMidiManager = JavaMidiManager(businessync, environment)
  }
```

4. Replace the body of `deviceEndpoint` (keep `EndpointFixture` as it is) with the following behaviours.
   `et` is shorthand for `direction.endpointType`: declare `val et: MidiEndpointType = direction.endpointType` at the
   top of `deviceEndpoint`.

```scala
    "list a device plugged in at start-up, with its info and a live handle in Connected, and report it as connected" in
      new EndpointFixture {
        // Then
        direction.isAvailable(manager, id) shouldBe true
        direction.deviceIds(manager) shouldEqual Seq(id)
        direction.devicesInfo(manager) shouldEqual Seq(device.asMidiDeviceInfo)
        direction.deviceInfoOf(manager, id) shouldEqual Some(device.asMidiDeviceInfo)
        direction.deviceHandleOf(manager, id).map(_.state) shouldEqual Some(State.Connected)
        direction.openedDevices(manager) shouldBe empty
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et))
      }

    "know nothing of a device that is not plugged in" in new EndpointFixture(isPluggedAtStart = false) {
      // (body unchanged)
    }

    "report a device plugged in after start-up as connected on refresh" in
      new EndpointFixture(isPluggedAtStart = false) {
        // (body unchanged, with MidiDeviceConnectedEvent(id, et))
      }

    "report an unplugged device as disconnected on refresh, and forget its handle" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.deviceHandleOf(manager, id).get
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      direction.isAvailable(manager, id) shouldBe false
      direction.deviceIds(manager) shouldBe empty
      direction.deviceHandleOf(manager, id) shouldBe empty
      handle.state shouldEqual State.Closed
      businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceDisconnectedEvent(id, et))
    }

    "open a connected device, handing it to an open handle, and report it as opened" in new EndpointFixture {
      // (body unchanged, with MidiDeviceConnectedEvent(id, et) and MidiDeviceOpenedEvent(id, et))
    }

    "return the same handle when a device is opened again" in new EndpointFixture {
      // (body unchanged)
    }

    "keep the device open when it is opened again" in new EndpointFixture {
      // (body of the ignored test unchanged; `ignore` becomes `in`)
    }

    "make the handle of a device that is not connected wait to open, without reporting anything" in
      new EndpointFixture(isPluggedAtStart = false) {
        // When
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // Then
        handle.id shouldEqual id
        handle.state shouldEqual State.WaitingToOpen
        handle.isConnected shouldBe false
        handle.isOpen shouldBe false
        direction.deviceHandleOf(manager, id) shouldEqual Some(handle)
        direction.openedDevices(manager) shouldEqual Seq(handle)
        direction.isAvailable(manager, id) shouldBe false
        businessync.publish.calls shouldBe empty
      }

    "open the device of a handle requested before the device got connected, once it gets connected" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val handle: MidiDeviceHandle = direction.open(manager, id)
        environment.plug(device)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe true
        businessync.publish.calls shouldEqual Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
      }

    "close an open device, keeping its handle live in Connected but no longer listed as opened" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // When
        direction.close(manager, id)

        // Then
        device.isOpen shouldBe false
        handle.state shouldEqual State.Connected
        direction.deviceHandleOf(manager, id) shouldEqual Some(handle)
        direction.openedDevices(manager) shouldBe empty
        businessync.publish.calls shouldEqual
          Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et), MidiDeviceClosedEvent(id, et))
      }

    "keep a device opened twice open until it is closed twice" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      direction.open(manager, id)

      // When
      direction.close(manager, id)

      // Then
      device.isOpen shouldBe true
      direction.openedDevices(manager) shouldEqual Seq(handle)

      // When
      direction.close(manager, id)

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.Connected
      direction.openedDevices(manager) shouldBe empty
    }

    "ignore closing a device that is not open" in new EndpointFixture {
      // (body unchanged, with MidiDeviceConnectedEvent(id, et))
    }

    "release and forget the handle of a device that is not connected when it is closed" in
      new EndpointFixture(isPluggedAtStart = false) {
        // (body of the ignored test unchanged; `ignore` becomes `in`)
      }

    "return a new handle for a device requested again after its handle was forgotten" in
      new EndpointFixture(isPluggedAtStart = false) {
        // Given
        val forgottenHandle: MidiDeviceHandle = direction.open(manager, id)
        direction.close(manager, id)

        // When
        val handle: MidiDeviceHandle = direction.open(manager, id)

        // Then
        handle should not be theSameInstanceAs(forgottenHandle)
        forgottenHandle.state shouldEqual State.Closed
        handle.state shouldEqual State.WaitingToOpen
      }

    "close an open device that got unplugged, keeping its handle waiting to open it again" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      environment.unplug(device.getDeviceInfo)

      // When
      manager.refresh()

      // Then
      device.isOpen shouldBe false
      handle.state shouldEqual State.WaitingToOpen
      direction.deviceHandleOf(manager, id) shouldEqual Some(handle)
      direction.openedDevices(manager) shouldEqual Seq(handle)
      direction.isAvailable(manager, id) shouldBe false
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(id, et),
        MidiDeviceOpenedEvent(id, et),
        MidiDeviceClosedEvent(id, et),
        MidiDeviceDisconnectedEvent(id, et)
      )
    }

    "open the handle of an unplugged device with the device it gets when replugged" in new EndpointFixture {
      // Given
      val handle: MidiDeviceHandle = direction.open(manager, id)
      val repluggedDevice: FakeMidiDevice = direction.newDevice(deviceName)
      environment.unplug(device.getDeviceInfo)
      manager.refresh()
      environment.plug(repluggedDevice)

      // When
      manager.refresh()

      // Then
      handle.state shouldEqual State.Open
      repluggedDevice.isOpen shouldBe true
      direction.openedDevices(manager) shouldEqual Seq(handle)
      businessync.publish.calls.drop(4) shouldEqual
        Seq(MidiDeviceConnectedEvent(id, et), MidiDeviceOpenedEvent(id, et))
    }

    "close the device of an open handle and open the one it got swapped for between two refreshes" in
      new EndpointFixture {
        // Given
        val handle: MidiDeviceHandle = direction.open(manager, id)
        val swappedDevice: FakeMidiDevice = direction.newDevice(deviceName)
        environment.unplug(device.getDeviceInfo)
        environment.plug(swappedDevice)

        // When
        manager.refresh()

        // Then
        handle.state shouldEqual State.Open
        device.isOpen shouldBe false
        swappedDevice.isOpen shouldBe true
        businessync.publish.calls shouldEqual Seq(
          MidiDeviceConnectedEvent(id, et),
          MidiDeviceOpenedEvent(id, et),
          MidiDeviceClosedEvent(id, et),
          MidiDeviceOpenedEvent(id, et)
        )
      }
```

5. In the `refresh` section, add after "resolve the device afresh for an id that left and came back":

```scala
    "keep the device resolved last when the environment lists an id twice" in new Fixture {
      // Given
      val firstDevice: FakeMidiDevice = Output.newDevice(deviceName)
      val lastDevice: FakeMidiDevice = Output.newDevice(deviceName)
      environment.plug(firstDevice)
      environment.plug(lastDevice)
      val manager: JavaMidiManager = newManager()

      // When
      manager.openOutput(firstDevice.id)

      // Then
      lastDevice.isOpen shouldBe true
      firstDevice.isOpen shouldBe false
      manager.outputDeviceIds shouldEqual Seq(firstDevice.id)
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(firstDevice.id, MidiEndpointType.Output),
        MidiDeviceOpenedEvent(firstDevice.id, MidiEndpointType.Output)
      )
    }
```

6. In the `close` section, turn "close a device opened more than once" from `ignore` into `in` (body unchanged, delete
   its `TODO #288`), and add:

```scala
    "forget the handle of a device waiting to open" in new Fixture {
      // Given
      val manager: JavaMidiManager = newManager()
      val handle: MidiDeviceHandle = manager.openOutput(MidiDeviceId(deviceName, "Roland"))

      // When
      manager.close()

      // Then
      handle.state shouldEqual State.Closed
      manager.outputDeviceHandleOf(handle.id) shouldBe empty
    }
```

7. Add a section after `close`:

```scala
  "Publishing" should {
    "publish the events of an operation in order once it completes, outside the lock of the manager" in new Fixture {
      // Given
      val device: FakeMidiDevice = Output.newDevice(deviceName)
      val manager: JavaMidiManager = newManager()
      manager.openOutput(device.id)
      environment.plug(device)
      // What a subscriber on another thread sees of the handle when each event reaches it; with the lock still held,
      // the lookup would time out.
      val observedStates: mutable.Buffer[Option[State]] = mutable.ArrayBuffer()
      onPublish = _ => observedStates += CompletableFuture
        .supplyAsync(() => manager.outputDeviceHandleOf(device.id).map(_.state))
        .get(5, TimeUnit.SECONDS)

      // When
      manager.refresh()

      // Then
      businessync.publish.calls shouldEqual Seq(
        MidiDeviceConnectedEvent(device.id, MidiEndpointType.Output),
        MidiDeviceOpenedEvent(device.id, MidiEndpointType.Output)
      )
      observedStates shouldEqual Seq(Some(State.Open), Some(State.Open))
    }
  }
```

8. In the `Logging` section:
   - Delete "report each connected device at debug level, with the connection limit of its direction". The handle
     test covers it since Task 3.
   - Rename "warn that a device to open is not connected" to "warn that a device to open is not connected and will be
     opened once it gets connected", expecting:

```scala
      events.messagesAt(Level.WARN) shouldEqual Seq("""Output device "CoreMIDI4J - FP-90" (Roland) is not connected; """ +
        "it will be opened once it gets connected.")
```

   - Rename "report an environment change at info level and the disconnection of a device at warn level" to "report an
     environment change at info level". Delete its `WARN` assertion; the handle now logs the disconnection.

- [ ] **Step 2: Run the tests to verify they fail**

Compile `sc-midi-test`, then run:
`sbtn "sc-midi/testOnly org.calinburloiu.music.scmidi.javamidi.JavaMidiManagerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL on assertions, for example:
- no live handle for a connected device nobody opened;
- `Closed` instead of `WaitingToOpen`;
- the handle forgotten after one close;
- the publishing test observing `Some(State.Connected)` for the connected event.

No compile change is needed for this red step.

- [ ] **Step 3: Implement the manager**

Replace the body of `JavaMidiManager.scala` (keep the license header) with:

```scala
package org.calinburloiu.music.scmidi.javamidi

import com.typesafe.scalalogging.StrictLogging
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.microtonalist.common.concurrency.Locking
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.MidiDeviceHandle.State
import org.calinburloiu.music.scmidi.javamidi.JavaMidiConverters.*

import java.util.concurrent.locks.{Lock, ReentrantLock}
import javax.annotation.concurrent.{NotThreadSafe, ThreadSafe}
import javax.sound.midi.{MidiDevice, MidiUnavailableException}
import scala.collection.immutable.VectorMap
import scala.collection.mutable

/**
 * [[MidiManager]] over Java Sound and CoreMIDI4J.
 *
 * The class has different sets of methods for inputs and outputs, because the Java MIDI API and CoreMIDI4J may
 * expose two [[MidiDevice]] ([[JavaMidiDeviceHandle]]) instances for the same physical device, one for input and the
 * other for output. Note that in this case, there is a single [[MidiDeviceId]].
 *
 * Each of the two endpoints, one for inputs and one for outputs, keeps a registry of its live
 * [[JavaMidiDeviceHandle]]s: one for every device that is connected, requested to open, or both.
 *
 * Each [[refresh]] resolves every device once through the [[JavaMidiEnvironment]] and reconciles each registry with
 * what it found (the device resolved last for an id wins). It hands each connected device to its handle and tells the
 * handles whose device is gone. A handle that ends up [[MidiDeviceHandle.State.Closed]] is forgotten. The manager also
 * refreshes whenever the environment reports a change, until it is closed.
 *
 * A manager-wide lock serialises the operations and the registry reads. The lock of a handle is only ever taken
 * inside it, never the other way around.
 *
 * Resolving the devices happens before taking the lock. The [[MidiEvent]]s of an operation are published in order
 * once the lock is released, because the bus delivers them synchronously to subscribers that may use the manager or
 * send MIDI. Sending MIDI takes neither lock.
 *
 * @param businessync Used for publishing [[MidiEvent]]s.
 * @param environment The Java Sound environment to scan and subscribe to; the production default is
 *                    [[CoreMidi4JEnvironment]], a fake is what a test passes.
 */
@ThreadSafe
class JavaMidiManager(businessync: Businessync,
                      environment: JavaMidiEnvironment = CoreMidi4JEnvironment)
  extends MidiManager, Locking, StrictLogging {

  import JavaMidiManager.*

  private implicit val lock: Lock = ReentrantLock()

  private val inputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Input)
  private val outputEndpoint: MidiEndpoint = MidiEndpoint(MidiEndpointType.Output)

  private val environmentSubscription: AutoCloseable = init()

  private def init(): AutoCloseable = {
    refresh()

    // Automatically refresh when the MIDI environment has changed
    environment.subscribeToEnvironmentChanged(() => onEnvironmentChanged())
  }

  private def onEnvironmentChanged(): Unit = {
    logger.info("The MIDI environment has changed.")
    refreshAfter(Seq(MidiEnvironmentChangedEvent))
  }

  override def refresh(): Unit = refreshAfter(Seq.empty)

  /** Refreshes, publishing `leadingEvents` before the events of the refresh. */
  private def refreshAfter(leadingEvents: Seq[MidiEvent]): Unit = {
    val resolutionEvents = mutable.Buffer.from(leadingEvents)
    val devices = environment.deviceInfos.flatMap { javaInfo =>
      resolveDevice(javaInfo, resolutionEvents).map(device => ConnectedDevice(device.asMidiDeviceInfo, device))
    }

    withLockThenPublish {
      val reconciliationEvents = inputEndpoint.reconcile(devices.filter(_.info.isInputDevice)) ++
        outputEndpoint.reconcile(devices.filter(_.info.isOutputDevice))
      ((), resolutionEvents.toSeq ++ reconciliationEvents)
    }
  }

  /**
   * Resolves the Java Sound device described by `javaInfo`, or `None` if it cannot be. A `MidiUnavailableException`
   * or an `IllegalArgumentException` drops the device silently. Any other exception is logged and collected into
   * `events` as a [[MidiDeviceFailedToConnectEvent]], to be published with the events of the refresh, before the
   * device is dropped.
   *
   * Both exceptions dropped silently mean that the device listed a moment earlier is not usable right now, which is
   * routine while devices are plugged in and unplugged, and which every [[refresh]] would report again:
   *
   *   - `MidiUnavailableException` means the device is there but its resources are not, typically because another
   *     application holds it exclusively.
   *   - `IllegalArgumentException` is what `MidiSystem.getMidiDevice` (and CoreMIDI4J's provider) throws for an info
   *     that no longer describes an installed device, so it is the outcome of the race between listing the devices
   *     and resolving them: the device was unplugged in between, and the next refresh will not list it at all.
   */
  private def resolveDevice(javaInfo: MidiDevice.Info, events: mutable.Buffer[MidiEvent]): Option[MidiDevice] = {
    try {
      Some(environment.deviceOf(javaInfo))
    } catch {
      case _: MidiUnavailableException => None
      case _: IllegalArgumentException => None
      case exception: Exception =>
        val id = javaInfo.asMidiDeviceId
        logger.error(s"Failed to connect to device $id!", exception)
        events += MidiDeviceFailedToConnectEvent(id, exception)
        None
    }
  }

  /**
   * Runs `operation` under the lock of the manager and then, once the lock is released, publishes in order the events
   * it returned, so that a subscriber, which the bus calls on this thread, never runs inside the lock.
   *
   * @return the result of the operation.
   */
  private def withLockThenPublish[R](operation: => (R, Seq[MidiEvent])): R = {
    val (result, events) = withLock(operation)
    events.foreach(businessync.publish)
    result
  }

  override def close(): Unit = {
    logger.info(s"Closing MIDI connections...")
    withLockThenPublish {
      ((), inputEndpoint.closeAll() ++ outputEndpoint.closeAll())
    }
    logger.info(s"Finished closing MIDI connections.")

    environmentSubscription.close()
  }

  override def isInputAvailable(deviceId: MidiDeviceId): Boolean = withLock {
    inputEndpoint.isDeviceAvailable(deviceId)
  }

  override def inputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = withLock {
    inputEndpoint.deviceInfoOf(deviceId)
  }

  override def inputDeviceIds: Seq[MidiDeviceId] = withLock {
    inputEndpoint.deviceIds
  }

  override def inputDevicesInfo: Seq[MidiDeviceInfo] = withLock {
    inputEndpoint.devicesInfo
  }

  override def openInput(deviceId: MidiDeviceId): MidiDeviceHandle = withLockThenPublish {
    inputEndpoint.openDevice(deviceId)
  }

  override def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = withLock {
    inputEndpoint.deviceHandleOf(deviceId)
  }

  override def inputOpenedDevices: Seq[MidiDeviceHandle] = withLock {
    inputEndpoint.openedDevices
  }

  override def closeInput(deviceId: MidiDeviceId): Unit = withLockThenPublish {
    ((), inputEndpoint.closeDevice(deviceId))
  }

  override def isOutputAvailable(deviceId: MidiDeviceId): Boolean = withLock {
    outputEndpoint.isDeviceAvailable(deviceId)
  }

  override def outputDeviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = withLock {
    outputEndpoint.deviceInfoOf(deviceId)
  }

  override def outputDeviceIds: Seq[MidiDeviceId] = withLock {
    outputEndpoint.deviceIds
  }

  override def outputDevicesInfo: Seq[MidiDeviceInfo] = withLock {
    outputEndpoint.devicesInfo
  }

  override def openOutput(deviceId: MidiDeviceId): MidiDeviceHandle = withLockThenPublish {
    outputEndpoint.openDevice(deviceId)
  }

  override def outputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle] = withLock {
    outputEndpoint.deviceHandleOf(deviceId)
  }

  override def outputOpenedDevices: Seq[MidiDeviceHandle] = withLock {
    outputEndpoint.openedDevices
  }

  override def closeOutput(deviceId: MidiDeviceId): Unit = withLockThenPublish {
    ((), outputEndpoint.closeDevice(deviceId))
  }
}

object JavaMidiManager {

  /** A device present in the environment: its API-level information and the resolved Java Sound device. */
  private case class ConnectedDevice(info: MidiDeviceInfo, device: MidiDevice) {
    def id: MidiDeviceId = info.id
  }

  /**
   * The registry of the live handles of one direction. The Java MIDI API lists input and output devices separately,
   * so the same physical device may appear twice, under the same [[MidiDeviceId]].
   *
   * It is not thread-safe: [[JavaMidiManager]] uses it only under its lock. Each operation returns the events of the
   * transitions it made, for the manager to publish.
   *
   * @param direction whether the devices managed are input or output devices.
   */
  @NotThreadSafe
  private class MidiEndpoint(val direction: MidiEndpointType) extends StrictLogging {

    /** The live handles, which are connected, requested to open, or both, in the order they were created. */
    private val handles: mutable.LinkedHashMap[MidiDeviceId, JavaMidiDeviceHandle] = mutable.LinkedHashMap()

    /**
     * Reconciles the live handles with the devices of this direction that a refresh found: each device is handed to
     * the handle of its id, created if there is none, and each connected handle whose device was not found is
     * disconnected, and forgotten if that leaves it closed.
     */
    def reconcile(devices: Seq[ConnectedDevice]): Seq[MidiEvent] = {
      // The device resolved last for an id wins
      val devicesById = devices.foldLeft(VectorMap.empty[MidiDeviceId, ConnectedDevice]) { (devicesById, device) =>
        devicesById.updated(device.id, device)
      }

      val connectionEvents = devicesById.values.toSeq.flatMap { connectedDevice =>
        handleOf(connectedDevice.id).connect(connectedDevice.info, connectedDevice.device)
      }
      val disconnectionEvents = handles.values.toSeq
        .filter(handle => handle.isConnected && !devicesById.contains(handle.id))
        .flatMap(handle => forgettingIfClosed(handle)(handle.disconnect()))

      connectionEvents ++ disconnectionEvents
    }

    def isDeviceAvailable(deviceId: MidiDeviceId): Boolean = handles.get(deviceId).exists(_.isConnected)

    def deviceInfoOf(deviceId: MidiDeviceId): Option[MidiDeviceInfo] = handles.get(deviceId).flatMap(_.info)

    def deviceIds: Seq[MidiDeviceId] = connectedHandles.map(_.id)

    def devicesInfo: Seq[MidiDeviceInfo] = connectedHandles.flatMap(_.info)

    /** Takes one reference to the device, through its live handle, created if there is none. */
    def openDevice(deviceId: MidiDeviceId): (JavaMidiDeviceHandle, Seq[MidiEvent]) = {
      val handle = handleOf(deviceId)
      val events = handle.open()
      if (handle.state == State.WaitingToOpen) {
        logger.warn(s"${direction.toString.capitalize} device $deviceId is not connected; it will be opened once it " +
          "gets connected.")
      }

      (handle, events)
    }

    def deviceHandleOf(deviceId: MidiDeviceId): Option[JavaMidiDeviceHandle] = handles.get(deviceId)

    def openedDevices: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isOpenRequested).toSeq

    /** Releases one reference to the device, if its live handle is requested to open. */
    def closeDevice(deviceId: MidiDeviceId): Seq[MidiEvent] = handles.get(deviceId) match {
      case Some(handle) if handle.isOpenRequested => forgettingIfClosed(handle)(handle.close())
      case _ => Seq.empty
    }

    /** Releases every reference to every device. */
    def closeAll(): Seq[MidiEvent] = handles.values.toSeq.flatMap { handle =>
      forgettingIfClosed(handle)(handle.closeAll())
    }

    private def connectedHandles: Seq[JavaMidiDeviceHandle] = handles.values.filter(_.isConnected).toSeq

    private def handleOf(deviceId: MidiDeviceId): JavaMidiDeviceHandle =
      handles.getOrElseUpdate(deviceId, JavaMidiDeviceHandle(deviceId, direction))

    /** Runs `command`, a command of `handle`, then forgets the handle if the command left it closed. */
    private def forgettingIfClosed(handle: JavaMidiDeviceHandle)(command: => Seq[MidiEvent]): Seq[MidiEvent] = {
      val events = command
      if (handle.state == State.Closed) {
        handles.remove(handle.id)
      }

      events
    }
  }
}
```

If a class-level `@ThreadSafe` or `@NotThreadSafe` annotation makes the build complain, check the imports: both
annotations come from `javax.annotation.concurrent`, already used by `JavaMidiDeviceHandle`.

- [ ] **Step 4: Update the `MidiManager` ScalaDoc**

In `MidiManager.scala`, append this paragraph to the trait ScalaDoc:

```scala
 * A handle is live while the manager holds it, which is exactly while its state is not
 * [[MidiDeviceHandle.State.Closed]] (see [[MidiDeviceHandle]]). The manager holds a handle for every device that is
 * connected, requested to open, or both, and it forgets a handle once it reaches `Closed`.
```

and replace the ScalaDoc of these members (shown for inputs; mirror each for outputs, changing "input" to "output"):

```scala
  /**
   * Takes one reference to the input device with the given identifier and returns its live handle, creating one if
   * there is none.
   *
   * The device is not required to be connected. The handle is [[MidiDeviceHandle.State.Open]] if the device is
   * connected and opens, [[MidiDeviceHandle.State.WaitingToOpen]] if it is not connected, in which case it opens once
   * the device gets connected, and [[MidiDeviceHandle.State.Connected]] if the device fails to open.
   *
   * @param deviceId Unique identifier of the device.
   * @return the live handle of the device.
   */
  def openInput(deviceId: MidiDeviceId): MidiDeviceHandle

  /**
   * @return the live handle of the input device with the given identifier: requested to open, connected, or both. A
   *         connected device nobody opened has one, in [[MidiDeviceHandle.State.Connected]].
   */
  def inputDeviceHandleOf(deviceId: MidiDeviceId): Option[MidiDeviceHandle]

  /** @return the live handles of the input devices requested to open. */
  def inputOpenedDevices: Seq[MidiDeviceHandle]

  /**
   * Releases one reference to the input device with the given identifier. It does nothing when the device has no live
   * handle requested to open. When the last reference is released, the handle moves to
   * [[MidiDeviceHandle.State.Connected]], or to [[MidiDeviceHandle.State.Closed]] if its device is not connected,
   * and is then forgotten.
   */
  def closeInput(deviceId: MidiDeviceId): Unit
```

and for `close()`:

```scala
  /**
   * Releases every reference held through this manager, so that every device it opened ends up closed, and stops
   * watching the environment.
   */
  override def close(): Unit
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`, then `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"` and
`sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"`.
Expected: PASS, with no ignored test left in `JavaMidiManagerTest`.
Then run `git grep -n "TODO #288\|TODO #131" -- '*.scala'` and expect no output.

- [ ] **Step 6: Commit**

```bash
git add sc-midi/src
git commit -m "$(cat <<'EOF'
[#131][#288] Keep live MIDI device handles up to date across device connections

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `TunerProcessor.reset()` and `TuningChangeProcessor.reset()`

Design 5.1.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessor.scala`
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessor.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TunerProcessorTest.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TuningChangeProcessorTest.scala`

**Interfaces:**
- Produces: `TunerProcessor.reset(): Unit` and `TuningChangeProcessor.reset(): Unit`.

- [ ] **Step 1: Write the failing tests**

Append to `TunerProcessorTest`:

```scala
  "reset" should "reset the tuner and send its reset messages to every receiver" in new Fixture {
    // Given
    val anotherReceiver: MidiReceiver = stub[MidiReceiver]
    processor.transmitter.addReceiver(anotherReceiver)

    // When
    processor.reset()

    // Then
    // Once when each receiver got connected, and once more on reset
    receiver.send.verify(initMessage, -1L).repeated(2)
    anotherReceiver.send.verify(initMessage, -1L).repeated(2)
  }
```

Append to `TuningChangeProcessorTest`:

```scala
  behavior of "reset"

  it should "reset every tuning changer" in new Fixture {
    // When
    processor.reset()

    // Then
    (() => noteTuningChangerStub.reset()).verify().once()
    (() => ccTuningChangerStub.reset()).verify().once()
  }
```

Stub both methods so that the tests compile: `def reset(): Unit = ???` in `TunerProcessor` and in
`TuningChangeProcessor`. Compile `tuner-test`. If ScalaMock rejects the `(() => stub.reset()).verify()` syntax, use
the syntax the compiler suggests for verifying a parameterless call (`mcp__metals__get-docs` on
`org.scalamock.scalatest.MockFactory` helps).

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TunerProcessorTest org.calinburloiu.music.microtonalist.tuner.TuningChangeProcessorTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL with `NotImplementedError` in both new tests.

- [ ] **Step 3: Implement**

In `TunerProcessor`, after `tune`:

```scala
  /**
   * Resets the tuner and sends the messages that initialize the output to every receiver of the transmitter, as a
   * newly connected receiver gets them, e.g. when the output device (re)opens after the processor was connected to it.
   *
   * It does not apply any tuning: the output plays in the tuning the tuner is left in by its reset.
   */
  def reset(): Unit = {
    val initMessages = tuner.reset()
    sendToReceivers(initMessages, -1)
  }
```

In `TuningChangeProcessor`, after the auxiliary constructor:

```scala
  /**
   * Resets every [[TuningChanger]], e.g. so that a trigger held when the input device disappeared does not swallow the
   * first trigger after the device comes back.
   */
  def reset(): Unit = {
    tuningChangers.foreach(_.reset())
  }
```

In the class ScalaDoc of `TunerProcessor`, add to the list of responsibilities: "- Resetting the tuner on request and
sending the initialization messages to every receiver."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tuner/src
git commit -m "$(cat <<'EOF'
[#131][#288] Add reset to TunerProcessor and TuningChangeProcessor

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `Track.resetTuner()` and `Track.releaseInput()`

Design 5.2 (second and third bullets).

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/Track.scala`
- Test: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackTest.scala`

**Interfaces:**
- Consumes: Task 6's `TunerProcessor.reset()` and `TuningChangeProcessor.reset()`, and Task 1's `DeviceFixture`,
  `RecordingMidiReceiver` and `FakeMidiDeviceHandle`.
- Produces: `Track.resetTuner(): Unit` and `Track.releaseInput(): Unit`.

- [ ] **Step 1: Write the failing tests**

In `TrackTest.scala`:
- Add the import `org.calinburloiu.music.scmidi.message.AllNotesOffMidiMsg`.
- In `DeviceFixture`, add a stubbed tuning changer before `spec`, and pass it to the spec:

```scala
    val tuningChanger: TuningChanger = stub[TuningChanger]
    tuningChanger.decide.when(*).returns(NoTuningChange)
```

```scala
    val spec: TrackSpec = TrackSpec("track", "Track", input = Some(DeviceTrackInputSpec(inputDeviceId, None)),
      tuningChangers = Seq(tuningChanger), tuner = Some(tuner),
      output = Some(DeviceTrackOutputSpec(outputDeviceId, None)))
```

Append:

```scala
  behavior of "resetTuner"

  it should "send the tuner's reset messages to the output" in new DeviceFixture {
    // Given
    outputReceiver.clear()

    // When
    track.resetTuner()

    // Then
    outputReceiver.messages shouldEqual Seq(initMessage)
  }

  behavior of "releaseInput"

  it should "send All Notes Off on every channel straight to the output, then reset the tuning changers and the tuner" in
    new DeviceFixture {
      // Given
      outputReceiver.clear()

      // When
      track.releaseInput()

      // Then
      outputReceiver.messages shouldEqual (0 until 16).map(AllNotesOffMidiMsg(_)) :+ initMessage
      tuner.process.verify(*).never()
      (() => tuningChanger.reset()).verify().once()
    }
```

Stub: `def resetTuner(): Unit = ???` and `def releaseInput(): Unit = ???` in `Track`. Compile `tuner-test`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL with `NotImplementedError` in the two new tests.

- [ ] **Step 3: Implement**

In `Track.scala`:
- Add the import `org.calinburloiu.music.scmidi.message.AllNotesOffMidiMsg` (next to `MidiMsg`).
- Add after `tune`:

```scala
  /**
   * Resets the tuner of this track, if any, sending the messages that initialize the output instrument to the output
   * of the track, e.g. after the output device (re)opened. The current tuning is not restored: the output plays in
   * 12-EDO until the next tuning change.
   */
  def resetTuner(): Unit = {
    tunerProcessor.foreach(_.reset())
  }

  /**
   * Releases the output of this track after its input got disconnected, so that no note stays held on it. It:
   *
   *   1. sends All Notes Off on each of the 16 MIDI channels straight to the output of the track, bypassing the tuner,
   *      so that it reaches every channel the tuner may have used, such as MPE Member Channels;
   *   1. resets the tuning changers, so that a trigger held when the input disappeared does not swallow the first
   *      trigger after it comes back;
   *   1. resets the tuner, as [[resetTuner]] does, which also clears the note state of tuners that keep one.
   *
   * The track keeps no state of its own about held notes.
   */
  def releaseInput(): Unit = {
    val outputReceivers = transmitter.receivers
    for (channel <- 0 until Track.MidiChannelCount; outputReceiver <- outputReceivers) {
      outputReceiver.send(AllNotesOffMidiMsg(channel), -1)
    }

    tuningChangeProcessor.foreach(_.reset())
    resetTuner()
  }
```

- In `object Track`, add:

```scala
  /** The number of channels of a MIDI 1.0 connection. */
  private val MidiChannelCount: Int = 16
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add tuner/src
git commit -m "$(cat <<'EOF'
[#131][#288] Let a track reset its tuner and release its output

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `TrackManager` reacts to its devices opening and disconnecting

Design 5.3.

**Files:**
- Modify: `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`
- Create: `tuner/src/test/scala/org/calinburloiu/music/microtonalist/tuner/TrackManagerTest.scala`

**Interfaces:**
- Consumes: Task 2's events with `endpointType`; Task 7's `Track.resetTuner()` and `Track.releaseInput()`; Task 1's
  `FakeMidiDeviceHandle` and `RecordingMidiReceiver`.
- Produces: a private `@Subscribe` handler `onMidiEvent(event: MidiEvent): Unit` on `TrackManager`. `replaceAllTracks`
  forgets the closed tracks before it builds the new ones.

- [ ] **Step 1: Write the failing event tests**

Create `TrackManagerTest.scala`:

```scala
package org.calinburloiu.music.microtonalist.tuner

import com.google.common.eventbus.EventBus
import org.calinburloiu.businessync.Businessync
import org.calinburloiu.music.scmidi.*
import org.calinburloiu.music.scmidi.message.{AllNotesOffMidiMsg, CcMidiMsg, MidiCc, MidiMsg}
import org.scalamock.stubs.{Stub, Stubs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TrackManagerTest extends AnyFlatSpec with Matchers with Stubs {

  private val initMessage: MidiMsg = CcMidiMsg(0, MidiCc.DataEntryMsb, 2)

  private val allNotesOff: Seq[MidiMsg] = (0 until 16).map(AllNotesOffMidiMsg(_))

  private val keyboardId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Seaboard", "ROLI")
  private val pianoId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - FP-90", "Roland")
  private val controllerId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Keystation", "M-Audio")
  private val synthId: MidiDeviceId = MidiDeviceId("CoreMIDI4J - Minilogue", "KORG")

  /** A tuner whose reset sends [[initMessage]], which tunes nothing and lets every message through. */
  private class ResetTuner extends Tuner {
    override val typeName: String = "reset"

    override def reset(): Seq[MidiMsg] = Seq(initMessage)

    override def tune(tuning: Tuning): Seq[MidiMsg] = Seq.empty

    override def process(message: MidiMsg): Seq[MidiMsg] = Seq(message)
  }

  /**
   * A [[TrackManager]] registered on a real bus, over a stubbed [[MidiManager]] that opens every device as a
   * [[FakeMidiDeviceHandle]] whose receiver records what the device gets. Its two tracks are built, and the messages
   * that building them sent are forgotten: "piano" plays the keyboard on the piano, and "synth" plays the controller on
   * the synth.
   */
  private trait Fixture {
    val businessync: Businessync = Businessync(EventBus())

    val deviceReceivers: Map[MidiDeviceId, RecordingMidiReceiver] =
      Seq(keyboardId, pianoId, controllerId, synthId).map(_ -> RecordingMidiReceiver()).toMap

    /** Called by the stubbed manager whenever a track opens an output device; a test may replace it. */
    var onOpenOutput: MidiDeviceId => Unit = _ => ()

    val midiManager: Stub[MidiManager] = stub[MidiManager]
    midiManager.openInput.returns(deviceId => FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId)))
    midiManager.openOutput.returns { deviceId =>
      onOpenOutput(deviceId)
      FakeMidiDeviceHandle(deviceId, deviceReceivers(deviceId))
    }
    midiManager.closeInput.returns(_ => ())
    midiManager.closeOutput.returns(_ => ())

    val trackSpecs: TrackSpecs = TrackSpecs(Seq(
      TrackSpec("piano", "Piano", input = Some(DeviceTrackInputSpec(keyboardId, None)), tuner = Some(ResetTuner()),
        output = Some(DeviceTrackOutputSpec(pianoId, None))),
      TrackSpec("synth", "Synth", input = Some(DeviceTrackInputSpec(controllerId, None)), tuner = Some(ResetTuner()),
        output = Some(DeviceTrackOutputSpec(synthId, None)))
    ))

    val trackManager: TrackManager =
      TrackManager(midiManager, TuningService(TuningSession(businessync), businessync))
    businessync.register(trackManager)
    trackManager.replaceAllTracks(trackSpecs)
    deviceReceivers.values.foreach(_.clear())
  }

  behavior of "a MIDI device event"

  it should "reset the tuner of the tracks whose output device got opened, and of no other track" in new Fixture {
    // When
    businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiEndpointType.Output))

    // Then
    deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
    deviceReceivers(synthId).messages shouldBe empty
  }

  it should "ignore the opening of an input device" in new Fixture {
    // When
    businessync.publish(MidiDeviceOpenedEvent(pianoId, MidiEndpointType.Input))
    businessync.publish(MidiDeviceOpenedEvent(keyboardId, MidiEndpointType.Input))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }

  it should "release the output of the tracks whose input device got disconnected, and of no other track" in
    new Fixture {
      // When
      businessync.publish(MidiDeviceDisconnectedEvent(keyboardId, MidiEndpointType.Input))

      // Then
      deviceReceivers(pianoId).messages shouldEqual allNotesOff :+ initMessage
      deviceReceivers(synthId).messages shouldBe empty
    }

  it should "release the output of the tracks whose input device failed to disconnect" in new Fixture {
    // When
    businessync.publish(
      MidiDeviceFailedToDisconnectEvent(keyboardId, MidiEndpointType.Input, IllegalStateException("Cannot close")))

    // Then
    deviceReceivers(pianoId).messages shouldEqual allNotesOff :+ initMessage
    deviceReceivers(synthId).messages shouldBe empty
  }

  it should "ignore the disconnection of an output device" in new Fixture {
    // When
    businessync.publish(MidiDeviceDisconnectedEvent(keyboardId, MidiEndpointType.Output))
    businessync.publish(MidiDeviceDisconnectedEvent(pianoId, MidiEndpointType.Output))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }

  it should "ignore the other MIDI events" in new Fixture {
    // When
    businessync.publish(MidiEnvironmentChangedEvent)
    businessync.publish(MidiDeviceConnectedEvent(pianoId, MidiEndpointType.Output))
    businessync.publish(MidiDeviceClosedEvent(keyboardId, MidiEndpointType.Input))

    // Then
    deviceReceivers.values.flatMap(_.messages) shouldBe empty
  }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Compile `tuner-test`, then run:
`sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackManagerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. The three "reset" and "release" tests find no messages. The "ignore" tests pass already, which is
expected: they pin that the handler must not react.

- [ ] **Step 3: Implement the handler**

In `TrackManager.scala`, replace the `scmidi` import with
`import org.calinburloiu.music.scmidi.{MidiDeviceDisconnectedEvent, MidiDeviceFailedToDisconnectEvent, MidiDeviceId, MidiDeviceOpenedEvent, MidiEndpointType, MidiEvent, MidiManager}`
and add after `onTuningChanged`:

```scala
  /**
   * Handles the MIDI device events that concern the devices of the tracks:
   *
   *   - when an output device opens, it resets the tuner of every track whose output is that device, since the device
   *     may have (re)opened after the track was built;
   *   - when an input device gets disconnected, or fails to, it releases the input of every track whose input is that
   *     device, so that no note stays held on its output.
   *
   * @param event The MIDI event published by the [[MidiManager]].
   */
  // TODO #90 Remove @Subscribe after implementing businessync. Guava calls this handler on the thread that publishes
  //  the event, which is CoreMIDI4J's notification thread for a device change, while TrackManager is meant to be used
  //  on the business thread only.
  @Subscribe
  private def onMidiEvent(event: MidiEvent): Unit = event match {
    case MidiDeviceOpenedEvent(deviceId, MidiEndpointType.Output) =>
      // TODO #303 Restore the current tuning after resetting the tuner.
      tracksWithOutputDevice(deviceId).foreach(_.resetTuner())
    case MidiDeviceDisconnectedEvent(deviceId, MidiEndpointType.Input) =>
      // TODO #303 Restore the current tuning after resetting the tuner.
      tracksWithInputDevice(deviceId).foreach(_.releaseInput())
    case MidiDeviceFailedToDisconnectEvent(deviceId, MidiEndpointType.Input, _) =>
      // TODO #303 Restore the current tuning after resetting the tuner.
      tracksWithInputDevice(deviceId).foreach(_.releaseInput())
    case _ => // Nothing to do for the other events
  }

  private def tracksWithInputDevice(deviceId: MidiDeviceId): Seq[Track] = tracks.filter { track =>
    track.spec.input.collect { case DeviceTrackInputSpec(midiDeviceId, _) => midiDeviceId }.contains(deviceId)
  }

  private def tracksWithOutputDevice(deviceId: MidiDeviceId): Seq[Track] = tracks.filter { track =>
    track.spec.output.collect { case DeviceTrackOutputSpec(midiDeviceId, _) => midiDeviceId }.contains(deviceId)
  }
```

In the class ScalaDoc, replace "Manages a collection of MIDI tracks and updates their tuning based on external
events." with: "Manages a collection of MIDI tracks and updates them based on external events: it re-tunes every
track when the tuning changes, resets the tuner of the tracks whose output device opens, and releases the output of
the tracks whose input device gets disconnected."

- [ ] **Step 4: Run the tests to verify they pass**

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackManagerTest -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 5: Write the failing test for replacing the tracks**

Append to `TrackManagerTest`:

```scala
  behavior of "replaceAllTracks"

  it should "not deliver an event published while it builds the new tracks to the tracks it closed" in new Fixture {
    // Given
    onOpenOutput = deviceId => businessync.publish(MidiDeviceOpenedEvent(deviceId, MidiEndpointType.Output))

    // When
    trackManager.replaceAllTracks(trackSpecs)

    // Then
    // Only the new track, connecting its device receiver when it is built, resets the tuner
    deviceReceivers(pianoId).messages shouldEqual Seq(initMessage)
    deviceReceivers(synthId).messages shouldEqual Seq(initMessage)
  }
```

Run: `sbtn "tuner/testOnly org.calinburloiu.music.microtonalist.tuner.TrackManagerTest -- -oNCXEHLOPQRMWS"`
Expected: FAIL. Each device receives `initMessage` twice: once from the closed track still listed, once from the new
track.

- [ ] **Step 6: Implement**

In `TrackManager.replaceAllTracks`, right after `closeTracks()`:

```scala
    // Forget the closed tracks before building the new ones, whose devices may publish events while they open
    tracks = Seq.empty
```

Run: `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add tuner/src
git commit -m "$(cat <<'EOF'
[#131][#288] Reset or release the tracks whose MIDI devices open or disconnect

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Architecture docs and TODO markers

Design 7. This task changes documentation only; no test cycle.

**Files:**
- Modify: `docs/architecture/sc-midi/README.md`
- Modify: `docs/architecture/tuner/README.md`

**Interfaces:**
- Consumes: everything built in Tasks 1–8.

- [ ] **Step 1: Update `docs/architecture/sc-midi/README.md`**

1. In "Key types → Device handling", replace the `**MidiDeviceHandle**` paragraph (from "**`MidiDeviceHandle`** is the
   trait" through "both survive disconnect/reconnect without re-wiring.") with:

```markdown
**`MidiDeviceHandle`** is the read-only trait for a handle to a single device identified by a `MidiDeviceId`.

- **Ownership.** The manager creates the handle and is the only one to change its state. A consumer requests a device
  with `openInput` / `openOutput` and releases it with `closeInput` / `closeOutput`, both reference-counted; otherwise
  it only inspects the handle and uses it for I/O.
- **Disconnected devices.** A handle can exist for a device that is **not currently connected**:
  `info: Option[MidiDeviceInfo]` is defined only while it is connected, and `isInputDevice` / `isOutputDevice` /
  `endpointType` derive from it. A request made while the device is not connected moves the handle to
  `WaitingToOpen`, and the handle opens once the device gets connected.
- **States.** The `State` enum in the companion captures the Closed/Connected/WaitingToOpen/Open transitions, drawn in
  its ScalaDoc. A failed transition sets to false the property it concerns: a failure of the connection leaves the
  handle not connected, and a failure to open or close the device leaves it not requested to open. `isConnected`,
  `isOpen` (true only in `Open`) and `isOpenRequested` all derive from `state`, so they cannot disagree.
- **Liveness.** A handle is **live** while its manager holds it, which is exactly while its state is not `Closed`. A
  handle that reaches `Closed` is forgotten and stays `Closed`; a later request for the same id returns a new handle.
- **I/O.** Callers **send** to an output via `handle.receiver: MidiReceiver` and **subscribe** to an input via
  `handle.transmitter: ConcurrentMidiTransmitter`. Both survive disconnect/reconnect without re-wiring.
```

2. In the same section, replace the text from "**The Java Sound implementation** (`javamidi`)." through "…converts
   with `asScala` into an internal `MidiSplitter(ConcurrentMidiTransmitter())`." with the following. Keep the
   `JavaMidiEnvironment` sentences that follow it.

```markdown
**The Java Sound implementation** (`javamidi`). `JavaMidiManager(businessync, environment = CoreMidi4JEnvironment)`
keeps two internal endpoints, one for inputs and one for outputs. Each is a registry of its **live handles**: one
`JavaMidiDeviceHandle` for every device that is connected, requested to open, or both.

- **Refresh.** Each `refresh()` resolves every `MidiDevice` once and builds its `MidiDeviceInfo` through
  `JavaMidiConverters.asMidiDeviceInfo` (Java Sound's `-1` becomes `Unlimited`). It then reconciles each registry with
  the result: a found device is handed to the handle of its id (created if there is none) with `connect`, and a
  connected handle whose device was not found gets `disconnect` and is forgotten if that leaves it `Closed`.
- **Replugged and swapped devices.** The device **resolved last** for an id wins, and a handle compares device
  instances. CoreMIDI4J keeps one `MidiDevice` per endpoint while the endpoint stays present and creates a new one
  when it reappears. Another instance under a still-present id therefore means the device was replugged or swapped
  between two refreshes: a `Connected` handle swaps it silently, and an `Open` one closes the old device and opens the
  new one, reporting *closed* and *opened* but not *disconnected* and *connected*.
- **Locking and publishing.** A manager-wide `ReentrantLock` serialises `refresh()`, the open/close operations,
  `close()` and the registry reads (lock order: manager, then handle). Resolution happens before taking the lock. The
  events an operation collects are published in order only after the lock is released, because Guava delivers them
  synchronously and `TrackManager`'s handler sends MIDI.

`JavaMidiDeviceHandle` is the `@ThreadSafe` handle over a `javax.sound.midi.MidiDevice` for one direction, its
`direction`, which its events carry. The device is reachable only through its
`private[javamidi] device: Option[MidiDevice]`.

- **Commands.** Its five `private[javamidi]` commands (`connect`, `disconnect`, `open`, `close` and `closeAll`) are
  called only by the manager and return the `MidiEvent`s of their transitions instead of publishing them.
- **Transactional transitions.** A failed open closes the device as far as it can and rolls back to `Connected` with
  no reference held. A failed close still moves to `Connected`. A failed disconnect still leaves the handle
  disconnected.
- **Java Sound boundary, outbound.** Its receiver converts each `Midi1Msg` with `asJava` and sends it to the open
  device; a `Midi2Msg` is dropped with a warning, since Java Sound speaks MIDI 1.0 only.
  - It sends through the single Java `Receiver` it obtains from an output device each time it opens it. A Java Sound
    device creates a new receiver on every `getReceiver` call and keeps it until it is closed, so asking per message
    would leak one per message. A device that cannot provide one fails the open with `MidiDeviceFailedToOpenEvent`.
  - The receiver reads that Java `Receiver` from a volatile field defined only while the handle is open, so sending
    takes no lock.
  - A send that hits a receiver Java Sound already closed (a device vanishing before CoreMIDI4J reports it) is
    dropped, with a warning once per open (#302).
- **Java Sound boundary, inbound.** The Java `Receiver` it hands to the device's transmitter converts with `asScala`
  into an internal `MidiSplitter(ConcurrentMidiTransmitter())`.
```

3. Replace the `**MidiEvent**` paragraph with:

```markdown
**`MidiEvent`** is a sealed `BusinessyncEvent` hierarchy covering everything a `MidiManager` implementation publishes
on the bus.

- `MidiEnvironmentChangedEvent` signals a change to the environment.
- The rest come as success/failure pairs for each lifecycle transition (connected/disconnected/opened/closed), each
  failure event (`…FailedTo…Event`) carrying the cause.
- All carry the `MidiDeviceId`. All but `MidiDeviceFailedToConnectEvent`, which is published before resolution tells
  the direction, also carry an `endpointType`: always `Input` or `Output`, the direction of the handle that made the
  transition.

Note that "connected" means *available to the system*, not *opened by the application*: they are distinct,
separately evented states.
```

4. In "How MIDI devices are opened, enumerated, and used", replace steps 4 and 5 with:

```markdown
4. Use the handle: send `MidiMsg` values via `handle.receiver` (outputs), subscribe `MidiReceiver`s via
   `handle.transmitter.addReceiver` (inputs). The wiring survives disconnect/reconnect cycles: the manager hands the
   replugged device to the same live handle.
5. `closeInput`/`closeOutput` (reference-counted) release a device. The handle is read-only and has no `close()`;
   `Track.close()` releases its devices this way. `MidiManager.close()` releases every reference held through the
   manager, so every device it opened ends up closed, and stops watching the environment.
```

5. Replace the body of "Device lifecycle and events" with:

```markdown
`JavaMidiManager`'s endpoints reconcile the scanned device set against their live handles on every `refresh()`. Every
handle transition reports exactly one [`MidiEvent`](#device-handling), and a failure event replaces its success event:

- *connected* / *disconnected* on `connect` / `disconnect`; `…FailedToDisconnect` replaces *disconnected* when releasing
  the device throws, and the handle ends up disconnected either way. Only the first refresh that sees an id reports it
  connected.
- *opened* on every entry into `Open`, or `…FailedToOpen`. This includes a `WaitingToOpen` handle whose device gets
  connected, and a device swap.
- *closed* on every exit from `Open`, or `…FailedToClose`: releasing the last reference, a disconnection, or a swap. An
  open handle whose device gets unplugged reports *closed*, then *disconnected* (or only `…FailedToDisconnect`).

Other events around a refresh:

- A refresh triggered by the platform is preceded by `MidiEnvironmentChangedEvent`.
- A device that fails to resolve for an unexpected reason is reported by `MidiDeviceFailedToConnectEvent`.
- A device working in both directions is reported once per direction, by two events that differ in their
  `endpointType`.

Delivery and subscribers:

- The events are published after the manager releases its lock, synchronously on the publishing thread. For a
  platform change, that is CoreMIDI4J's notification thread.
- `TrackManager` (in `tuner`) is the first subscriber. It resets the tuner of the tracks whose output device opens and
  releases the output of the tracks whose input device disconnects (see
  [`tuner`](../tuner/README.md#device-changes)).
- It subscribes through Guava's `@Subscribe`, since `Businessync.subscribe` is still a stub (#90).
```

6. In "Notes / subject to change", delete the first bullet (the `JavaMidiManager does not yet keep its handles up to
   date …` one, with its `#288` / `TODO #131` text). Add as the first bullet:

```markdown
- A vanished device is noticed only when CoreMIDI4J reports the change: until then a send to it is dropped. A handle
  whose device failed to open stays `Connected` with no reference held, and so unusable by the track that requested
  it, until the tracks are rebuilt (#302).
```

- [ ] **Step 2: Update `docs/architecture/tuner/README.md`**

1. In "The processor pipeline" paragraph, append: "`TunerProcessor.reset()` resets the tuner and sends its
   initialization messages to every current receiver, and `TuningChangeProcessor.reset()` resets its tuning changers;
   `Track` calls both when its devices change."
2. Replace the "**Track and lifecycle.**" paragraph with:

```markdown
**Track and lifecycle.**

- `Track` (`@ThreadSafe`) is one instrument pipeline built from a `TrackSpec`. It opens the input/output MIDI devices
  via `MidiManager` and assembles the processor chain (see [Track pipeline](#track-pipeline)).
  - `close()` switches back to 12-EDO and releases its devices through `MidiManager.closeInput` / `closeOutput`.
  - `resetTuner()` re-initialises the output instrument, and `releaseInput()` silences it after its input device
    disappears (see [Device changes](#device-changes)).
- `TrackSpec` / `TrackSpecs` are the declarative description of a track and an immutable, id-keyed ordered collection
  of them.
- `TrackIO` holds the input/output spec plugins, including inter-track routing (`FromTrackInputSpec` /
  `ToTrackOutputSpec`).
- `TrackManager` (`@NotThreadSafe`) builds and replaces the live tracks from `TrackSpecs` and wires inter-track
  connections. It re-tunes every track when the tuning changes (it subscribes to `TuningEvent`), and reacts to the
  devices of its tracks opening and disconnecting (it subscribes to `MidiEvent`).
```

3. Add a section after "Tuning-change flow":

````markdown
## Device changes

`TrackManager` keeps the tracks working while their MIDI devices come and go (#131):

```
MidiDeviceOpenedEvent(id, Output)             (an output device (re)opened)
  → TrackManager.onMidiEvent
  → Track.resetTuner() for every track whose output is DeviceTrackOutputSpec(id)
  → TunerProcessor.reset() → Tuner.reset() messages → output device

MidiDeviceDisconnectedEvent(id, Input) or MidiDeviceFailedToDisconnectEvent(id, Input, _)
  → TrackManager.onMidiEvent
  → Track.releaseInput() for every track whose input is DeviceTrackInputSpec(id)
  → All Notes Off on channels 0–15, straight to the track's output (bypassing the tuner)
  → TuningChangeProcessor.reset() → Track.resetTuner()
```

- Every other `MidiEvent` is ignored.
- A device already connected when its track is built needs no event: connecting the device receiver as an initial
  receiver of the pipeline already sends the tuner's reset messages.
- `replaceAllTracks` forgets the closed tracks before building the new ones, so an event published while the new
  tracks open their devices never reaches a closed track.
- The reset does not restore the current tuning. Until #303, the instrument plays in 12-EDO until the next tuning
  change.
````

4. In "Threading model", add the bullet: "- `TrackManager`'s `MidiEvent` handler runs on the thread that publishes the
   event, which for a device change is CoreMIDI4J's notification thread, not the business thread (#90)."
5. In "Subject to change", add:

```markdown
- `TrackManager`'s `MidiEvent` handler runs on the publishing thread instead of the business thread (TODO #90).
- Resetting a track's tuner on a device event does not restore the current tuning (TODO #303).
```

- [ ] **Step 3: Check the TODO markers**

Run each and compare with the expected output:
- `git grep -n "TODO #288\|TODO #131"`: no output outside `issues/`.
- `git grep -n "TODO #302" -- '*.scala'`: one hit, in `JavaMidiDeviceHandle.scala`.
- `git grep -n "TODO #303" -- '*.scala'`: three hits, in `TrackManager.scala`.
- `git grep -n "TODO #90" -- tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/TrackManager.scala`: two
  hits (`onTuningChanged` and `onMidiEvent`).
- `git grep -n "onConnect\|onDisconnect" -- sc-midi docs/architecture/sc-midi`: only the `MidiProcessor` hooks and
  their users, never a handle.

- [ ] **Step 4: Commit**

```bash
git add docs/architecture
git commit -m "$(cat <<'EOF'
[#131][#288] Document hot plugging MIDI devices in the architecture docs

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: Final checks

The workflow checks of `CLAUDE.md`. Create one task for each step.

- [ ] **Step 1: Module tests**

Run each, expecting PASS:
- `sbtn "sc-midi/testOnly * -- -oNCXEHLOPQRMWS"`
- `sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"`
- `sbtn "cli/testOnly * -- -oNCXEHLOPQRMWS"`
- `sbtn "app/testOnly * -- -oNCXEHLOPQRMWS"`

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill and follow its policy for the `sc-midi` and `tuner` modules. Check:
- the module floors (80% statements / 80% branches for both);
- `JavaMidiManager`, `JavaMidiDeviceHandle`, `Track`, `TrackManager`, `TunerProcessor` and `TuningChangeProcessor`.

For every uncovered line in code this branch changed, add a test (red if it pins missing behaviour, otherwise a
characterisation test) until the targets are met. Ignore the scala-logging macro branches on log-call lines, which
cannot be covered. Commit the added tests with the message
`[#131][#288] Cover <what> in <TestClass>`.

- [ ] **Step 3: Full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: PASS.

- [ ] **Step 4: Documentation**

- Review the ScalaDoc of every changed public identifier listed in design 7: `MidiDeviceHandle` (and its `State`),
  `MidiManager`, `MidiEvent` and its device events, `JavaMidiManager`, `JavaMidiDeviceHandle`, `Track`,
  `TrackManager`, `TunerProcessor`, `TuningChangeProcessor`.
- Check them against the final code, and re-run Task 9 Step 3.
- Fix and commit any drift with the message `[#131][#288] Align the documentation with the implementation`.

- [ ] **Step 5: Report**

Report the branch state (`git log --oneline refactoring/sc-midi-javamidi-tests..HEAD`), the test and coverage results,
and anything left for #302 / #303. Do not push and do not open a PR.

