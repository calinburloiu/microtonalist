# MPE Tuner MIDI Message Routing and Filtering Conformance (Prompt)

- **Date**: 2026-08-03
- **Issue**: [#250](https://github.com/calinburloiu/microtonalist/issues/250) — "Make MPE Tuner MIDI message routing and
  filtering conform to the paper"
- **Base commit**: `9feda4adf8be21da2db7a354b873bad576abbea4`
- **Source of truth**: the MPE Tuner paper,
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../../../docs/architecture/tuner/mpe-tuner-paper.md)
- **MPE Specification**: [`docs/architecture/tuner/mpe-spec.md`](../../../docs/architecture/tuner/mpe-spec.md)
- **Cycle-1 prompt this one derives from**: [`code-prompt.md`](code-prompt.md)

Your task is to make the MIDI message routing and filtering of the MPE Tuner
(`tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`) conform to the MPE Tuner paper, which
is the source of truth. For reference, the MPE Specification on which the MPE Tuner is based is in `mpe-spec.md`.

Use /superpowers:brainstorming . Sections, facts and bullets are numbered / identified to facilitate discussions. We are
working on issue #250, so write superpowers' artifacts to `issues/00154-mpe-tuner-poly-expr/code/` — the same directory
this prompt lives in, beside the cycle-1 artifacts.

## Scope

Issue #154 ran in two cycles. **Cycle 1 is merged** (PR #251, commit `9feda4a`): it implemented the polyphonic
expression model — per-note Expression Values, Note Identity with reference counting, per-channel aggregation, the
divergence rule and the emission ordering. This prompt is **cycle 2**, issue #250, and covers what cycle 1 deliberately
left out:

- Zone-level messages arriving on an input Member Channel must be discarded (**I3**).
- Messages received on a channel outside every enabled Zone must be discarded, including the degenerate case in which
  no Zone is enabled at all (**I2**, **C4**).
- Master Channel CC #74 and Channel Pressure must be forwarded as Zone-level controls (**C5**).
- Uninterpreted RPN/NRPN traffic must be routed per the paper, and an invalid MCM ignored in its entirety (**C6**).
- A forwarded Pitch Bend Sensitivity sequence must be closed with an RPN Null (**P7**).
- A Zone reconfiguration must reset state only for the channels entering or leaving MPE control, and must not discard
  the active Tuning (**C3**, **I1**).
- The MIDI Mode messages 124–127 must be discarded in both input modes (**N4**).

Do **not** revisit the Expression Value model: averaging, fan-out, retention on empty channels, reference counting and
the Note On / Note Off emission ordering are implemented and tested. Touch them only where a gap below genuinely depends
on them.

The in-code anchor for this work is the `TODO #250` at `MpeTuner.scala:644-651`, above `resolveZoneMasterChannel`, which
enumerates the same list. There are three further `TODO #250` markers, at `MpeTuner.scala:250` (the `case None` branch
of `processMemberNoteOn`), `MpeTuner.scala:372-373` (Master Channel CC #74) and `MpeTuner.scala:583-587` (Master Channel
Channel Pressure). All four must be gone when this work is done. No `TODO #154` remains anywhere in the code base.

Two further TODOs sit inside methods you will be editing, and both are **out of scope for #250** — neither absorb them
into this work nor delete them: `TODO #253` at `MpeTuner.scala:261-263`, immediately above `inputExpressionOf` (a note
seeded from a raw Pitch Bend that predates a member PBS change), and `TODO #254` at `MpeTuner.scala:566-568`, inside
`stopAllNotes` — the very method **C3** asks you to rescope (one Note Off per active Master Channel note rather than one
per forwarded Note On, because the tracker holds a set with no reference count). Rescoping `stopAllNotes` must leave
`TODO #254` standing and still accurate.

## A note on the identifiers used here

