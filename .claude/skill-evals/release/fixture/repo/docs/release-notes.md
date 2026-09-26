# Release notes

The notes of every Microtonalist release, newest first. Each release is also published on the
[GitHub Releases](https://github.com/calinburloiu/microtonalist/releases) page, with the same notes.

## v1.5.0 (2026-09-22)

This release brings **hot plugging of MIDI devices**. For developers, Java Sound is now confined to the `sc-midi`
module, which opens the way to other MIDI back ends, such as MIDI 2.0 and Android.

### User-facing changes

- **Hot plugging of MIDI devices** ([#131](https://github.com/calinburloiu/microtonalist/issues/131), [#288](https://github.com/calinburloiu/microtonalist/issues/288)). You can connect, disconnect, or turn on or off a MIDI device while the
  app is running, and it just works. A device doesn't need to be connected at startup. When an input device is
  unplugged, the app sends All Notes Off so that no notes are left hanging. Two tracks can now share one device.
- **Bug fixes:**
  - Every message sent to an output device leaked a Java Sound receiver ([#301](https://github.com/calinburloiu/microtonalist/issues/301)).
  - The Monophonic Pitch Bend Tuner failed on System Real-Time messages such as MIDI Clock ([#279](https://github.com/calinburloiu/microtonalist/issues/279)).
  - A device used in both directions, such as the JDK's Real Time Sequencer, was closed from under itself ([#315](https://github.com/calinburloiu/microtonalist/issues/315),
    [#320](https://github.com/calinburloiu/microtonalist/issues/320)).
- **Compatibility:** pedal triggers on controllers 120–127 (Channel Mode messages) are now rejected when loading a
  tracks file ([#285](https://github.com/calinburloiu/microtonalist/issues/285)).

### Developer-facing changes

- **Java Sound is isolated in `sc-midi`'s `javamidi` package** ([#278](https://github.com/calinburloiu/microtonalist/issues/278)). Other modules work only with `sc-midi`'s Scala
  MIDI messages, and `tuner` no longer imports `javax.sound.midi`. `MidiManager` and `MidiDeviceHandle` are traits,
  implemented by `JavaMidiManager` and `JavaMidiDeviceHandle`, and the manager has one API for both directions ([#307](https://github.com/calinburloiu/microtonalist/issues/307)).
- **The message model is ready for MIDI 2.0.** The `Sc` prefix is dropped (`ScMidiMessage` is now `MidiMsg`), and
  messages form a `Midi1Msg` / `Midi2Msg` hierarchy ([#279](https://github.com/calinburloiu/microtonalist/issues/279)). Channel Mode messages have their own types ([#285](https://github.com/calinburloiu/microtonalist/issues/285)).
- **New `MidiTransmitter` family**, with immutable, mutable and concurrent implementations ([#280](https://github.com/calinburloiu/microtonalist/issues/280)).
- **The MIDI device lifecycle is documented** in `docs/architecture/midi-device-lifecycle.md`, and *connected* /
  *disconnected* is renamed to *available* / *unavailable* throughout ([#318](https://github.com/calinburloiu/microtonalist/issues/318)).
- **Tests and coverage.** `sc-midi` is now thoroughly tested ([#177](https://github.com/calinburloiu/microtonalist/issues/177)), and every test suite uses `AnyWordSpec` ([#299](https://github.com/calinburloiu/microtonalist/issues/299)).
  Coverage, statement/branch, v1.4.0 → v1.5.0:

  | Module | v1.4.0 | v1.5.0 |
  |---|---|---|
  | `sc-midi` | 71.28% / 56.04% | 97.62% / 94.69% |
  | `tuner` | 84.70% / 83.14% | 88.41% / 86.31% |
  | All modules | 76.29% / 71.24% | 83.76% / 80.76% |

### Known issues

- When a device reconnects, its instrument plays in 12-EDO until the next tuning change ([#305](https://github.com/calinburloiu/microtonalist/issues/305)).
- A track fed by another track doesn't react when a device is plugged in or unplugged ([#316](https://github.com/calinburloiu/microtonalist/issues/316)).

The remaining issues of the **sc-midi** milestone were deliberately postponed. The next releases focus on the app's
overall architecture and on watching input files for changes, to refresh automatically:

- The app learns that a device failed only when the operating system reports it, and a device that failed to open is
  not retried ([#302](https://github.com/calinburloiu/microtonalist/issues/302)).
- Two identical MIDI devices connected at once collapse into one ([#306](https://github.com/calinburloiu/microtonalist/issues/306)).
- The MIDI plumbing is still to be restructured into a traversable flow graph ([#310](https://github.com/calinburloiu/microtonalist/issues/310)).

## v1.4.0 (2026-08-18)

This release is about the **MPE Tuner**: its MIDI message routing and filtering now conform to the
[MPE Tuner paper](https://github.com/calinburloiu/microtonalist/blob/v1.4.0/docs/architecture/tuner/mpe-tuner-paper.md),
and the three MPE limitations published as known issues with v1.3.0 are all closed. Those gaps were known at v1.3.0 and
considered minor; closing them removes several ways a receiving instrument could be left with a hanging note, an
out-of-tune note, or a silently discarded Tuning.

### Routing and filtering conformance ([#250](https://github.com/calinburloiu/microtonalist/issues/250))

- **Messages are routed by explicit channel role** ([#260](https://github.com/calinburloiu/microtonalist/issues/260)). A channel outside every enabled Zone discards everything but
  a valid MPE Configuration Message — including when no Zone is enabled at all, where a Note On used to be forwarded
  with no matching Note Off. Zone-level messages on an input Member Channel are discarded; Master Channel CC `#74` and
  Channel Pressure are forwarded as Zone-level controls; the MIDI Mode messages Omni Mode Off (124), Omni Mode On (125),
  Mono Mode On (126) and Poly Mode On (127) are discarded in both input modes, while All Sound Off (120), Reset All
  Controllers (121), Local Control (122) and All Notes Off (123) keep being forwarded.
- **Uninterpreted RPN/NRPN traffic is re-emitted as complete sequences** ([#261](https://github.com/calinburloiu/microtonalist/issues/261)), so two senders' parameter sequences
  cannot be merged into one another when routing brings them onto a shared output channel. An invalid MPE Configuration
  Message is now ignored entirely, instead of letting its Data Entry escape to the output Master Channel as a bare
  value.
- **Emitted RPN sequences are closed and consistently ordered** ([#259](https://github.com/calinburloiu/microtonalist/issues/259)). The MPE Configuration Message and Pitch Bend
  Sensitivity sequences now end with an RPN Null, so a later stray Data Entry cannot change what they just set. All
  emitters send the selector LSB (CC `#100`) before its MSB (CC `#101`), matching MIDI 1.0's own examples and RP-053
  §2.1.1 — a change of bytes on the wire, not of behaviour.
- **A Zone reconfiguration resets only the channels it affects** ([#262](https://github.com/calinburloiu/microtonalist/issues/262)). An MPE Configuration Message arriving
  mid-performance no longer discards the active Tuning, which used to drop subsequent notes into 12-EDO. Only the
  channels entering or leaving MPE control are reset; untouched channels keep their notes and state.
- **Pitch Bend Sensitivity is restated for both Zones after an MPE Configuration Message** ([#276](https://github.com/calinburloiu/microtonalist/issues/276)), not only for the Zone
  that changed, so the Pitch Bend refresh the other Zone receives is always accompanied by the range its values are
  encoded against.

### Bug fixes

- **Expression Pitch Bend can no longer go stale after a member Pitch Bend Sensitivity change** ([#253](https://github.com/calinburloiu/microtonalist/issues/253)). It is now stored
  raw, exactly as received, so a Pitch Bend Sensitivity change *reinterprets* the held bend — what an ordinary MIDI
  receiver does — rather than leaving a newly started note seeded from a stale value while sounding notes keep their
  deviation in cents. The two code paths that used to disagree are now the same expression.
- **Duplicate Note Ons get matching Note Offs** ([#254](https://github.com/calinburloiu/microtonalist/issues/254)). `ScMidiChannelStateTracker` now reference-counts notes instead
  of holding a set. Notes forwarded on the MPE Master Channel get one Note Off per Note On received rather than one per
  active note, so an instrument that reference-counts duplicates is no longer left with a note hanging. Member Channel
  notes, already reference-counted by the allocator, are unaffected.
- **An RPN/NRPN parameter with a 127 half is no longer mistaken for a half-received selector** ([#267](https://github.com/calinburloiu/microtonalist/issues/267)). The tracker used
  127 as its "not yet received" sentinel, so parameters such as NRPN 0/127 went untracked and their Data Entry was
  discarded rather than forwarded. An RPN Null is now also recognized in either byte order.

### Other user-facing changes

- **Monophonic Pitch Bend Tuner** ([#254](https://github.com/calinburloiu/microtonalist/issues/254)): it inherits the tracker's reference counting, so a Note Off that does not
  discharge a note's last unmatched Note On no longer stops the note.

### Developer-facing changes

- **`sc-midi`**: new `RpnMessages`, the single home of the RPN/NRPN selector encoding, so its byte order is decided in
  one place; `RpnSelector` promoted to a top-level type with its unset halves modelled as `Option` rather than by a 127
  sentinel ([#267](https://github.com/calinburloiu/microtonalist/issues/267)); `ScMidiChannelStateTracker` gained reference counts, a per-channel `reset(channel)` and
  most-recent-first ordering of repeated Note Ons ([#254](https://github.com/calinburloiu/microtonalist/issues/254), [#262](https://github.com/calinburloiu/microtonalist/issues/262)); `ScMidiCc` gained the missing Channel Mode constants.
- **`tuner`**: `MpeChannelAllocator`'s supporting types split into `MpeExpression.scala`, `MpeNoteIdentity.scala` and
  `MpeChannelState.scala`, with every MPE type not referenced from `format` restricted to `private[tuner]` ([#252](https://github.com/calinburloiu/microtonalist/issues/252)). New
  `MpeMessageRouting` holds the paper's message-handling table as pure functions, leaving `MpeTuner` a classify-then-act
  coordinator; the allocator gained a `retaining` factory that rebuilds it across a Zone reconfiguration.
- **Tests and coverage**: the MPE Tuner suites added in the v1.3.0 cycle got their post-merge review ([#256](https://github.com/calinburloiu/microtonalist/issues/256)). Measured
  coverage, statement/branch, v1.3.0 → v1.4.0: `tuner` 83.77%/81.64% → 84.70%/83.14%, `sc-midi` 69.88%/52.31% →
  71.28%/56.04%, and the aggregate across all modules 75.73%/69.85% → 76.29%/71.24%. The `tuner` module's enforced
  branch floor was also raised from 75% to the 80% target its coverage already cleared ([#178](https://github.com/calinburloiu/microtonalist/issues/178)).
- **Paper**: updated alongside the code — the RPN selector order and the rule for re-emitting uninterpreted parameter
  traffic, the two halves of the Note Off obligation on a Zone reconfiguration (Section 4.2), and a Pitch Bend
  Sensitivity change as a second trigger for the shared-channel divergence rule.

## v1.3.0 (2026-08-03)

The headline of this release is the **MPE Tuner**: Microtonalist can now tune polyphonic MIDI streams by using MIDI
Polyphonic Expression, alongside the existing MIDI Tuning Standard (MTS) and Monophonic Pitch Bend protocols. This
release also publishes the design paper behind it.

### MPE Tuner

Until now, playing microtonally over Pitch Bend meant playing one note at a time, because Pitch Bend affects a whole
MIDI channel. The MPE Tuner removes that restriction for MPE-capable instruments: each sounding note gets its own
Member Channel, so each can carry its own tuning offset, and chords are tuned exactly.

Enable it by setting a track's tuner type to `"mpe"` in your `.mtlist.tracks` file:

```json
{
  "type": "mpe",
  "inputMode": "nonMpe",
  "zones": {
    "lower": { "memberCount": 15 },
    "upper": { "memberCount": 0 }
  }
}
```

**What it does:**

- **Works with any controller, not just MPE ones.** In `nonMpe` input mode (the default) the Tuner converts a
  conventional MIDI stream into a fully MPE-conformant output stream, distributing incoming notes across Member
  Channels itself. In `mpe` input mode it accepts MPE input and preserves the performer's per-note expression. The
  Tuner also switches to MPE input mode automatically when it receives an MPE Configuration Message (MCM), reconfiguring
  its Zones accordingly.
- **Intonation comes first.** Standard MPE allocation strategies optimize for expressiveness; this one optimizes for
  correct tuning. Member Channels are split into a *Pitch Class Group* — where no two channels hold the same pitch
  class, so every pitch class keeps its own independent tuning offset — and an *Expression Group*, which absorbs
  repeated pitch classes and overflow. When channels run out, the Tuner drops notes by a specified set of rules rather
  than letting a note sound out of tune.
- **Polyphonic expression.** Pitch Bend, Channel Pressure and CC `#74` are handled per note. Notes that end up sharing an
  output channel have their Expression Values aggregated, and each note's expressive Pitch Bend is kept separate from
  its tuning Pitch Bend, so vibrato and glissandi ride on top of the tuning instead of replacing it.
- **Real-time tuning changes.** Switching tuning mid-performance re-tunes the channels of active notes, which matters
  for tuning systems with more than twelve pitches per octave, where the performer switches tunings to reach the rest of
  the pitch inventory.
- **Configurable Zones.** Lower and Upper Zone member counts and Pitch Bend Sensitivities are all configurable, with the
  MPE defaults applied when omitted (±2 semitones on the Master Channel, ±48 on Member Channels).

#### The paper

The design is documented in full in a paper published with this release:
[**MPE Tuner: A MIDI Polyphonic Expression Approach to Microtonal Intonation**](https://github.com/calinburloiu/microtonalist/blob/v1.3.0/docs/architecture/tuner/mpe-tuner-paper.md).
It covers the dual-group channel partitioning, the allocation and note-dropping algorithms, the polyphonic expression
model, non-MPE→MPE conversion, and the deliberate departures from the MPE Specification (RP-053) that microtonal
intonation requires. It is written to be usable as a reference for other implementations, not just for Microtonalist.

#### MPE limitations and known issues

The MPE Tuner is usable but not yet fully conformant to the paper. The gaps are tracked under the **MPE Follow-up**
milestone:

- **MIDI message routing and filtering does not yet conform to the paper** ([#250](https://github.com/calinburloiu/microtonalist/issues/250)). Specifically: messages arriving
  outside every enabled Zone, or at the wrong level (Zone-level messages on a Member Channel), are not consistently
  discarded; Master Channel CC `#74` and Channel Pressure are not forwarded as Zone-level controls; uninterpreted
  RPN/NRPN traffic is not routed as specified and a forwarded Pitch Bend Sensitivity sequence is not closed with an RPN
  Null; a Zone reconfiguration resets more state than it should; and MIDI Mode messages 124–127 are not discarded.
- **Expression Pitch Bend can go stale after a member Pitch Bend Sensitivity change** ([#253](https://github.com/calinburloiu/microtonalist/issues/253)). If the member PBS changes
  mid-performance, a note started afterwards on that channel — with no intervening Pitch Bend — is seeded from a stale
  raw Pitch Bend value reinterpreted under the new sensitivity, so it can start noticeably out of tune.
- **Note Off counting on the Master Channel** ([#254](https://github.com/calinburloiu/microtonalist/issues/254)). When all notes are stopped, notes forwarded on the Master Channel
  get one Note Off per active note rather than one per Note On received, so an instrument that reference-counts
  duplicate Note Ons may leave a note hanging. Member Channel notes are unaffected.

### Other user-facing changes

- **Monophonic Pitch Bend Tuner**: Pitch Bend Sensitivity can now be configured over MIDI during performance, not only
  in the tracks file ([#144](https://github.com/calinburloiu/microtonalist/issues/144)).
- The launcher scripts moved from `scripts/` to `bin/` (`bin/microtonalist`, `bin/microtonalist-tool`).
- The `README` was rewritten, and `CONTRIBUTING.md` plus a full documentation set under `docs/` were added.

### Developer-facing changes

#### Agentic coding support

The **Agentic Coding** milestone (34 issues) made the repository work well with AI coding agents:

- Claude Code support added, then restructured: the monolithic `CLAUDE.md` was broken into always-loaded root rules
  plus on-demand documents under `docs/agents/`, `docs/development/` and `docs/architecture/`, with a per-module
  `CLAUDE.md` importing that module's architecture document ([#150](https://github.com/calinburloiu/microtonalist/issues/150), [#208](https://github.com/calinburloiu/microtonalist/issues/208), [#212](https://github.com/calinburloiu/microtonalist/issues/212), [#214](https://github.com/calinburloiu/microtonalist/issues/214)).
- A `contributing` skill bundling a `microtonalist-gh` script that applies the repository's labels, milestones,
  Projects-v2 assignment and title conventions in one call ([#210](https://github.com/calinburloiu/microtonalist/issues/210), [#221](https://github.com/calinburloiu/microtonalist/issues/221)).
- A `scoverage-inspector` MCP server that parses coverage reports in-process instead of loading large XML files, plus
  per-module coverage thresholds and a `coverageModules` sbt command ([#183](https://github.com/calinburloiu/microtonalist/issues/183), [#191](https://github.com/calinburloiu/microtonalist/issues/191), [#220](https://github.com/calinburloiu/microtonalist/issues/220)).
- Development stack scripts consolidated into a single `bin/microtonalist-dev-stack` command, with the Metals BSP-server
  sbt isolated from the CLI sbt so the two no longer race over the same target directories ([#186](https://github.com/calinburloiu/microtonalist/issues/186), [#205](https://github.com/calinburloiu/microtonalist/issues/205)).
- Apache license headers are added by a pre-commit hook, enforced in CI, and skipped when agents read files so they
  don't consume context ([#233](https://github.com/calinburloiu/microtonalist/issues/233)).
- Test output is filtered for agents, and the shared test helpers moved into a `common-test-utils` module ([#218](https://github.com/calinburloiu/microtonalist/issues/218)).

#### `sc-midi` library

The **sc-midi** milestone continued making the internal Scala MIDI wrapper a complete, idiomatic MIDI 1.0 API:

- `ScMidiMessage` reorganized into a dedicated `message` package, with support for the remaining MIDI 1.0 messages and
  new `ScMidiCc`, `ScMidiRpn` and `ScMidiNrpn` abstractions ([#157](https://github.com/calinburloiu/microtonalist/issues/157), [#162](https://github.com/calinburloiu/microtonalist/issues/162)).
- Java↔Scala MIDI conversion extracted into `JavaMidiConverters` ([#164](https://github.com/calinburloiu/microtonalist/issues/164)).
- New `ScMidiChannelStateTracker` and `ScMidiReceiver`, which track per-channel state (active notes, Pitch Bend,
  controllers) and are now used by both the monophonic and MPE tuners ([#155](https://github.com/calinburloiu/microtonalist/issues/155), [#167](https://github.com/calinburloiu/microtonalist/issues/167)).
- `mapChannel` added to `ChannelScMidiMessage`, and `ScProgramChangeMidiMessage` added ([#151](https://github.com/calinburloiu/microtonalist/issues/151), [#169](https://github.com/calinburloiu/microtonalist/issues/169)).
- Message tests unified into a single `ScMidiMessageTest` ([#171](https://github.com/calinburloiu/microtonalist/issues/171)).

#### Follow-up work

Besides the MPE conformance issues above, the **MPE Follow-up** milestone also tracks splitting
`MpeChannelAllocator`'s value and result types out of its file and tightening their visibility ([#252](https://github.com/calinburloiu/microtonalist/issues/252)), and a final
human review of the MPE Tuner test suites ([#256](https://github.com/calinburloiu/microtonalist/issues/256)).

## v1.2.1 (2025-05-17)

### What's Changed

* Fix CI test issue and concurrency bug in `DeferrableRead` by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/141

**Full Changelog**: https://github.com/calinburloiu/microtonalist/compare/v1.2.0...v1.2.1

## v1.2.0 (2025-05-17)

### What's Changed

* Use the term URL instead of URI in the file formats by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/140
    * In composition files:
        - `baseUri` is renamed to `baseUrl`
        - `tracksUri` is renamed to `tracksUrl`
    * In configuration files:
        - `libraryBaseUri` is renamed to `libraryBaseUrl`.

**Full Changelog**: https://github.com/calinburloiu/microtonalist/compare/v1.1.2...v1.2.0

## v1.1.2 (2025-05-17)

### What's Changed

* [[#134](https://github.com/calinburloiu/microtonalist/issues/134)] Make MidiProcessor be composed from a Receiver and a Transmitter by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/135
* [[#137](https://github.com/calinburloiu/microtonalist/issues/137)] Wire TrackSession to TrackRepo by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/138
* [[#68](https://github.com/calinburloiu/microtonalist/issues/68)] Intonation standard does not convert intervals for an inline scale in a composition by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/139

**Full Changelog**: https://github.com/calinburloiu/microtonalist/compare/v1.1.1...v1.1.2

## v1.1.1 (2025-04-08)

This is a patch release that fixes a bug.

### Issues Resolved

* [[#133](https://github.com/calinburloiu/microtonalist/issues/133)] tracksUri does not work with microtonalist scheme by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/136

**Full Changelog**: https://github.com/calinburloiu/microtonalist/compare/v1.1.0...v1.1.1

## v1.1.0 (2025-04-06)

### Features

* Support for Tracks files (`*.mtlist.tracks`).
    * By default a file with the same name but with the added extension `.tracks` is search in the same directory, unless the composition sets a different path via its `tracksUri` property.
    * Multi-track support allows configuring multiple input/output devices. Alternatively, racks may route the input or output from other tracks or to other tracks, respectively.
    * Setting channel numbers is not currently implemented.
* Support for new tuning standards (tuner types). Now all octave-based MTS (MIDI Tuning Standard) tuner types are supported, including with real-time and non-real-time support.
* In a composition JSON, `globalFill` property was moved to `fill.global`. This allows configuring other fill types in the future in the same place. Local fill, although supported is not currently configurable.
* Stability and architecture changes in the MIDI and tuner modules of the application. However, the changes are not finalized and are expected to finish by the end of the next minor release (1.2.0).

### Issues Resolved

* [[#92](https://github.com/calinburloiu/microtonalist/issues/92)] Reorganize modules by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/93
* [[#88](https://github.com/calinburloiu/microtonalist/issues/88)] Refactor MidiManager by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/91
* Factor-out config module by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/94
* [[#95](https://github.com/calinburloiu/microtonalist/issues/95)] Refactor tuning change flow by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/101
* [[#95](https://github.com/calinburloiu/microtonalist/issues/95)] Allow configuring triggersThru per TuningChanger by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/104
* [[#65](https://github.com/calinburloiu/microtonalist/issues/65)/#105] Add JSON-Schema for TuningChanger plugin and reorganize schemas in directories by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/107
* [[#108](https://github.com/calinburloiu/microtonalist/issues/108)] Implement format for TuningChanger by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/110
* [[#111](https://github.com/calinburloiu/microtonalist/issues/111)] Create a fill root property to include global and local fill specification by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/112
* [[#96](https://github.com/calinburloiu/microtonalist/issues/96)] Refactor Tuner interface to comply to Plugin by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/113
* [[#109](https://github.com/calinburloiu/microtonalist/issues/109)] Implement format for Tuner by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/114
* [[#98](https://github.com/calinburloiu/microtonalist/issues/98)] Merge Tuning and PartialTuning into a single class by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/115
* [[#98](https://github.com/calinburloiu/microtonalist/issues/98)] Make composition module depend on tuner module by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/116
* [[#98](https://github.com/calinburloiu/microtonalist/issues/98)] Clean-up obsolete terms from code (partial tuning, deviation etc.)  by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/117
* [[#120](https://github.com/calinburloiu/microtonalist/issues/120)] Implement track specs management by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/123
* [[#118](https://github.com/calinburloiu/microtonalist/issues/118)] Implement format for track I/O plugins by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/124
* [[#119](https://github.com/calinburloiu/microtonalist/issues/119)] Read tracks files from the file URI scheme  by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/125
* [[#103](https://github.com/calinburloiu/microtonalist/issues/103)] Upgrade to Scala 3.3.1. by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/126
* [[#65](https://github.com/calinburloiu/microtonalist/issues/65)] Finish adding JSON-Schema for tracks by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/127
* [[#64](https://github.com/calinburloiu/microtonalist/issues/64)] Finalize support for tracks files by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/128
* [[#122](https://github.com/calinburloiu/microtonalist/issues/122)] Implement routing between tracks by @calinburloiu in https://github.com/calinburloiu/microtonalist/pull/130

**Full Changelog**: https://github.com/calinburloiu/microtonalist/compare/v1.0.0...v1.1.0

## v1.0.0 (2024-11-04)

This is the first stable release of Microtonalist. The file format for scales and compositions should be stable and not break compatibility unless the major component of the version is updated.

### Features

Here is a list of features supported in this release:

* Support for Huygens-Fokker Scala files (`.scl`) for scales.
* Support for Microtonalist own scale files in JSON format (`.jscl`). The format allows defining scale pitches in cents, ratios or EDO (equal divisions of the octave). The latter optionally allows setting the number of divisions relative to 12-EDO (useful for EDOs that are multiple of 12, such as 24-EDO and 72-EDO).
* Support for _composition_ files in JSON format which allow sequencing one or more scale files.
* Scales can be mapped to a tuning for a keyboard musical instrument by using a _tuning mapper_. All instrument keys with the same pitch class have the same tuning, so a tuning spans one octave to 12 instrument keys. Scales can be mapped automatically (if possible), manually, or a combination of the two.
* The tunings mapped can be reduced if possible if two adjacent tunings do not have a _conflict_ with two different tuning values on the same keyboard key.
* Quarter tones and soft chromatic genus scale structures used in Oriental music can be mapped in a customizable idiomatic way.
* The following limited number of tuning standards for tuning keyboards and synthesizers are supported:
    - MTS (MIDI Tuning Standard) Scale/Octave Tuning 1-Byte Form (Non Real-Time)
        - Works on some Roland devices. Only tested on the Roland FP-90 digital piano.
    - Monophonic Pitch Bend
* Configuring a single track with MIDI input/output and one of the tuning standards supported. The track can be defined via the application configuration file in HOCON format. This way of configuring tracks is subject to change during the next minor releases.
* Custom CC messages can be sent when the output MIDI device is being initialized.
* The change of the tuning can be triggered either by selecting one of the tunings from the UI, by using the Up/Down arrow key, by pressing a number on the keyboard between 1 and 9 or by configuring CC messages for switching to the next or previous tuning. By default the CC messages as set such that the center and left piano pedals switch to the next or previous tuning, respectively.
* Ability to see in the command line the current tuning.

### Future Improvements

* Support for more tuning standards: more MTS standards, MPE and Yamaha-specific format.
* Configuring multiple tracks, each with its own tuning standard and way of changing the tuning, via a new JSON file.
* Ability to see the current tuning in the UI.
* Ability to configure tracks in the UI.
* Detecting input files changes to automatically refresh the resulted tunings.
* Improvements in error reporting and ability to see the errors in the UI.
