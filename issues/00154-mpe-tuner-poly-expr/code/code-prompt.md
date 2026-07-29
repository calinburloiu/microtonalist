# Update Paper with Polyphonic Expression for MPE Tuner (Prompt)

Your task is to update and refactor the incomplete MPE Tuner implementation (from `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`) according to the MPE Tuner paper (from `docs/architecture/tuner/mpe-tuner-paper.md`). For reference, here is the MPE Specification on which the MPE Tuner is based: `docs/architecture/tuner/mpe-spec.md`.

Note that sections, facts and bullets are numbered / identified to facilitate discussions.

Use /superpowers:brainstorming . We are working on issue #154, so write superpowers' artifacts to `issues/00154-mpe-tuner-poly-expr/code/`.

## 1. Changes overview

* Resolve TODO: "Warn when MpeTuner is configured with non-MPE input mode while both zones are enabled".

Input-mode-specific behavior changes are described in the next sections.

### 1.1. MPE Input Mode

* (a) **Expression Value Processing**. Any incoming note has an Expression Value for each of the three control dimensions (Expression Pitch Bend, Channel Pressure and CC #74), see paper subsection 1.3. These values are taken from the input Member Channel. Multiple notes may come from the same input channel and in this case, all have the same Expression Values. Due to the specific rules of the MPE Tuner, incoming notes from the same input channel may be mapped to different output channels if they have different pitch classes. Multiple notes, with the same pitch class may be mapped to the same output channel when they come from different input channels. Each Expression Value for a control dimension on an output channel is computed as the average of the incoming notes values for that control dimension (see paper section 7). Remember that the final Pitch Bend for an output channel is computed as the sum of the Expression Pitch Bend (which is the average of the incoming notes Pitch Bend) and the Tuning Pitch Bend for that output channel (which is the tuning offset of the channel's pitch class).
* (b) **Omit unchanged control dimensions**. An MPE Tuner implementation is not required to emit all three control dimensions before a Note On. It may choose to optimize by only emitting those that changed.
* (c) **Empty channels keep the last values of the control dimensions**. Averaging of the Expression Values for the control dimensions is only used when there is at least one note on an output channel. But when the channel becomes empty, it will preserve its latest Expression Values. This not only helps with the mentioned optimization of only emitting control dimensions when they change, but it also avoids a division by zero if we would calculate the average for an Expression Value for zero incoming notes.
* (d) **Input channels are tracked for control dimensions**. Even if there are no active notes on an input Member Channel, the MPE Tuner must remember the values of the three control dimensions received on that channel such that when a new note arrives it will be initialized with those control dimension values as Expression Values. Remember that the incoming note Expression Values must be averaged on the output channel. When there are no active notes on an input Member Channel, there is no need to emit control dimensions to the output Member Channels.
    - `ScMidiChannelStateTracker` is already used in `MpeTuner` to track the state of the input channels. When a note is allocated, it may pull the latest Expression Value from `ScMidiChannelStateTracker`.
* (e) **Fan-out control dimension updates**. If there are active notes on an input channel, when a control dimension update is received on that channel, for each active note, update that note's contribution to the average of the Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

### 1.2. Non-MPE Input Mode

* (a) In Non-MPE Input Mode only Pitch Bend and Channel Pressure control dimensions are used on an output Member Channel. From these, only Channel Pressure can be used as an Expression Value. Pitch Bend is only used for tuning (the Tuning Pitch Bend component), there is no per-channel Expression Pitch Bend. Pitch Bend messages received on an input channel are forwarded to the Master Channel and applies to all channels. CC #74 does not appear on an output channel and if it's received on an input channel it's forwarded on the output Master Channel.
* (b) A Channel Pressure from an output Member Channel always originates from a converted Polyphonic Key Pressure received on an input channel. A Channel Pressure received on an input channel is always forwarded to the Master Channel.
* (c) A Polyphonic Key Pressure received from an input channel is assumed to be applied to an active note. If one is received on a note for which a Note On was not issued on that input channel, it's ignored. So a Polyphonic Key Pressure always has value 0 for a note at the time a Note On message is issued for it.
* (d) When there are multiple active notes on an output Member Channel, their Channel Pressure Expression Value (that originates from a Polyphonic Key Pressure) is averaged, similar with MPE Input Mode, but dissimilar to it, it does not preserve its latest value on Note Off, but instead resets it. Remember that it must be reset because it originates from a Polyphonic Key Pressure which always has a 0 value on Note On.
* (e) When a Polyphonic Key Pressure update is received on an input channel, update that note's contribution to the average of the output Channel Pressure Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

## 2. Implementation details

### 2.1. Changes in `MpeChannelAllocator`

* (a) Move `channelNoteMap` from `MpeTuner` to `MpeChannelAllocator`. In this way, the latter is input-channel-aware and the former uses the latter if it needs to know what active notes are on an input channel and where each is mapped. Note that the information about what active notes are on an input channel can also be found via `ScMidiChannelStateTracker`.
* (b) The change mentioned in the previous bullet also introduces the new *Note Identify* concept from the paper to `MpeChannelAllocator` which is a pair of input channel and MIDI note number (a note with its origin). One implication of this is that `ChannelState` will have `_notes` keyed by Note Identify instead of just `MidiNote`. As a side note, we probably don't need `LinkedHashMap` anymore and we can just use a `HashMap` (see details in the TODO above `_notes` declaration).
* (c) Note how `MpeExpression` (and its implementations) represents all three Expression Values. Make sure that the Expression Value concept is mentioned in the trait ScalaDoc.
* (d) Move the responsibility to *aggregate* Expression Values from `MpeTuner` to `MpeChannelAllocator`. The latter has every information to do this. This may be done at the `ChannelState` level. Note that `MpeTuner` is only averaging Expression Pitch Bend today, but `MpeChannelAllocator` will need to aggregate / average all three Expression Values. It also needs to implement the new logic of keeping the last Expression Values when the output channel becomes unoccupied (Channel Pressure in Non-MPE Input Mode is except from this logic); otherwise it will use average.
* (e) Some of the public methods of `MpeChannelAllocator` need a mechanism to informs which Expression Value(s) need updates on the output channel. This is because they affect the set of Note Identifies that occupy an output channel and the aggregated Expression Values update. `MpeChannelAllocator` knows the aggregated Expression Values before and after a method call. For those that changed, it can inform the caller of the method that the values changed. For example, for `allocate`, in Non-MPE Input Mode, the Slide (CC #74) is not used as an Expression Value, so its value if 64 both before and after the call, so it never changes. The way to inform which Expression Values change is by wrapping each Expression Value from the method returned result into a Scala `Option`; if it's `None` than it didn't change, if it's `Some` value, then it changed to the value inside it. Those three `Option`s for Expression Values may be stored into a case class.
* (f) `allocate` method:
    - Receives a Note Identify and an `MpeExpression` object (can be `ImmutableMpeExpression`). The latter object may be optional and have default values for the three Expression Values; the default is always used in Non-MPE Input Mode.
    - When comparing with the current `allocate` method, note that `preferredChannel` is swallowed by the Note Identify (it's upgraded from a preference to an important way to identify a note), and `expressivePitchBendCents` is included in the `MpeExpression` object. 
    - The new version of the method is also responsible to update the `channelNoteMap` moved from `MpeTuner` and to provide initial values for all Expression Values.
    - `AllocationResult` also informs which Expression Value(s) need updates on the output channel as documented above.
* (g) `release` method:
    - Also receives a Note Identify instead of just note. This allows better precision when excluding a note from an allocated output channel and better update the aggregated Expression Values. The new version of the method continues to receive the output (allocated) channel as parameter.
    - Receives a boolean flag as parameter which tells whether pressure needs to be reset. This is used for Non-MPE Input Mode where the Channel Pressure converted from Polyphonic Key Pressure needs to be reset just before the Note Off when an output channel becomes unoccupied. This allows resetting the internal state for pressure in that case, instead of keeping the last value (as for MPE Input Mode).
    - The new version of the method is also responsible to update the `channelNoteMap` moved from `MpeTuner`.
    - A release may affect the aggregated Expression Values. So it needs to return a result which informs, similar with `allocate` method, if the updated Expression Value(s) need updates on the output channel. It should use the same mechanism.
* (h) Add separate methods for updating each Expression Value.
    - Note that today we only have one for Expression Pitch Bend, named `updateExpressivePitchBend`, but this needs to be renamed to `updateExpressionPitchBend` and have a different signature according to the new requirements.
    - Will also need similar methods for the other Expression Values: `updatePressure` and `updateSlide`.
    - All three methods may have common code.
    - Each of the three methods receives the input channel where the value was updated and the new value. This is different from the current version of `updateExpressivePitchBend` which receives an output channel (that was wrong!). The new methods will perform the newly documented fan-out from the paper where an Expression Value update from an input channel is distributed to all its active notes and affects the aggregated Expression Values of each output channel where those notes were allocated.
    - These new methods return a result which informs, similar with `allocate` and `release` methods, if the updated Expression Value needs an update on the output channel. It should use the same mechanism.
    - In the case of the new `updateExpressionPitchBend`, a sequence of `DroppedNotes` is also returned in case of a High Expression Pitch Bend. This is different from the result of the current `updateExpressivePitchBend` which contains a single `Option` for `DroppedNotes`. The reason we have multiple values now, is because of the fan-out mechanism which may affect multiple output channels. When an Expression Pitch Bend Expression Value is updated on the input channel, the output channels where each its active notes were allocated are affected.
* (i) Keep `MpeChannelAllocator` unaware of Input Mode. The mechanism for informing about which Expression Value changed and the flag for resetting pressure on `release` should allow this.

### 2.2. Changes in `MpeTuner`

* (a) In MPE Input Mode, `MpeTuner` must pass the control dimensions from the input Member Channel as Expression Values to `allocate` method of `MpeChannelAllocator`. In Non-MPE Input Mode, those always have the default values. The control dimensions from the input Member Channel are tracked by `ScMidiChannelStateTracker`. The return value of `allocate` informs which Expression Values need to be updated such that `MpeTuner` can emit the control dimensions to the allocated Member Channel. It also informs if there are any dropped notes.
* (b) The `release` method call on `MpeChannelAllocator` also informs which Expression Values need to be updated. In Non-MPE Input Mode, if the target channel on which the note was released becomes unoccupied, the Channel Pressure needs to be reset. In this case it needs to make sure that the Channel Pressure reset happens before the Note Off and the rest of the control dimensions are updated after the Note Off (if necessary).
* (c) When a control dimension is updated on an input channel in MPE Input Mode, its update method is called from `MpeChannelAllocator`. The return value informs if the Expression Value needs to be updated on the output Member Channels or if their notes need to be dropped. In Non-MPE Input mode Polyphonic Key Pressure values are converted to Channel Pressure Expression Values, for notes active on the input channel, and participate to Expression Value aggregation on the allocated output channel.
* (d) How are various *other* MIDI messages handled in MPE Input Mode:
    - when received on an input Member Channel:
        - Pitch Bend Sensitivity: accepted
        - MCM: accepted on channel 1 and 16 (1-based)
        - Polyphonic Key Pressure, Program Change, Bank Select, Reset All Controllers (CC #121), MIDI Mode Messages 124–127, any other CC Message: blocked
    - when received on an input Master Channel:
        - Pitch Bend Sensitivity, Polyphonic Key Pressure, Program Change, Bank Select, Reset All Controllers (CC #121), any other CC Message: accepted
        - MCM: accepted on channel 1 and 16 (1-based)
        - MIDI Mode Messages 124–127: blocked
* (e) How are various *other* MIDI messages handled in Non-MPE Input Mode:
    - Pitch Bend Sensitivity, Program Change, Bank Select, any other CC Message: forwarded to output Master Channel
    - MCM: accepted on channel 1 and 16 (1-based)
    - Reset All Controllers (CC #121), All Sound Off (Channel Mode message 120) and All Notes Off (Channel Mode message 123): do not clear `MpeTuner`'s or `ScMidiChannelStateTracker`'s internal state and are forwarded to output Master Channel.
    - Polyphonic Key Pressure: converted to Channel Pressure Expression Value and used on output Member Channels
    - MIDI Mode Messages 124–127: blocked
* (f) Any RPN / NRPN message sequence must be forwarded atomically to avoid issues with interleaved streams. Those interleaved streams appear naturally in Non-MPE Mode when messages can come from multiple channels and RPN / NRPN sequences must be forwarded to some specific channels.
    - Setting the RPN / NRPN selector numbers requires two separate messages. Those are not forwarded immediately, but instead are stored and change the internal state of `ScMidiChannelStateTracker`.
    - Only when a value-changing message comes (Data Entry, Date Increment/Decrement), the whole sequence is emitted, including the full selector. The selector is always emitted before such a message, even if it's verbose. Data Entry has separate messages for MSB and LSB, so the full sequence including the selector is emitted for each of those. That's fine!

## 3. Deliverables

These deliverables are derived from the report that tracks the gaps between the implementation and the paper from `issues/00154-mpe-tuner-poly-expr/paper/paper-impl-gap-report.md`. **But you should not read that file!** Its relevant content is copied here verbatim and commented. That's why the content here focuses more on the current state of the implementation and why is not consistent with the paper. References in this section are to the paper.

### P1. Per-note Expression Value model for Channel Pressure and CC #74 (§1.3, §5.7, §7.1–7.3)

The paper's central polyphonic-expression model: each note carries three Expression Values
(Expression Pitch Bend, Channel Pressure, CC #74), and each output Member Channel emits the
**average** of its active notes' values per dimension.

In the implementation, only the Expression Pitch Bend dimension participates in this model
(`computeOutputPitchBend`, `MpeTuner.scala:635-648`, averages `pitchBendCents` over active notes).
For CP and CC #74:

- `MutableMpeExpression` has `pressure` and `slide` fields, but they are set once at allocation and
  **never updated afterwards**. Confirmed at `MpeChannelAllocator.scala:412`: `doAllocate` calls
  `MutableMpeExpression(expressivePitchBendCents)`, passing *only* the bend, so `pressure` and
  `slide` always take their defaults (0 and 64). TODO at `MpeChannelAllocator.scala:61`.
- At Note On, `MpeTuner` seeds the output channel's CC #74 / CP directly from the tracker's
  last-known per-*input-channel* value (`MpeTuner.scala:242-246`), bypassing the per-note model —
  no averaging with other notes already on the shared output channel.
- Incoming CC #74 / CP updates are fanned out raw to all output channels fed by the input channel
  (`forwardToMemberChannel`, `MpeTuner.scala:596-600`), overwriting the whole channel value.
  Per §7.2, they should update only the contributions of the notes originating from that input
  channel and re-emit the recomputed average.

Acknowledged by `MpeChannelAllocator.scala:81-82` (`TODO #154 Add a channelExpression field …
averaged across all channel notes`).

Worked example §9.3 traces the full three-dimension averaging through a concrete
sequence and is the most direct test oracle for this item.

The whole Expression Value aggregation / averaging mechanism move to `MpeChannelAllocator`.

### P2. Initial Expression Pitch Bend not seeded from remembered input-channel state (§7.2)

§7.2 "State retention on input channels": when a note arrives on an input Member Channel, its
Expression Values are initialized from the channel's remembered control state. A conforming MPE
sender sends Pitch Bend *before* Note On, so this is the normal path, not an edge case.

The implementation tracks input Pitch Bend (`ScMidiChannelStateTracker`), but
`MpeTuner.scala:221` calls `alloc.allocate(midiNote, preferredChannel = preferredChannel)`
**without** `expressivePitchBendCents`, so the parameter takes its default of `0.0`
(`MpeChannelAllocator.scala:247`) — the note always starts with Expression PB = 0. Two
consequences:

- Pitch Bend sent by the sender before Note On on an input Member Channel is silently lost
  (at that moment `outputChannelsFor` is empty, so nothing is emitted, and the tracked value is
  never read back).
- §6.2.2 ("new note *with* a High Expression Pitch Bend on an occupied channel") is dead code:
  `dropExistingNotesForHighBend` supports it (`MpeChannelAllocator.scala:429-447` — the
  `newHighBend` disjunct at line 438), but the new note's bend is always 0, so only the §6.2.3 half
  (`existingHighBend`) can trigger.

Worked example §9.3 step 1 is explicit that a note is initialized from its input channel's
remembered Pitch Bend, Channel Pressure, and CC #74.

The initial Expression Values should be seeded from `ScMidiChannelStateTracker`.

### P3. Update propagation: input channel → note mapping (§7.2)

§7.2 "Update propagation": a control message on an input Member Channel updates the contribution
of *each note active on that input channel*. The implementation instead assumes the **most
recently added note** on the output channel is the one being bent
(`MpeChannelAllocator.updateExpressivePitchBend`, lines 306-324 — `state.lastAddedNote` at lines
312 and 321; `TODO #154` at lines 293-294: "Bad assumption that the last note is being bent. To map
incoming channel to output channel.").

This is wrong whenever notes from different input channels share an output channel (fan-in): a
bend from input channel X may be applied to a note that arrived from input channel Y. The paper's
fan-in averaging (multiple input channels → one output channel, §7.2 paragraph 2) is therefore
unimplemented for all three dimensions.

- §7.6.2 names cross-input-channel fan-in of the *same note number* onto one output channel as an
  acknowledged, reachable situation with two independent identities — precisely the case the
  "last added note" assumption resolves wrongly. Worked example §9.6 Part 2 traces it. See also N2,
  which shows that case is not merely mis-attributed but unrepresentable in `ChannelState`.
- §9.5 traces the fan-in case for the divergence rule: a Pitch Bend on input Channel 2 must be
  attributed to the note that arrived from input Channel 2, not to the channel's last-added note,
  or the wrong note is dropped under §6.2.1.

The changes described in `MpeChannelAllocator` with the new `update*` methods, the introduction of the Note Identify concept and the changes from `ChannelState`, should allow resolving this deliverable.

### P4. Nothing emitted after a Note Off (§7.1, §7.5)

Two requirements, both unmet by the same omission.

§7.1: "Each time an Expression Value of an output Member Channel changes — whether because a note
entered or left the average …— the new value is sent on that channel."

§7.5 also fixes the **ordering** for the release side, inverting the Note On
order: Note Off first, then Pitch Bend, CC #74, Channel Pressure recomputed over the remaining
notes. The rationale is symmetric to the Note On case — emitting the recomputed values first would
drag the still-sounding released note toward values computed for a set it no longer belongs to. The
sole exception is the Non-MPE Channel Pressure reset of §7.4, which precedes the Note Off (see C2).

In `MpeTuner.processNoteOff` (lines 257-273) the note is released from the allocator (line 265) and
a Note Off is forwarded (line 266) — and that is the whole body. The remaining notes' new average
Pitch Bend / CP / CC #74 is **not** recomputed and emitted at all. The "note entered" direction
works (the pre-Note-On Pitch Bend at `MpeTuner.scala:233-235` includes the new note in the
average); the "note left" direction is missing entirely, so the §7.5 Note Off ordering has nothing
to order.

The new `release` method from `MpeChannelAllocator` and how it's handled in `MpeTuner` should resolve this deliverable.

### P5. Poly Pressure → Channel Pressure averaging in Non-MPE mode (§3.3 item 1, §7.3)

The conversion itself is implemented (`processPolyPressure`, `MpeTuner.scala:563-586`; the Non-MPE
branch is lines 576-585), including discarding Poly Pressure for inactive notes — the
for-comprehension at lines 579-584 yields nothing when the note is untracked. But when multiple
notes share the output channel, the paper requires the converted values to be **combined by
averaging**; line 583 emits `ChannelPressureScMidiMessage(outChannel, pressure)` with the raw value
for the addressed note, overwriting the channel's CP. It also never records the value in the note's
`MpeExpression` (same root cause as P1).

§7.3: note bookkeeping in Non-MPE mode is explicitly **per input
channel**, as the Note Identity of §5.1 requires. The implementation already satisfies that half —
the lookup at lines 580-581 goes through `channelNoteMap.get(inputChannel)` and only then
`notes.get(midiNote)` — so the Poly Pressure path is correctly identity-scoped. See N1.

The new averaging mechanism, moved to `MpeChannelAllocator`, the introduction of Note Identify and the support to reset pressure on `release` via the new flag should allow resolving this deliverable.

### P7. Forwarded Pitch Bend Sensitivity sequence not closed with an RPN Null (§4 preamble)

Introduced by the working tree's rewrite of the §4 preamble, and closable from either side. The
rewritten sentence gives one shape to every sequence the Tuner re-emits — "selector, Data Entry, and
a closing RPN Null (7F 7F) that protects the parameter from a later stray Data Entry" — where the
previous text distinguished the sequences the Tuner *originates* (selector, Data Entry, Null) from a
Data Entry it *forwards*, which was merely to be preceded by the selector.

`mcmMessages` (`MpeTuner.scala:689-698`) satisfies the rewritten rule: selector, Data Entry, then
Null. `applyPbsUpdate` (lines 485-487) does not — it emits the PBS selector and the Data Entry and
stops, which was exactly conformant under the previous wording. Either add the two Null CCs there,
or restore the paper's distinction between originated and forwarded sequences.

### N1. No reference counting per Note Identity (§5.1)

§5.1 defines a note's **Note Identity** as the pair (input channel, note number) and gives each
active identity a **reference count**: allocation runs only on the 0 → 1 transition, deallocation
only on the transition to 0, and a Note Off for an identity with no active count is discarded. The
stated purpose is MIDI 1.0's per-Note-On transmitter obligation [2, p. A-4] — exactly one forwarded
Note Off per forwarded Note On.

The two halves land differently:

- **Note Identity is already there, at the `MpeTuner` level.** `channelNoteMap` is
  `mutable.Map[Int, mutable.Map[MidiNote, Int]]` keyed by input channel and then by note
  (`MpeTuner.scala:83`, with the comment at lines 77-82 spelling out the layout). `trackNote`
  (524-526), `untrackNote` (528-534) and the Poly Pressure lookup (580-581) all key on the pair.
- **Note Identity is *not* there at the `MpeChannelAllocator` level.** `ChannelState._notes` is a
  `LinkedHashMap[MidiNote, MutableMpeExpression]` (`MpeChannelAllocator.scala:104`) keyed by note
  number alone. Two distinct identities that the allocator places on one output channel therefore
  collide in a single map entry — see N2, second sub-finding.
- **Reference counting is absent entirely.** `trackNote` uses `.update(midiNote, outChannel)`,
  which overwrites, and `untrackNote` uses `.remove(midiNote)`, which deletes outright. There is no
  count anywhere, so allocation runs on *every* Note On and deallocation on the *first* Note Off.

The consequences are N2. Note that §5.1's fourth rule — a Note Off for an identity with no active
count is discarded — is *accidentally* satisfied: `processNoteOff`'s `case None` branch (lines
269-270) logs and drops. The branch is marked `$COVERAGE-OFF$` as defensive, but under §5.1 it is
a specified path. The coverage exclusion markers should be removed because they don't work in Scala 3 anyway.

All this new functionality should be implemented in `MpeChannelAllocator` along with the introduction of Note Identify and the move of `channelNoteMap` here from `MpeTuner`.

### N5. Criterion (b) under-counts identities sharing a note number (§5.6)

§5.6 criterion (b) prefers the channel with the fewest active notes, and the latest paper version fixed the
counting semantics: the count is of **distinct Note Identities**, since an identity whose reference
count exceeds 1 still contributes a single term to each average and "must not be counted twice".

`bestCandidate` uses `s.notes.size` (`MpeChannelAllocator.scala:464`), i.e. `_notes.keySet.size`.
Because no reference counts exist (N1), over-counting cannot occur — that half is vacuously
satisfied. But because `_notes` is keyed by note number alone, two *distinct* identities sharing a
note number on one channel count as **one**, so the criterion under-counts and may pick a channel
that is in fact more heavily loaded than a rival. Minor, and it disappears once N1's identity
keying reaches the allocator.

The proposed changes in `MpeChannelAllocator` and `ChannelState` with the introduction of Note Identify should help resolve this deliverable.

### C1. CC #74 on Member Channels in Non-MPE Input Mode (§3.3 item 3, §7.3)

Paper: "the Tuner never sends CC #74 on a Member Channel" in Non-MPE mode; §7.3: "CC #74 never
appears on an output Member Channel."

Implementation (`MpeTuner.scala:242-243`): `val slide = if (inputMode == Mpe) tracker.cc(…) else 64`
followed by an unconditional `buffer += CcScMidiMessage(outChannel, ScMidiCc.MpeSlide, slide)` —
so CC #74 = 64 is emitted on the allocated Member Channel before **every** Note On in Non-MPE mode,
justified in the code comment (lines 237-241) by MPE spec §3.3.5 ("Initial-64"). One of the two
documents must yield: either the paper should permit the neutral Initial-64 seeding, or the
implementation should stop emitting it.

§4.2 now describes CC #74 64 as "the centered initial value the MPE Specification prescribes for a
bipolar third dimension [1, §3.3.5]" — the same justification the code comment gives — while
keeping it as a *retained* default rather than an emitted message. Combined with the emission
optimization of §7.5, a channel that already holds 64 would in any case be permitted to omit the
message, which narrows the practical disagreement to channels whose retained CC #74 differs.

The special return values for the updated methods from `MpeChannelAllocator` inform which Expression Values need updates on the output channel. This allows omitting CC #74 as already mentioned.

### C2. Channel Pressure reset placement in Non-MPE mode (§7.4)

Paper §7.4: in Non-MPE mode the Tuner is the
controller and performs the CP reset **at Note Off**, and the rule is now stated precisely — the
reset happens only when the released note is the **last active note on its channel** (when others
remain, the recomputed value is simply their average, reduced but not zeroed), and it is the one
control message emitted **before** the Note Off rather than after it (§7.5), the specification
requiring Channel Pressure to be zeroed "immediately before a Note On or a Note Off" [1, §3.3.4].

Implementation: emits CP = 0 before every Note On in Non-MPE mode (`MpeTuner.scala:245-246`) and
emits **nothing at Note Off** (`processNoteOff`, lines 257-273). The receiver state is correct by
the next Note On (satisfying §7.3's weaker phrasing), but between a Note Off and the next Note On
the downstream channel keeps the stale CP, affecting the release tail — exactly what §7.4's chosen
design avoids. Worked example §9.3 step 6 states the contrast explicitly.

Implement this in `MpeTuner` which is aware of Input Mode.

### C3. Scope of state reset on MCM reconfiguration (§4.2)

Paper: a Zone reconfiguration resets state only for channels "entering or leaving MPE control";
"Channels of a Zone untouched by the reconfiguration keep their notes and state."

Implementation (`processMcm`, `MpeTuner.scala:361-399`): `stopAllNotes` (line 375) iterates all of
`channelNoteMap` and so drops notes on **both** zones, and `resetState()` (line 381) clears
`channelNoteMap` and the tracker and recreates **both** allocators (lines 144-148), even when the
MCM touches only one zone and no overlap resolution occurs. Notes and retained Expression Values in
the untouched zone are lost, contrary to the paper.

### C4. Behavior when all Zones are deactivated (§4.1, §3.7)

Paper: with no Zone enabled "every channel lies outside the Zone
structure, so every Channel Voice and Channel Mode message the Tuner receives is discarded under
Section 3.7 — not only notes"; the only messages the Tuner emits are the configuration messages of
§4.2, and the only input it still acts upon is a valid MCM.

Implementation: with no enabled zone `createAllocator` returns `None` (line 719), so
`processMemberNoteOn`'s `case None` branch (lines 251-253) forwards the Note On **as-is** on its
input channel. Controls fare no better: `resolveZoneMasterChannel` returns `Some(inputChannel)` for
a channel in no zone (lines 630-631), so CCs and Program Change pass through unchanged instead of
being discarded. The conflict therefore covers the whole message stream, not just notes — hence the
raise from Minor to Medium. It is the degenerate case of I2, and the same fix resolves both.

### C5. Master Channel CC #74 and Channel Pressure forwarding in MPE mode (§3.4)

Paper §3.4: Master Channel Pitch Bend and Zone-level messages are "forwarded on the Master Channel
without modification". Per the MPE spec (§2.5–2.6, quoted in paper §2.5/2.6), CC #74 and Channel
Pressure on a Master Channel are Zone-level controls. There is exactly one exception to this
forwarding rule — the MIDI Mode messages 124–127 (§3.6, see N4) — and CC #74 and Channel Pressure
are not among them. Member Channel
paragraphs, in §3.5, govern Zone-level messages arriving on a *Member* Channel (I3), not the Master
Channel forwarding at issue here.

Implementation: only Pitch Bend and Poly Pressure special-case the Master Channel
(`processPitchBend` lines 281-283, `processPolyPressure` lines 573-575). CC #74 and Channel
Pressure go through `forwardToMemberChannel` unconditionally in MPE mode (`processCc` lines
343-346, `processChannelPressure` lines 552-554), which forwards to `outputChannelsFor(channel)`
(lines 543-545) — for a Master Channel that is the set of tracked master-channel notes. Result:

- With no active Master Channel notes, `outputChannelsFor` returns the empty set and the `foreach`
  in `forwardToMemberChannel` (line 597) emits nothing: Master CC #74 / CP is **silently dropped**
  instead of forwarded as a Zone-level control.
- With active Member Channel notes in the zone, the Zone-level control never reaches them (it is
  not re-emitted on the Master Channel).

### C6. Uninterpreted RPN/NRPN traffic on an input Member Channel (§4 preamble, §3.5, §4.2)

**Aligned — Non-MPE Input Mode.** Uninterpreted RPN selectors (CC #101/#100) match the second
selector case (`processCc`, lines 329-334); NRPN selectors (CC #99/#98), Data Entry (CC #6/#38) and
Data Increment/Decrement (CC #96/#97) match no case and fall to the catch-all (lines 350-351). In
Non-MPE mode both routes call `forwardOnZoneMasterChannel`, so the whole sequence arrives on the
output Master Channel — which is what §3.3 item 4 now requires. The guards are sound:
`ScMidiChannelStateTracker` models NRPN selection, so `rpnSelector` returns `RpnSelector.Nrpn(...)`
and neither `isMcmRpn` nor `isPbsRpn` fires spuriously.

**Aligned — MPE Input Mode on an input Master Channel.** The two routes converge there: line 333
emits the message unchanged on its own channel, and the catch-all resolves through
`resolveZoneMasterChannel`'s `case Some((zone, _)) => Some(zone.masterChannel)` (lines 628-629),
which for a Master Channel *is* that same channel. Both therefore satisfy §3.5's "forwarded
unmodified on the same Master Channel", and the previous revision's "selector and value split across
two channels" sub-finding does not arise here.

**Still a conflict — MPE Input Mode on an input Member Channel.** §3.5 requires this traffic to be
discarded, and none of it is. Uninterpreted RPN selectors are emitted unchanged on the Member
Channel (line 333) while NRPN selectors, Data Entry and Data Increment/Decrement are redirected to
the zone's Master Channel (catch-all), so the sequence is both wrongly retained and split across two
channels. The root cause is I3's — `resolveZoneMasterChannel` discarding `isMaster` — but fixing I3
does not by itself fix line 333, which needs a Member Channel case of its own.

**Still a conflict — invalid MCM.** The suppression case (line 328) matches whenever the tracked
selector is RPN 00 06, on **any** input channel, so the selector is swallowed even off Channels 1
and 16; the following Data Entry MSB then fails the `inputChannel == 0 || inputChannel == 15` guard
(line 335), reaches the catch-all, and arrives on the output Master Channel as a bare Data Entry,
applied downstream to whatever parameter is selected there. §4.2 is unchanged and still requires an
invalid MCM to be ignored "in its entirety: neither its selector nor its Data Entry is relayed".

### N4. MIDI Mode messages 124–127 not discarded (§3.6, §5.8.6)

§3.6 makes the Tuner fixed-mode on both sides and requires that the **MIDI Mode
messages 124–127** (Omni Off, Omni On, Mono On, Poly On) be **discarded in both input modes** —
neither forwarded nor emitted. This is the sole exception to Master Channel forwarding (§3.4,
recorded in §3.5's table) and to Non-MPE redirection (§3.3 item 4); §5.8.6 records it as a
deliberate departure from transparency, justified because a Mode 4 (monophonic) Member Channel
downstream would turn every shared allocation into an unintended note drop.

The implementation has no notion of Channel Mode messages at all. `JavaMidiConverters` maps every
`0xB0` status message to `CcScMidiMessage` regardless of controller number
(`JavaMidiConverters.scala:208`), and `ScMidiCc` defines constants only for 120, 121 and 123 — none
for 122 or 124–127. Controller numbers 124–127 therefore fall through `processCc`'s catch-all
(lines 350-351) and are **redirected to the zone's Master Channel** like any other CC. A Mono On
reaching the output Zone's Master Channel is precisely the outcome §5.8.6 forecloses.

**The 120–123 half is aligned** — see Section 5. Note the asymmetry: the paper distinguishes
124–127 from 120–123, while the code's catch-all treats all eight identically, so the fix needs an
explicit controller-number branch rather than a change of default.

### I2. Out-of-zone notes and controls in MPE mode (§3.7, §5 intro)

**Paper** §3.7: in MPE Input Mode a channel that is neither the Master
Channel nor a Member Channel of an enabled Zone lies outside the Zone structure, and every Channel
Voice and Channel Mode message received on it is discarded — notes "neither forwarded nor
allocated" (§5 intro), channel-global controls and Zone-level messages neither redirected nor
passed through. MCMs are the sole exception.

**Implementation**: `getAllocatorForInput` (`MpeTuner.scala:722-738`) falls through both zone tests
to `lowerAllocator.orElse(upperAllocator)` (line 735), so an out-of-zone note is allocated into the
first enabled zone and tuned as though it had arrived inside it; `resolveZoneMasterChannel` (lines
630-631) returns `Some(inputChannel)`, so out-of-zone controls pass through unchanged on their
original channel.

This is also the mechanism C4 depends on: deactivating every Zone leaves every channel out of zone,
so a correct discard here yields the silence §4.1 requires, and one fix serves both.

### I3. Zone-level messages on an input Member Channel in MPE mode (§3.5, §2.6)

**Paper** §3.5: in MPE Input Mode a Zone-level message arriving on an input *Member*
Channel is **discarded**, following the receiver obligation of [1, §2.3.1] ("it must ignore it") and,
for Program Change, [1, §2.3.3] ("a receiver operating in Mode 3 should ignore Program Change
messages received on Member Channels"). Three exemptions: the three control dimensions (per-note
under §7.2), Pitch Bend Sensitivity (note-level as well as Zone-level per [1, Table 1], handled by
§4.3), and an MCM
on Channel 1 or 16 (§4.2). The MIDI Mode messages are discarded on every channel (§3.6, N4).

**Implementation**: generic CCs reach `processCc`'s catch-all and are redirected to the zone's Master
Channel (`MpeTuner.scala:350-351`), as is Program Change (`processShortMessage`, lines 181-185).
`resolveZoneMasterChannel` maps any in-zone channel — Master and Member alike — to
`zone.masterChannel`, discarding the distinction in its `case Some((zone, _))` wildcard (lines
628-629). So a Damper Pedal sent on input Member Channel 4 arrives on the output Master Channel and
sustains the whole Zone, precisely the outcome §3.5 cites as its motive.

The information the fix needs is already there: `findZoneForChannel` returns
`Some((zone, isMaster))` (line 628) and only the wildcard throws `isMaster` away. The Non-MPE path is
unaffected — `resolveZoneMasterChannel` branches on `inputMode` first (line 625), and §3.3 item 4's
redirection stands, for the reason §3.5 gives: a non-MPE input has no Member Channels.

### I1. Active Tuning reset to Standard on incoming MCM

`resetState()` sets `_tuning = Tuning.Standard` (`MpeTuner.scala:143`) and is called from
`processMcm` (line 381). So an in-band Zone reconfiguration silently discards the performer's
active Tuning; subsequent notes play in 12-EDO until the next `tune()` call. Nothing in §4.2 or §8
sanctions this. Resetting the Tuning is appropriate in `reset()` (full re-initialization, line 112),
not in the MCM path.

### B1. Dropped notes leave stale entries in `channelNoteMap`

When the allocator drops notes (channel exhaustion or high bend), `MpeTuner` emits their Note Offs
(`emitDroppedNoteOffs`, lines 653-659) but **never removes them from `channelNoteMap`** — the
method body writes only to `buffer`. Tracking added at `trackNote` (line 230) is undone only in
`untrackNote` on a sender Note Off, or wholesale in `resetState`. Consequences:

- The sender's eventual Note Off for a dropped note finds the stale entry, so `untrackNote` returns
  `Some(outChannel)` and a **duplicate** downstream Note Off is emitted (line 266). The
  accompanying `release` is a no-op, because `ChannelState.removeNote` guards on
  `_notes.remove(midiNote).isDefined` (`MpeChannelAllocator.scala:190`).
- In MPE mode, `outputChannelsFor(inputChannel)` (lines 543-545) still lists the output channel of
  the dropped note, so expressive Pitch Bend / CC #74 / CP fan-out targets a channel that may by
  now be occupied by a **different pitch class** — directly violating the intonation guarantees
  both the paper (§5.2) and the code aim for.

**The paper now prescribes the fix.** §5.1 and §6: dropping a note
clears its reference count *and* its channel binding, so a Note Off the performer sends for it
afterwards is **discarded**. Forwarding it "would exceed the one-Note-Off-per-Note-On obligation
rather than satisfy it" (§6). The first consequence above is thus also a direct conflict with §5.1
rule 4.

Note that `channelNoteMap` is moving `MpeChannelAllocator`, but it must not have this bug after the move.

### N2. Duplicate Note On re-runs allocation (§5.1, §7.6)

`processMemberNoteOn` calls `alloc.allocate(...)` unconditionally (line 221) — there is no check
for an already-active note, because there are no reference counts (N1). §5.1 and §7.6.1 require the
opposite: a Note On that raises the count above 1 must bypass allocation entirely and be forwarded
on the channel already bound to the identity. Tracing the two cases of §7.6 through the code gives
two distinct failures.

**Case 1 — same input channel (§7.6.1): a leaked channel and a hanging note.** With E4 active on
input channel 2 → output channel 2, a second Note On for E4 on input channel 2 re-enters
`allocate`. Step 1 is skipped (`pitchClassInPCG` is true, line 253); Step 2 fires whenever the
Expression Group has capacity (line 260) and returns a **different** output channel, say 3. Then:

- `trackNote(2, E4, 3)` (line 230) **overwrites** `channelNoteMap(2)(E4)`, destroying the binding
  to output channel 2.
- The first Note Off routes to channel 3, releases it, and removes the map entry; the second Note
  Off finds nothing and is dropped at lines 269-270.
- Output channel 2 still holds E4 in its `ChannelState._notes` and is never released: the channel
  stays **occupied and unusable** until the next `reset()`/MCM, and its Note On is never matched
  downstream — a **hanging note**.
- Two Note Ons were forwarded but only one Note Off, violating the very MIDI 1.0 obligation
  [2, p. A-4] that §5.1 cites the counting to satisfy.

If Step 2 has no capacity and Step 3 shares the same channel instead, there is no leak, but
`ChannelState.addNote`'s `_notes(midiNote) = expression` (line 178) **replaces** the existing
entry, resetting the note's accumulated expression — contrary to §7.6.1's "the note keeps whatever
pressure it has accumulated".

**Case 2 — different input channels (§7.6.2): unrepresentable, and a premature free.** Two
identities `(2, E4)` and `(3, E4)` that Step 3 places on one output channel collide in
`ChannelState._notes`, which is keyed by `MidiNote` alone (line 104). The second `addNote`
overwrites the first's expression and `notes.size` stays 1. Consequences:

- `computeOutputPitchBend` averages over `alloc.activeNotes(channel)` (line 637) and so sees **one**
  term where §7.6.2 requires two — the emitted bend is the second note's alone, not the average.
- `dropExistingNotesForHighBend` reads `state.expressionFor(n)` for the pre-add snapshot (line 437),
  which after the overwrite returns the *new* note's bend — the high-bend test consults the wrong
  note.
- The first Note Off removes the single entry, so the channel reports **unoccupied** while a note is
  still sounding on it. A subsequent allocation can then place a **different pitch class** on that
  channel — a pitch-class invariant violation (§5.2).

`channelNoteMap` handles Case 2 correctly (it keys on input channel), so the two forwarded Note Offs
do reconcile in count; the damage is confined to the allocator.

Both cases resolve the same way: give `ChannelState` identity-keyed notes and reference counts
(N1), then gate `allocate` on the 0 → 1 transition.

## 4. Testing

* A few of the tests required for the changes described here were already added as red and are currently ignored. Before you start coding, you may start my understanding them and activating them (replacing `ignore` with `it`).
* For new test cases, please be mindful about where they should be placed. The tests are grouped in categories via `behavior of` sections or additional subsections delimited by special comments. For more details read the ScalaDoc of the test classes.