This document is a **filtered derivative** of the cycle-1 prompt [`code-prompt.md`](code-prompt.md). Every identifier is
inherited from it verbatim and deliberately **not** renumbered: the message-handling bullets keep their `§2.2(d)`,
`§2.2(e)`, `§2.2(f)` labels under the original `## 2. Implementation details` / `### 2.2. Changes in MpeTuner`
headings, and the gaps keep their `P7`, `C3`, `C4`, `C5`, `C6`, `N4`, `I2`, `I3`, `I1` identifiers, in the cycle-1
prompt's order. The holes in both sequences — the missing `## 1.`, `### 2.1.`, `§2.2(a)–(c)`, and gaps P1–P5, N1, N2,
N5, B1, C1, C2 — are exactly the items cycle 1 closed. These labels are the shared vocabulary of #250's body, the paper
and the gap report, so renumbering would silently break all three mappings.

Every line number and every statement about the current state of the code in this document was re-derived against the
base commit above. The cycle-1 prompt's line numbers predate cycle 1's rewrite of `MpeTuner.scala` and
`MpeChannelAllocator.scala` and must not be trusted.

## 2. Implementation details

### 2.2. Changes in `MpeTuner`

* (d) How are various *other* MIDI messages handled in MPE Input Mode:
    - when received on an input Member Channel:
        - Pitch Bend Sensitivity: accepted
        - MCM: accepted on channel 1 and 16 (1-based)
        - Polyphonic Key Pressure, Program Change, Bank Select, Reset All Controllers (CC #121), MIDI Mode Messages
          124–127, any other CC Message: blocked
    - when received on an input Master Channel:
        - Pitch Bend Sensitivity, Polyphonic Key Pressure, Program Change, Bank Select, Reset All Controllers
          (CC #121), any other CC Message: accepted
        - MCM: accepted on channel 1 and 16 (1-based)
        - MIDI Mode Messages 124–127: blocked
    - This restates paper §3.5's table for the message classes at issue here. At the base commit the **Master Channel**
      column is satisfied — Program Change, Bank Select, Reset All Controllers and any other CC reach `processCc`'s
      catch-all (`MpeTuner.scala:383-384`) or `processShortMessage`'s Program Change branch (lines 190-194), both of
      which route through `resolveZoneMasterChannel`, and for a Master Channel that resolves to the same channel
      (line 657); Polyphonic Key Pressure is forwarded as-is (`processPolyPressure`, lines 608-609); PBS is consumed
      and re-emitted on the same channel (`processPbs`, lines 449-456), which is §3.5's Zone-level verdict for it
      (*"Accepted; consumed and re-emitted on the output Master Channel"*) — though the re-emission itself is short an
      RPN Null, see **P7**. Two Master Channel classes are **not** satisfied: CC #74 and Channel Pressure (**C5**) and
      the MIDI Mode messages (**N4**).
    - The **Member Channel** column is where the work is. Only Polyphonic Key Pressure is blocked today
      (`processPolyPressure`, lines 604-610, emits nothing off the Master Channel), and PBS and the MCM are correctly
      accepted (lines 364-367). Everything else in the "blocked" list is instead redirected to the Zone's Master
      Channel — see **I3** for the general case and **N4** for the MIDI Mode messages.

* (e) How are various *other* MIDI messages handled in Non-MPE Input Mode:
    - Pitch Bend Sensitivity, Program Change, Bank Select, any other CC Message: forwarded to output Master Channel
    - MCM: accepted on channel 1 and 16 (1-based)
    - Reset All Controllers (CC #121), All Sound Off (Channel Mode message 120) and All Notes Off (Channel Mode message
      123): do not clear `MpeTuner`'s or `ScMidiChannelStateTracker`'s internal state and are forwarded to output Master
      Channel.
    - Polyphonic Key Pressure: converted to Channel Pressure Expression Value and used on output Member Channels
    - MIDI Mode Messages 124–127: blocked
    - This restates paper §3.3 item 4 (the redirection lines, and the MIDI Mode messages as its sole exception), §4.2
      (the MCM's validity by channel number), §3.3 item 1 together with §7.3 (the Polyphonic Key Pressure conversion and
      its averaging), and §3.6 (the Channel Mode messages and the "does not clear its own state" clause). At the base
      commit every line here holds **except the last**. The forwarding lines are served by `processCc`'s catch-all and
      `forwardOnZoneMasterChannel`
      (`MpeTuner.scala:383-384`, 628-633) via `routingZoneForNonMpeInput` (lines 485-489). The "do not clear internal
      state" clause holds for both halves: `MpeTuner` has no branch for CC #120/#121/#123, and the tracker is
      constructed as `ScMidiChannelStateTracker()` (line 81), whose `shallRespondToResetMessages` parameter defaults to
      `false` (`sc-midi/.../ScMidiChannelStateTracker.scala:47-50`), so `handleChannelModeCc` (lines 303-314) is inert.
      The Polyphonic Key Pressure line was implemented by cycle 1 (`processPolyPressure`, lines 611-619). The MIDI Mode
      line is **N4**.

* (f) Any RPN / NRPN message sequence must be forwarded atomically to avoid issues with interleaved streams. Those
  interleaved streams appear naturally in Non-MPE Mode when messages can come from multiple channels and RPN / NRPN
  sequences must be forwarded to some specific channels.
    - Setting the RPN / NRPN selector numbers requires two separate messages. Those are not forwarded immediately, but
      instead are stored and change the internal state of `ScMidiChannelStateTracker`.
    - Only when a value-changing message comes (Data Entry, Data Increment/Decrement), the whole sequence is emitted,
      including the full selector. The selector is always emitted before such a message, even if it's verbose. Data
      Entry has separate messages for MSB and LSB, so the full sequence including the selector is emitted for each of
      those. That's fine!
    - **Where this comes from.** The paper never uses the words "atomic" or "interleaved" — verified by search over
      `mpe-tuner-paper.md` and `mpe-spec.md`. This bullet is a design requirement the cycle-1 prompt's author added,
      generalizing to *uninterpreted* parameters the shape §4's preamble already imposes on the two the Tuner
      interprets ("it *consumes* the sender's selector and Data Entry messages and re-emits complete sequences of its
      own"). Treat it as binding, and raise it with the author if you believe it conflicts with the paper.
    - **Status at the base commit: unimplemented for uninterpreted parameters.** `processCc` suppresses the sender's
      selector only when the tracked selector is the MCM or PBS RPN (`MpeTuner.scala:357`, an empty case body). Every
      other selector is forwarded the moment it arrives — RPN selectors (CC #101/#100) explicitly at lines 358-363, NRPN
      selectors (CC #99/#98) through the catch-all at lines 383-384 — and the value-changing messages that should have
      triggered the sequence, Data Entry (CC #6/#38, when neither the MCM nor the PBS RPN is selected) and Data
      Increment/Decrement (CC #96/#97), are forwarded separately through that same catch-all. See **C6**, which is the
      paper-conformance half of the same code path: it asks where these messages must *go*, this bullet asks how they
      must be *grouped*, and the two verdicts differ in Non-MPE Input Mode.

## 3. Implementation gaps

These gaps are derived from the report (from `issues/00154-mpe-tuner-poly-expr/paper/paper-impl-gap-report.md`) that
tracks the differences, conflicts and inconsistencies between the current implementation and the paper. **You should not
read that report file!** Its relevant content is copied here and commented. That's why the content here focuses more on
the current state of the implementation and why it is not consistent with the paper. References in this section are to
the paper, except where a file is named explicitly.

Every gap cycle 1 closed has been removed from this section. What remains is the nine gaps deferred to cycle 2, in the
cycle-1 prompt's order.

### P7. Forwarded Pitch Bend Sensitivity sequence not closed with an RPN Null (§4 preamble)

§4's preamble gives one shape to every RPN sequence the Tuner re-emits: "selector, Data Entry, and a closing RPN Null
(7F 7F) that protects the parameter from a later stray Data Entry". The Tuner does not relay the sender's messages
verbatim; it consumes them and emits complete sequences of its own.

Three sites in `MpeTuner` emit such a sequence, and only one of them departs from the rule:

- `mcmMessages` (`MpeTuner.scala:754-763`) emits the RPN 00 06 selector, the Data Entry MSB, then the RPN Null
  (lines 760-761). Conformant.
- `pitchBendSensitivityMessages` (`MpeTuner.scala:765-781`) delegates to `PitchBendSensitivityMessages.create`
  (`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/PitchBendSensitivity.scala:71-81`), which closes with the Null
  at lines 78-79. Conformant.
- `applyPbsUpdate` (`MpeTuner.scala:504-527`) — the path a *received* PBS Data Entry takes, from `processPbs` in both
  input modes — emits the PBS selector and the Data Entry (lines 518-520) and stops. **Not conformant.**

The fix is to append the two Null CCs in `applyPbsUpdate`. The cycle-1 prompt left open the alternative of amending the
paper to restore its older distinction between sequences the Tuner *originates* and a Data Entry it *forwards*; the
paper as committed at the base commit states the single rule and is the source of truth, so prefer the code-side fix and
raise it with the author if you disagree.

**Out of scope, noted only because you will be reading this code:** `applyPbsUpdate` emits the selector RPN MSB before
the RPN LSB (lines 518-519), whereas `mcmMessages` (lines 757-758) and `PitchBendSensitivityMessages.create`
(`PitchBendSensitivity.scala:73-74`) emit LSB before MSB. Neither the paper nor the gap report requires either order,
and both are valid on the wire. Do **not** change it under #250; raise it as its own issue if it bothers you.

### C3. Scope of state reset on MCM reconfiguration (§4.2)

Paper: a reconfiguration "resets the Tuner's state for every channel entering or leaving MPE control" — active notes on
the affected channels dropped, group assignments cleared, retained Expression Values and remembered input-channel
control values returned to their defaults, Pitch Bend Sensitivity back to the specification's defaults. And then:
"Channels of a Zone untouched by the reconfiguration keep their notes and state."

Implementation (`processMcm`, `MpeTuner.scala:394-432`) resets everything, whichever Zone the MCM addresses:

- `stopAllNotes(buffer)` (line 408) iterates **both** allocators' `activeAllocations` (lines 557-563) and, in MPE Input
  Mode, both enabled Zones' Master Channel notes held by the tracker (lines 565-575). Notes in the untouched Zone are
  dropped.
- `resetState()` (line 414, body at lines 152-158) calls `tracker.reset()` (line 154), which replaces the state of all
  sixteen channels (`ScMidiChannelStateTracker.reset`, `ScMidiChannelStateTracker.scala:92-96`), and recreates **both**
  allocators (lines 156-157), discarding the untouched Zone's channel states, group assignments and retained Expression
  Values.
- It also resets the active Tuning — that is **I1**, below.

Two things are already right and should not be disturbed. The *ordering* is conformant: `stopAllNotes` runs before the
MCM is emitted (line 420), and for Member Channel notes it emits one Note Off per forwarded Note On (the
`1 to alloc.referenceCountOf(noteIdentity)` loop at line 560), which is §5.1's obligation. And the *PBS* half is
conformant: the addressed Zone is rebuilt as `MpeZone(zoneType, memberCount)` (lines 397-400), taking
`MpeZone.DefaultMasterPitchBendSensitivity` / `DefaultMemberPitchBendSensitivity` (`MpeZone.scala:103-104, 115-117`),
while `MpeZones.update` preserves the Pitch Bend Sensitivity of a Zone it shrinks by overlap resolution
(`MpeZone.scala:142-157`).

So the gap is confined to *scope*: which channels lose their notes, their tracker state and their allocator state. Note
that "entering or leaving MPE control" is not the same as "belonging to the addressed Zone" — overlap resolution
(`MpeZones.update`) can shrink the *other* Zone, and the channels it gives up are leaving MPE control and must be reset
too.

### C4. Behavior when all Zones are deactivated (§4.1, §3.7)

Paper §4.1: with no Zone enabled "every channel lies outside the Zone structure, so every Channel Voice and Channel Mode
message the Tuner receives is discarded under Section 3.7 — not only notes"; the only messages the Tuner emits are the
configuration messages of §4.2, and the only input it still acts upon is a valid MCM.

With both Zones disabled, `createAllocator` (`MpeTuner.scala:783-785`) returns `None` for each, so `allocatorFor`
(lines 787-803) returns `None` in **both** input modes — in Non-MPE Input Mode from
`lowerAllocator.orElse(upperAllocator)` at line 790, in MPE Input Mode from the same expression at line 800. From
there:

- **A hanging note — the user-visible failure mode.** `processMemberNoteOn`'s `case None` branch (lines 249-253,
  carrying the `TODO #250` at line 250) **forwards the Note On** unchanged on its input channel:
  `buffer += NoteOnScMidiMessage(inputChannel, midiNote, velocity).asJava`. The matching Note Off is then **swallowed**:
  `processNoteOff` (lines 270-309) forwards a Master Channel Note Off first (lines 275-276) and wraps everything else in
  `allocatorFor(inputChannel).foreach { alloc => … }` (line 278), which does nothing when the option is empty. With no
  Zone enabled `isMasterChannel` is false for every channel (lines 805-808), so every Note Off takes that second path
  and is dropped. One Note On out, no Note Off ever — the note never stops sounding. This is
  **pre-existing, not a cycle-1 regression**: before cycle 1 the same `case None` forwarded the Note On without calling
  `trackNote`, so the later `untrackNote` returned `None` and the Note Off was logged and dropped (verified against
  `MpeTuner.scala` at commit `4bc45c9`, lines 251-253 and 262-272). Whichever way this gap is fixed, the fix must not
  leave a forwarded Note On without a Note Off; the paper's answer is that neither message should be emitted at all.
- **Controls pass through too.** `resolveZoneMasterChannel` (lines 652-661) returns `Some(inputChannel)` for a channel
  in no Zone (line 658), so in MPE Input Mode generic CCs (catch-all, lines 383-384), Program Change (lines 190-194)
  and uninterpreted RPN selectors (line 362) are all emitted unchanged on their original channel. In Non-MPE Input Mode
  these are correctly dropped instead, because `routingZoneForNonMpeInput` (lines 485-489) yields `None`.
- **Some paths are accidentally silent.** In MPE Input Mode, Pitch Bend (lines 324-329), CC #74 (lines 376-378) and
  Channel Pressure (lines 588-590) all go through `allocatorFor(...).foreach`, so they emit nothing; Polyphonic Key
  Pressure is dropped because `isMasterChannel` (lines 805-808) requires an enabled Zone. Do not mistake this for
  conformance — it is the same missing guard failing to fire, and a correct fix should make the discard explicit.
- The MCM path still works and must keep working: line 364's guard is `isMcmRpn && (inputChannel == 0 ||
  inputChannel == 15)` and does not consult the Zone configuration, which is what allows a Zone to be re-activated
  in band (§4.1, §3.7).

This is the degenerate case of **I2**, and the same fix resolves both.

### C5. Master Channel CC #74 and Channel Pressure forwarding in MPE mode (§3.4)

Paper §3.4: Master Channel Pitch Bend and Zone-level messages are "forwarded on the Master Channel without
modification". §3.5's table settles the two dimensions at issue explicitly: its first row — "Pitch Bend, Channel
Pressure, CC #74" — reads *Zone level: Forwarded unmodified (Section 3.4)*. On a Master Channel these are Zone-level
controls, not per-note ones. The only Master Channel messages exempt from plain forwarding are the two parameters the
Tuner interprets, which it consumes and re-emits (PBS RPN 00 00 and the MCM RPN 00 06, §4), and the MIDI Mode messages
124–127, which it discards (§3.6, see **N4**). CC #74 and Channel Pressure are in neither class.

Implementation: only Pitch Bend and Polyphonic Key Pressure special-case the Master Channel — `processPitchBend`
(`MpeTuner.scala:317-319`) and `processPolyPressure` (lines 608-609) forward them as-is. CC #74 and Channel Pressure do
not. In MPE Input Mode both are handed straight to the allocator:

- CC #74: `processCc`'s `case ScMidiCc.MpeSlide` (lines 374-381) calls `alloc.updateSlide(inputChannel, ccValue)`.
- Channel Pressure: `processChannelPressure` (lines 578-590) calls `alloc.updatePressure(msg.channel, msg.value)`.

Both allocator methods (`MpeChannelAllocator.scala:558-560` and `583-585`) resolve their targets through
`identitiesOn(inputChannel)` (lines 784-785), which filters `noteChannels` — the allocator's identity → output-channel
bindings. A Master Channel note never enters that map: `processNoteOn` forwards it before `processMemberNoteOn` is
reached (lines 203-208). So `identitiesOn` returns an empty sequence, `updateExpressionValues` (lines 799-825) produces
an empty `MpeExpressionUpdateResult`, and **nothing at all is emitted** for a Master Channel CC #74 or Channel Pressure.

Both sites already carry a `TODO #250` saying so (lines 372-373 and 583-587). The second records that this is a
**regression** relative to the pre-cycle-1 code, where a Master Channel note was recorded in the Tuner's own note map
and its Channel Pressure was forwarded on the Master Channel for as long as such a note sounded — and note that even
that behavior was conditional on a note sounding, whereas §3.4 requires unconditional forwarding.

(The cycle-1 prompt attributed this requirement to "the MPE spec §2.5–2.6, quoted in paper §2.5/2.6". That citation
does not land: paper §2.5 is "Note On Setup and Message Ordering" and §2.6 "Zone-Level Messages", neither of which
classifies Master Channel CC #74 or Channel Pressure. §3.5's table row and §3.4 are the citations to use.)

### C6. Uninterpreted RPN/NRPN traffic on an input Member Channel (§4 preamble, §3.5, §4.2)

**Aligned — Non-MPE Input Mode.** Uninterpreted RPN selectors (CC #101/#100) match the second selector case
(`processCc`, `MpeTuner.scala:358-363`); NRPN selectors (CC #99/#98), Data Entry (CC #6/#38) and Data
Increment/Decrement (CC #96/#97) match no case and fall to the catch-all (lines 383-384). In Non-MPE mode both routes
call `forwardOnZoneMasterChannel`, so the whole sequence arrives on the output Master Channel — which is what §3.3
item 4 requires. **Aligned here means aligned as to *destination* only**: every message of the sequence reaches the
right channel. Whether they are *grouped* into a re-emitted sequence rather than relayed one by one is §2.2(f)'s
separate, design-level question, and by that criterion this same code path is unimplemented. Do not read this paragraph
as saying the Non-MPE RPN/NRPN path needs no work. The guards are sound: `ScMidiChannelStateTracker` models NRPN
selection as a distinct
`RpnSelector.Nrpn` case (`ScMidiChannelStateTracker.scala:449-456`), so neither `isMcmRpn` nor `isPbsRpn`
(`MpeTuner.scala:345-348`) fires spuriously on NRPN traffic.

**Aligned — MPE Input Mode on an input Master Channel.** The two routes converge there: line 362 emits the message
unchanged on its own channel, and the catch-all resolves through `resolveZoneMasterChannel`'s
`case Some((zone, _)) => Some(zone.masterChannel)` (line 657), which for a Master Channel *is* that same channel. Both
therefore satisfy §3.5's "forwarded unmodified on the same Master Channel".

**Still a conflict — MPE Input Mode on an input Member Channel.** §3.5's table gives "All other RPN and all NRPN
messages" a note-level verdict of *Discarded*, and none of it is. Uninterpreted RPN selectors are emitted unchanged on
the Member Channel (line 362) while NRPN selectors, Data Entry and Data Increment/Decrement are redirected to the
Zone's Master Channel (catch-all), so the sequence is both wrongly retained and split across two channels. The root
cause is **I3**'s — `resolveZoneMasterChannel` discarding `isMaster` — but fixing I3 does not by itself fix line 362,
which needs a Member Channel case of its own.

**Still a conflict — invalid MCM.** The suppression case (line 357) matches whenever the tracked selector is RPN 00 06,
on **any** input channel, so the selector is swallowed even off Channels 1 and 16; the following Data Entry MSB then
fails the `inputChannel == 0 || inputChannel == 15` guard (line 364), falls past the PBS case (line 366) to the
catch-all, and arrives on the output Master Channel as a bare Data Entry, applied downstream to whatever parameter is
selected there. §4.2 requires an invalid MCM to be ignored "in its entirety: neither its selector nor its Data Entry is
relayed".

Note that `processShortMessage` feeds every message to `tracker.send(scMessage)` (line 177) *before* dispatching it, so
by the time `processCc` reads `tracker.rpnSelector(inputChannel)` the selector already includes the CC being processed.
Any redesign of the RPN routing has to keep that in mind.

### N4. MIDI Mode messages 124–127 not discarded (§3.6, §5.8.6)

§3.6 makes the Tuner fixed-mode on both sides and requires that the **MIDI Mode messages 124–127** (Omni Off, Omni On,
Mono On, Poly On) be **discarded in both input modes** — neither forwarded nor emitted. This is the sole exception to
Master Channel forwarding (§3.4, recorded in §3.5's table) and to Non-MPE redirection (§3.3 item 4); §5.8.6 records it
as a deliberate departure from transparency, justified because a Mode 4 (monophonic) Member Channel downstream would
turn every shared allocation into an unintended note drop.

The implementation has no notion of Channel Mode messages at all. `JavaMidiConverters` maps every `0xB0` status message
to `CcScMidiMessage` regardless of controller number
(`sc-midi/src/main/scala/org/calinburloiu/music/scmidi/message/JavaMidiConverters.scala:207-209`), and `ScMidiCc`
defines constants only for 120, 121 and 123 (`ScMidiCc.scala:39-44`) — none for 122 or 124–127. Controller numbers
124–127 therefore fall through `processCc`'s catch-all (`MpeTuner.scala:383-384`) and are **redirected to the Zone's
Master Channel** like any other CC. A Mono On reaching the output Zone's Master Channel is precisely the outcome §5.8.6
forecloses.

**The 120–123 half is aligned** — see §2.2(e) above, which verifies both the forwarding and the "does not clear internal
state" clause. Note the asymmetry: the paper distinguishes 124–127 from 120–123, while the code's catch-all treats all
eight identically, so the fix needs an explicit controller-number branch rather than a change of default.

### I2. Out-of-zone notes and controls in MPE mode (§3.7, §5 intro)

**Paper** §3.7: in MPE Input Mode a channel that is neither the Master Channel nor a Member Channel of an enabled Zone
lies outside the Zone structure, and every Channel Voice and Channel Mode message received on it is discarded — notes
"neither forwarded nor allocated" (§5 intro), channel-global controls and Zone-level messages neither redirected nor
passed through. MCMs are the sole exception.

**Implementation**: `allocatorFor` (`MpeTuner.scala:787-803`; the method the cycle-1 prompt knew as
`getAllocatorForInput`, which no longer exists) falls through both Zone tests to `lowerAllocator.orElse(upperAllocator)`
(line 800), so an out-of-zone note is allocated into the first enabled Zone and tuned as though it had arrived inside
it. For controls, `resolveZoneMasterChannel` (lines 652-661) returns `Some(inputChannel)` at line 658, so out-of-zone
CCs and Program Change pass through unchanged on their original channel. A received PBS Data Entry on an out-of-zone
channel takes a third route to the same outcome: `processPbs`'s `case None` (lines 457-458) re-emits the CC on the
original channel.

This is also the mechanism **C4** depends on: deactivating every Zone leaves every channel out of Zone, so a correct
discard here yields the silence §4.1 requires, and one fix serves both. Beware the interaction, though: with a Zone
enabled, the `None` case of `allocatorFor` is unreachable (line 800 always yields a `Some`), so the out-of-zone discard
must be decided *before* an allocator is looked up, not inside `processMemberNoteOn`'s `case None`.

### I3. Zone-level messages on an input Member Channel in MPE mode (§3.5, §2.6)

**Paper** §3.5: in MPE Input Mode a Zone-level message arriving on an input *Member* Channel is **discarded**, following
the receiver obligation of [1, §2.3.1] ("it must ignore it") and, for Program Change, [1, §2.3.3] ("a receiver operating
in Mode 3 should ignore Program Change messages received on Member Channels"). Three exemptions: the three control
dimensions (per-note under §7.2), Pitch Bend Sensitivity (note-level as well as Zone-level per [1, Table 1], handled by
§4.3), and an MCM on Channel 1 or 16 (§4.2). The MIDI Mode messages are discarded on every channel (§3.6, **N4**).

**Implementation**: generic CCs reach `processCc`'s catch-all and are redirected to the Zone's Master Channel
(`MpeTuner.scala:383-384`), as is Program Change (`processShortMessage`, lines 190-194). `resolveZoneMasterChannel` maps
any in-Zone channel — Master and Member alike — to `zone.masterChannel`, discarding the distinction in its
`case Some((zone, _))` wildcard (line 657). So a Damper Pedal sent on input Member Channel 4 arrives on the output
Master Channel and sustains the whole Zone, precisely the outcome §3.5 cites as its motive.

The information the fix needs is already there: `findZoneForChannel` (lines 534-536) returns `Some((zone, isMaster))`,
built by `findChannelRole` (lines 538-543), and only the wildcard at line 657 throws `isMaster` away. The Non-MPE path
is unaffected — `resolveZoneMasterChannel` branches on `inputMode` first (line 653), and §3.3 item 4's redirection
stands, for the reason §3.5 gives: a non-MPE input has no Member Channels.

### I1. Active Tuning reset to Standard on incoming MCM

`resetState()` sets `_tuning = Tuning.Standard` (`MpeTuner.scala:153`) and is called from `processMcm` (line 414). So an
in-band Zone reconfiguration silently discards the performer's active Tuning; subsequent notes play in 12-EDO until the
next `tune()` call. Nothing in §4.2 or §8 sanctions this. Resetting the Tuning is appropriate in `reset()` (full
re-initialization, which calls `resetState()` at line 107), not in the MCM path.

## 4. Testing

* **§3.5's table is the oracle for this issue** — the most compact statement of the routing rules, one row per message
  class and one verdict per level, so a test case can be read straight off it. Do not go looking for a worked example:
  §9's six examples cover allocation, tuning changes, Expression Value averaging, note dropping and duplicate Note Ons,
  and **none of them traces a routing or filtering case**. For the rules §3.5's table does not settle, the prose of
  §3.3, §3.4, §3.6, §3.7, §4, §4.1 and §4.2 is the source, as cited per gap above.
* For new test cases, please be mindful about where they should be placed. The tests are grouped in categories via
  `behavior of` sections or additional subgroups delimited by `// ---- <subgroup name> ----` comment lines. Read the
  ScalaDoc of the test classes first — `MpeTunerTest` (`MpeTunerTest.scala:31-55`) documents its eight categories, the
  per-input-mode split of each `behavior of` block, and the ordering rule inside a subgroup. The
  `process() - Zone-level Messages`, `MCM Processing` and `PBS Processing` categories are where most of this work
  belongs.
* There are **no `ignore`d tests left** in the `tuner` or `sc-midi` suites — cycle 1 activated the ones it had staged.
  The cycle-1 prompt's instruction to start by un-ignoring red tests no longer applies; write your own failing tests
  first, per the repository's TDD workflow.
