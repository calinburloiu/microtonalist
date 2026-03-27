# MPE Tuner: A MIDI Polyphonic Expression Approach to Microtonal Intonation

Călin-Andrei Burloiu, 2026

[Microtonalist](https://github.com/calinburloiu/microtonalist)

**Abstract.**
This paper presents the MPE Tuner, a MIDI signal processing component of the [Microtonalist](https://github.com/calinburloiu/microtonalist) application that applies microtonal tunings to polyphonic MIDI streams by leveraging the MIDI Polyphonic Expression (MPE) protocol. A core capability of the MPE Tuner is support for real-time tuning changes during performance, enabling the use of complex tuning systems in which twelve pitch classes per octave are insufficient and the performer must switch between tunings to access additional pitches. The MPE Specification (RP-053, v1.0, 2018) defines a mechanism for per-note pitch and articulation control by assigning notes to individual MIDI Channels within configurable Zones. The MPE Tuner exploits this mechanism to apply pitch-class-based tuning offsets via per-channel Pitch Bend messages. However, standard MPE channel allocation strategies are insufficient when intonation precision is a primary design goal. This paper details the MPE Tuner's architecture, its channel allocation strategy employing a novel dual-group partitioning of Member Channels, its handling of non-MPE to MPE conversion, and the deliberate departures from the MPE Specification's recommendations that are necessary to guarantee correct and stable microtonal intonation under polyphonic conditions. The specification herein is intended to serve as a reference for software implementations.

---

## 1. Introduction

### 1.1 Motivation

The MIDI 1.0 protocol encodes pitch as discrete note numbers corresponding to the twelve-tone equal temperament (12-EDO) tuning system. Musicians and composers working with microtonal tuning systems, historical temperaments, or non-Western scales require a mechanism to deviate from 12-EDO while retaining the polyphonic expressiveness that modern MIDI controllers and instruments afford.

Many tuning systems of musical interest employ more than twelve distinct pitches per octave. Maqam music, for example, uses quarter-tone inflections that double the number of available pitch classes; just intonation systems may require dozens of distinct pitches to cover all harmonic relationships across multiple keys. When a tuning system exceeds twelve pitches per octave, the twelve keys of a standard MIDI keyboard are insufficient to address all pitches simultaneously. A performer working in such a system must be able to switch between tunings in real time—reassigning the twelve keys to different subsets of the tuning system's pitch inventory as the musical context demands. Real-time tuning change is therefore not an auxiliary feature but a core capability that the MPE Tuner is designed to support.

Several approaches to microtonality over MIDI exist. The MIDI Tuning Standard (MTS) uses System Exclusive messages to reprogram an instrument's pitch table. Monophonic Pitch Bend tuners apply per-note pitch correction on a single-voice basis. Each approach is limited to instruments that support the respective protocol. The MPE Tuner presented in this paper targets the increasingly prevalent class of instruments that support MIDI Polyphonic Expression (MPE), as specified in the MIDI Manufacturers Association's RP-053 [1].

MPE achieves per-note control by assigning each sounding note to its own MIDI Channel within a defined Zone, thereby enabling independent Pitch Bend, Channel Pressure, and CC #74 (timbre) control for each note. This per-note Pitch Bend capability provides a natural substrate for applying microtonal tuning offsets: the tuning correction for each note can be expressed as a Pitch Bend value on that note's dedicated Member Channel.

### 1.2 Scope and Definitions

A **Tuning**, in the context of this specification, is a set of twelve pitch offsets—one for each pitch class (C, C♯, D, ..., B)—measured relative to the standard 12-EDO tuning. A Tuning may be changed by the performer in real time.

A **Tuner** is a MIDI processing device that applies a given Tuning to a MIDI signal using a specific output protocol. It receives MIDI input assumed to be in standard 12-EDO tuning, processes the messages according to the active Tuning, and produces MIDI output conforming to a protocol understood by the receiving instrument. The Tuner has a single MIDI input and a single MIDI output. Examples of Tuner types include:

- **MTS Tuner**: outputs MIDI Tuning Standard SysEx messages for instruments supporting MTS.
- **Monophonic Pitch Bend Tuner**: outputs Pitch Bend on a single channel for monophonic instruments.
- **MPE Tuner**: outputs MPE-conformant messages for instruments supporting MPE.

Any pitch alteration present in the input signal—such as Pitch Bend applied by the performer—is not interpreted as an alternative tuning. It is treated exclusively as an **expressive pitch bend**: a transient, performance-driven deviation from the base pitch. The MPE Tuner first applies the tuning offset for the note's pitch class, then adds the expressive pitch bend offset on top of it.

### 1.3 Overview of Operation

The MPE Tuner sits in the MIDI signal path between a controller (or sequencer) and an MPE-capable instrument. It performs the following operations:

1. Receives MIDI input, which may be either conventional (non-MPE) or MPE.
2. Allocates incoming notes to MPE Member Channels according to a strategy that prioritizes intonation precision.
3. Computes the appropriate Pitch Bend value for each Member Channel by combining the tuning offset for the channel's pitch class with any expressive pitch bend from the input.
4. Outputs a fully MPE-conformant MIDI stream.

The output of the MPE Tuner is always MPE, regardless of whether the input is MPE or non-MPE. The input mode may be configured through a non-MIDI interface or detected automatically: upon receiving an MPE Configuration Message (MCM), the MPE Tuner shall switch to MPE input mode.

---

## 2. Background: The MPE Specification

This section summarizes the relevant aspects of the MPE Specification (RP-053, v1.0) [1] that form the foundation upon which the MPE Tuner operates. Readers familiar with the MPE Specification may proceed to Section 3.

### 2.1 Zones, Master Channels, and Member Channels

MPE organizes the sixteen MIDI Channels into one or two **Zones**. Each Zone consists of a **Master Channel** and one or more **Member Channels**:

- The **Lower Zone** uses Channel 1 as its Master Channel, with Member Channels allocated sequentially upward from Channel 2.
- The **Upper Zone** uses Channel 16 as its Master Channel, with Member Channels allocated sequentially downward from Channel 15.

A Zone is configured by sending an MPE Configuration Message (MCM)—Registered Parameter Number 00 06—on the Master Channel of the desired Zone. The Data Entry MSB specifies the number of Member Channels. When only one Zone is active, the Master Channel of the unused Zone is available as a Member Channel, permitting up to 15 Member Channels.

### 2.2 Per-Note Control via Channel Assignment

The MPE Specification states:

> "An MPE controller assigns every new note its own MIDI Channel, until there are no unoccupied Channels available." [1, §2.2.1]

Each note assigned to its own Member Channel can receive independent Pitch Bend, Channel Pressure, and CC #74 messages. Messages sent on the Master Channel apply to all notes in the Zone. The specification requires that if a device receives Pitch Bend (or Channel Pressure, or CC #74) on both a Master and a Member Channel, "it must combine the data meaningfully" [1, §2.3.2].

### 2.3 Pitch Bend Sensitivity

Upon receiving an MCM, a receiver must set:

- **Master Channel Pitch Bend Sensitivity**: ±2 semitones (default).
- **Member Channel Pitch Bend Sensitivity**: ±48 semitones (default).

These values may be changed via RPN 0. All Member Channels within a Zone must share the same Pitch Bend Sensitivity value [1, §2.4].

### 2.4 Channel Allocation Recommendations

The MPE Specification provides guidelines for allocating notes to Member Channels when implementing an MPE controller:

> "In the simplest workable implementation, a new note will be assigned to the Channel with the lowest count of active notes. Then, all else being equal, the Channel with the oldest last Note Off would be preferred." [1, §3.2]

The specification acknowledges that multiple notes may need to coexist on the same Channel when all Channels are occupied, and that there are legitimate scenarios for having the same Note Number active on two different Channels:

> "In particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch. Alternatively, a guitar-type controller might permit the same pitch to be played simultaneously on different strings." [1, §3.2]

When all Channels are occupied, the specification suggests two approaches:

> "A controller may choose the Channel in which the change of pitch for the new note requires the smallest adjustment of pitch for other playing notes. Alternatively, at least one commercial implementation provides gentle degradation of pitch control when all Channels are occupied by switching to a mode where notes step discretely from one pitch to the next, permitting Pitch Bend to respond only to small vibrato gestures." [1, §3.2]

### 2.5 Note-On Setup and Message Ordering

The specification recommends sending all control dimension messages (Pitch Bend, CC #74, Channel Pressure) before the Note On message to prevent audible artifacts:

> "Provided that the Note On follows all necessary initial settings for pitch and articulation, other orderings of these messages will work equally well." [1, §3.3.1]

This practice prevents "swooping" noises caused by a Channel retaining a previous note's Pitch Bend value when a new note begins.

---

## 3. MPE Tuner Architecture

### 3.1 Signal Flow

The MPE Tuner is a stateful MIDI processor with the following signal flow:

```mermaid
flowchart LR
    A["MIDI Input"] --> B["Input Mode Detection"]
    B --> C["Channel Allocation"]
    C --> D["Tuning Application"]
    D --> E["MIDI Output"]
```

1. **Input Mode Detection**: Determines whether the incoming stream is MPE or non-MPE. The mode may be set via a non-MIDI configuration interface. Receipt of an MPE Configuration Message (MCM) shall cause the Tuner to switch to MPE input mode automatically.

2. **Channel Allocation**: Assigns each incoming note to a Member Channel in the output Zone(s), following the dual-group allocation strategy described in Section 4.

3. **Tuning Application**: For each occupied Member Channel, computes the output Pitch Bend as the sum of the tuning offset for the channel's associated pitch class and the expressive pitch bend from the input.

### 3.2 Input Modes

The MPE Tuner accepts two classes of input:

- **Non-MPE input**: Conventional MIDI where all notes may arrive on a single channel or across channels without MPE Zone structure. This input requires conversion to MPE (Section 3.3).
- **MPE input**: MIDI conforming to the MPE Specification, with notes already distributed across Member Channels within Zones.

In both cases, the output is always MPE-conformant.

### 3.3 Non-MPE to MPE Conversion

When the input is non-MPE, the MPE Tuner must perform the following conversions to produce valid MPE output:

1. **Polyphonic Key Pressure to Channel Pressure**: In MPE, Polyphonic Key Pressure must not be sent on Member Channels [1, §2.5]. Any Polyphonic Key Pressure messages in the non-MPE input shall be converted to Channel Pressure messages on the appropriate Member Channels.

2. **Pitch Bend Redirection to Master Channel**: In non-MPE input, Pitch Bend typically applies to all notes on a channel. This global pitch manipulation shall be redirected to the Master Channel of the output Zone, where it serves as Zone-level Pitch Bend affecting all notes equally.

3. **Control Dimension Initialization**: To maximize compatibility with MPE receivers, all three control dimensions (Pitch Bend, CC #74, and Channel Pressure) shall be sent before each Note On message, even when the input sender omitted some of them. When a control dimension was not present in the input, the MPE Tuner shall use the default values as documented in the MPE Specification:
   - **Pitch Bend**: center value (0x2000, i.e., no bend), combined with the tuning offset.
   - **CC #74**: 64 (0x40), the neutral initial-64 value [1, §3.3.5].
   - **Channel Pressure**: 0 [1, §3.3.4].

This initialization practice aligns with the MPE Specification's recommendation to prevent audible artifacts at note onset [1, §3.3.1].

---

## 4. Allocation of Notes to Member Channels

The channel allocation strategy is the central contribution of the MPE Tuner design. Standard MPE allocation strategies aim to maximize per-note expressiveness while gracefully handling polyphony overflow. The MPE Tuner reorders these priorities: **intonation precision is the primary objective**, even at the cost of dropping active notes or constraining expressive independence.

### 4.1 Fundamental Invariant

The following invariant governs all channel allocation decisions:

> **Multiple active notes are permitted on the same Member Channel only if they share the same pitch class.**

This invariant follows directly from how a Tuning is defined: as a set of offsets indexed by pitch class. Because the Pitch Bend on a Member Channel encodes the tuning offset for a specific pitch class, placing notes of different pitch classes on the same channel would require a single Pitch Bend value to represent two different tuning offsets simultaneously—an impossibility that would compromise the intonation of at least one note.

Furthermore, even when two pitch classes happen to have identical tuning offsets at a given moment, they may not share a Member Channel. The Tuning may change at any time during performance, potentially assigning different offsets to those pitch classes. Preemptively separating them onto distinct channels ensures that the Tuner can always adjust each pitch class independently without interrupting sounding notes.

### 4.2 Dual-Group Channel Partitioning

For each Zone, the available Member Channels are logically partitioned into two groups:

- **Pitch Class Group**: Channels reserved for notes of distinct pitch classes. Within this group, no two occupied Channels may have active notes of the same pitch class. This group ensures that the Zone can accommodate as many distinct pitch classes as possible, each with an independently controllable tuning offset.

- **Expression Group**: Channels available for notes whose pitch class is already represented in the Pitch Class Group, or for notes that cannot be accommodated in the Pitch Class Group because all its channels are occupied. This group accommodates scenarios where multiple notes of the same pitch class must coexist with different expressive pitch bends—for example, when a note is bent away from its original pitch and a new note at that original pitch is initiated.

Unoccupied Member Channels are not considered to be part of any group. Group assignment occurs dynamically: any
unoccupied channel may be assigned to either group as notes are allocated. The group assignment of a channel is
determined at the moment a note is placed on it and persists for the lifetime of that channel's occupancy.

### 4.3 Group Size Allocation

The number of channels allocated to each group depends on the total number of Member Channels `n` configured for the Zone:

| Member Channels (`n`) | Pitch Class Group (`a`) | Expression Group (`b`) |
|---|---|---|
| 10 ≤ n ≤ 15 | n − 3 | 3 |
| 3 ≤ n < 10 | n − 2 | 2 |
| n = 2 | 1 | 1 |
| n = 1 | 1 | 0 |

The rationale for these sizes is as follows. The Pitch Class Group must be large enough to cover the maximum number of distinct pitch classes likely to be sounding simultaneously. Notably, for a single Zone with 15 Member Channels, the Pitch Class Group has 12 channels—exactly the number required to represent all 12 pitch classes of a standard keyboard simultaneously. The Expression Group provides a small buffer for expressive duplication of pitch classes. When only one Member Channel is available, the Expression Group is necessarily empty, and the Tuner operates with strict one-note-per-pitch-class behavior.

### 4.4 High Expressive Pitch Bend

A threshold value `t`, measured in cents, defines the boundary between small expressive gestures (vibrato, subtle
inflections) and large pitch bends. The value of `t` represents the absolute pitch deviation from the tuned pitch caused
by the expressive pitch bend. A note whose expressive pitch bend causes a deviation exceeding `t` in either direction is
considered to have a **high expressive pitch bend**. The recommended value is `t = 50` cents (half a semitone): any note
bent more than 50 cents up or down from its tuned pitch has a high expressive pitch bend.

### 4.5 Allocation Algorithm

When a new note arrives, the MPE Tuner executes the following allocation procedure:

1. **Check Pitch Class Group availability**: If the Pitch Class Group contains an unoccupied channel *and* no occupied channel in the Pitch Class Group has an active note with the new note's pitch class, assign the new note to an unoccupied channel in the Pitch Class Group.

2. **Pitch Class Group unavailable**: If the Pitch Class Group already holds a note with the new note's pitch class *or* all Pitch Class Group channels are occupied, attempt to assign the new note to an unoccupied channel in the Expression Group.

3. **Expression Group full**: If no unoccupied channel is available in the Expression Group—and the Pitch Class Group either has an active note with the new note's pitch class or has all channels occupied—assign the new note to any channel (from either group) that already holds active notes with the same pitch class.

4. **Tie-breaking among candidates**: When multiple channels are valid candidates at any step, the following criteria are applied in order, consistent with the MPE Specification's recommendations [1, §3.2]:
   - Prefer channels that don't have high expressive pitch bend.
   - Among them, prefer the channel with the lowest count of active notes.
   - Among channels with equal active note counts, prefer the channel with the oldest last Note Off (i.e., the channel that has been idle the longest).
   - If the oldest last Note Off is equal among channels, select the oldest channel, which is the one with the most
     recent onset time that is oldest among all candidates.

5. **Preserving input channel allocation (MPE input only)**: When the input is MPE, the Tuner should attempt to preserve the input's channel assignment for a new note, provided that doing so does not violate the pitch-class invariant or the group constraints. Only when the constraints require it should the Tuner reallocate a note to a different channel.

### 4.6 Pitch Bend Computation for Shared Channels

When a Member Channel holds multiple active notes (necessarily of the same pitch class), the output Pitch Bend for that channel is computed as follows:

```
Output Pitch Bend = Tuning Offset(pitch class) + average(expressive pitch bends of all active notes on the channel)
```

The averaging of expressive pitch bends is a necessary compromise when multiple notes share a channel. It provides a natural and gentle degradation of per-note pitch control. For example, if three notes share a channel and only one has an expressive vibrato in the input, the output vibrato amplitude on that channel will be one-third of the input amplitude—a musically acceptable attenuation that preserves the correct base intonation.

This behavior aligns with the MPE Specification's suggestion of "gentle degradation of pitch control when all Channels are occupied" [1, §3.2].

### 4.7 Comparison with Standard MPE Allocation

The MPE Tuner's allocation strategy departs from the MPE Specification's recommendations in several important respects. Each departure is motivated by the requirement to maintain precise intonation.

#### 4.7.1 Channel Sharing Before Exhaustion

The MPE Specification recommends:

> "An MPE controller assigns every new note its own MIDI Channel, until there are no unoccupied Channels available." [1, §2.2.1]

The MPE Tuner does **not** follow this recommendation unconditionally. Because the pitch-class invariant prohibits placing notes of different pitch classes on the same channel, and because the Pitch Class Group restricts each pitch class to at most one channel within it, the Tuner may assign a new note to an already-occupied channel even when unoccupied channels remain in the Pitch Class Group. This occurs when the new note's pitch class is already represented in the Pitch Class Group and must therefore be placed in the Expression Group or on the existing channel.

This departure is essential: blindly assigning each note to a fresh channel without regard to pitch class would eventually require a single channel to carry conflicting tuning offsets, destroying intonation accuracy.

#### 4.7.2 Prioritizing Intonation Over Note Preservation

The MPE Specification's allocation guidelines are designed to maximize polyphonic expressiveness and avoid dropping notes. The MPE Tuner inverts this priority: **correct intonation is never sacrificed to preserve an older note**. When channel resources are insufficient to maintain both intonation precision and all currently sounding notes, the Tuner drops notes (Section 5).

#### 4.7.3 Gentle Degradation via Averaging

The MPE Specification notes that one commercial implementation achieves gentle degradation by "switching to a mode where notes step discretely from one pitch to the next, permitting Pitch Bend to respond only to small vibrato gestures" [1, §3.2]. The MPE Tuner achieves an analogous effect through pitch bend averaging on shared channels: because notes sharing a channel must have the same pitch class (and hence the same tuning offset), and because high expressive pitch bends are constrained (Section 5.2), the effective Pitch Bend on a shared channel responds primarily to small gestures. More sophisticated implementations of the MPE Tuner may additionally implement discrete pitch stepping when a new note arrives on an occupied channel, smoothing the audible transition.

#### 4.7.4 Same Note Number on Multiple Channels

The MPE Specification acknowledges the legitimacy of having the same Note Number active on multiple Channels:

> "In particular circumstances it is appropriate to have the same Note Number active on two different MIDI Channels. For example, a note may start at a certain pitch and be bent to another before a second note is initiated at the original pitch." [1, §3.2]

The Expression Group was introduced specifically to support this use case. When a note's pitch class already occupies a channel in the Pitch Class Group, the Expression Group provides additional channels where the same pitch class can be sounded with a different expressive pitch bend, enabling scenarios such as the bent-then-restruck pattern described in the specification.

#### 4.7.5 Master Channel Pitch Bend

The MPE Specification requires:

> "If an MPE synthesizer receives Pitch Bend on both a Master and a Member Channel, it must combine the data meaningfully." [1, §2.3.2]

The MPE Tuner forwards Master Channel Pitch Bend as received, without modification. Master Pitch Bend is not used by the Tuner in computing tuning offsets; it is treated as a component of the expressive pitch bend, controlled entirely by the performer. The Tuner's tuning offsets are applied exclusively through Member Channel Pitch Bend.

---

## 5. Dropping Notes and Freeing Channels

An acceptable cost of maintaining precise intonation is the occasional dropping of notes. This section specifies the
conditions under which notes are dropped and the criteria for selecting which notes to drop. We maintain the principle
that dropping is the last resort measure, used only when the fundamental invariants of intonation would otherwise be
violated.

In practice, note dropping should rarely occur for Zones with 7 or more Member Channels. Musical scales seldom contain more than 7 notes per octave, and even complex jazz chords rarely employ more than 7 distinct pitch classes simultaneously. In the ideal scenario of a single Zone with 15 Member Channels, the Pitch Class Group accommodates all 12 pitch classes and the Expression Group provides 3 additional channels for duplicate pitch classes; note dropping never occurs under these conditions, because the Pitch Class Group can always represent every pitch class of the chromatic scale. When two equal Zones are configured—the typical dual-Zone split allocates 7 Member Channels to each Zone—each Zone can support at least 7 simultaneous distinct pitch classes (5 in the Pitch Class Group and 2 in the Expression Group for `n = 7`), which suffices for the vast majority of musical contexts.

### 5.1 Dropping Notes Due to Channel Exhaustion

When all Member Channels are occupied and the Pitch Class Group does not have enough channels to support all pitch classes present among the active notes, some notes must be dropped to free a channel for the incoming note. The term **freeing a channel** refers to dropping all notes on that channel to make it unoccupied.

The selection of which channel to free follows this procedure:

1. **Exclude boundary channels**: Channels holding the highest-pitched and lowest-pitched notes among all active notes are excluded from consideration. Dropping extreme-register notes is perceptually more disruptive, as they often define the harmonic boundaries of the musical texture.

2. **Select the oldest channel**: From the remaining candidates, select the channel with the most recent onset time that is oldest among all candidates—that is, the channel whose last note onset occurred earliest. Using last onset time rather than average onset time simplifies the implementation and provides a clear, unambiguous ordering.

For a Zone configured with the maximum of 15 Member Channels, note dropping should not occur under normal playing conditions. The Pitch Class Group accommodates exactly 12 channels—the number required to represent every pitch class of a standard piano keyboard. The Expression Group provides a 3-channel buffer for duplicate pitch classes. Dropping occurs only when the Pitch Class Group cannot accommodate all distinct pitch classes in use *and* all Expression Group channels are already occupied—a situation that requires an unusually high degree of simultaneous polyphony.

### 5.2 Dropping Notes Due to High Expressive Pitch Bend

Notes are dropped in the following situations involving high expressive pitch bend:

#### 5.2.1 Divergence on a Shared Channel

When a channel holds multiple active notes and one of them develops a high expressive pitch bend, **all other notes on
that channel are dropped**. The rationale is that the performer's intent is to bend a single note; the other notes
sharing the channel would receive an unintended pitch deviation due to the averaged Pitch Bend computation (Section
4.6).

#### 5.2.2 New Note with High Expressive Pitch Bend on an Occupied Channel

When a new note with a high expressive pitch bend is assigned to an already-occupied channel, **all existing notes on that channel are dropped** (the channel is freed). This holds even if the existing notes' Pitch Bend values are close to that of the new note, because there is no guarantee that the existing notes' bends will not subsequently diverge from the new note's bend, causing unintended intonation changes.

It follows that **when an active note on a channel has a high expressive pitch bend, that note is necessarily the sole active note on the channel**. No other active notes can coexist with it: existing notes are dropped when one develops a high expressive pitch bend (Section 5.2.1), and new notes arriving on a channel with a high-bend note cause the channel to be freed (Section 5.2.3).

#### 5.2.3 New Note Assigned to a Channel with a High-Bend Note

When a new note is assigned to a channel that already contains an active note with a high expressive pitch bend, **the channel is freed** (all existing notes are dropped). The new note then occupies the channel exclusively, preventing its intonation from being compromised by the pre-existing high bend.

### 5.3 Summary of Note-Dropping Invariants

The following invariants are maintained at all times through the note-dropping mechanisms described above:

1. All active notes on a shared channel have the same pitch class.
2. An active note with a high expressive pitch bend (absolute deviation > `t`) is always the sole active note on its
   channel. No other notes may coexist with it: pre-existing notes are dropped, and the channel is freed before any new
   note is assigned to it (Section 4.6).

---

## 6. MPE Tuner Output Conformance

The output of the MPE Tuner conforms to the MPE Specification with the following specific behaviors:

### 6.1 Zone Configuration

The MPE Tuner outputs MPE Configuration Messages to establish the Zone structure on the receiving instrument. It supports both single-Zone and dual-Zone configurations as defined by the MPE Specification [1, §2.1].

### 6.2 Pitch Bend Sensitivity

The MPE Tuner relies on the default Member Channel Pitch Bend Sensitivity of ±48 semitones [1, §2.4]. This range is sufficient for all practical microtonal tuning offsets (which rarely exceed ±1 semitone for common temperaments, though extreme tuning systems may approach larger values). Implementations should verify that the receiving instrument's Pitch Bend Sensitivity is configured appropriately, and may send RPN 0 messages to all Member Channels to ensure the correct sensitivity is set.

### 6.3 Message Ordering

For each new note, the MPE Tuner outputs messages in the following order on the assigned Member Channel:

1. **Pitch Bend**: Encoding the sum of the tuning offset and the initial expressive pitch bend.
2. **CC #74**: The timbre control value, forwarded from the input or set to the default value of 64.
3. **Channel Pressure**: Forwarded from the input or set to the default value of 0.
4. **Note On**: The note message itself.

This ordering follows the MPE Specification's recommendation [1, §3.3.1] and ensures that the receiving instrument has the correct pitch and articulation state before the note begins sounding.

### 6.4 Zone-Level Messages

Zone-level messages (Damper Pedal, Program Change, Reset All Controllers, and other messages listed in Table 1 of the MPE Specification) are forwarded on the Master Channel without modification. The MPE Tuner does not interpret or alter these messages.

### 6.5 Note Off Behavior

Upon Note Off, the MPE Tuner ceases controlling the Pitch Bend for the released note's channel (consistent with the specification's statement that "control of a note ceases once Note Off has occurred" [1, §3.3.3]). The channel becomes available for reuse once all its notes have received Note Off messages.

---

## 7. Real-Time Tuning Changes

A distinguishing feature of the MPE Tuner is its support for real-time tuning changes during performance. This capability is essential for tuning systems that employ more than twelve pitches per octave, where the performer must switch tunings to access different subsets of the available pitch inventory.

When the performer changes the active Tuning:

1. The Tuner updates the stored tuning offsets for each pitch class.
2. For every occupied Member Channel, the output Pitch Bend is recomputed using the new tuning offset for that channel's pitch class, combined with the current expressive pitch bend.
3. The updated Pitch Bend message is sent immediately on each affected Member Channel.

Because the pitch-class invariant (Section 4.1) guarantees that all notes on a given channel share the same pitch class, a single Pitch Bend update per channel is sufficient to retune all notes on that channel simultaneously. This is the fundamental reason for the invariant: it enables instantaneous, glitch-free retuning of the entire polyphonic texture.

If the invariant were violated—if notes of different pitch classes shared a channel—a tuning change that assigned different offsets to those pitch classes could not be correctly represented by a single Pitch Bend value, and at least one note would be mistuned until it was moved to a different channel.

---

## 8. Worked Examples

### 8.1 Basic Allocation in Quarter-Comma Meantone

Consider a Lower Zone with 7 Member Channels (Channels 2–8), configured with a quarter-comma meantone Tuning. The Pitch Class Group has 5 channels and the Expression Group has 2 channels (per the formula for `n = 7`).

1. **Note C4 arrives**: Pitch Class Group has unoccupied channels and no channel holds pitch class C. Assign to Channel 2, Pitch Class Group. Output Pitch Bend encodes the meantone offset for C.

2. **Note E4 arrives**: Pitch Class Group has unoccupied channels and no channel holds pitch class E. Assign to Channel 3, Pitch Class Group. Output Pitch Bend encodes the meantone offset for E.

3. **Note G4 arrives**: Assign to Channel 4, Pitch Class Group. Output Pitch Bend encodes the meantone offset for G.

4. **Second C4 arrives** (e.g., re-articulated while the first is sustained): Pitch class C is already in the Pitch Class Group (Channel 2). Assign to Channel 6, Expression Group. Both channels output the meantone offset for C; expressive bends are independent.

5. **Performer bends the second C4 upward**: Only Channel 6's Pitch Bend is affected. Channel 2's Pitch Bend remains at the pure meantone offset for C, preserving the first note's intonation.

### 8.2 Tuning Change During Performance

Continuing from the previous example, the performer switches from quarter-comma meantone to Pythagorean tuning. The MPE Tuner:

1. Updates the tuning offsets for all 12 pitch classes.
2. Recomputes and sends Pitch Bend on Channel 2 (pitch class C, new Pythagorean offset).
3. Recomputes and sends Pitch Bend on Channel 3 (pitch class E, new Pythagorean offset).
4. Recomputes and sends Pitch Bend on Channel 4 (pitch class G, new Pythagorean offset).
5. Recomputes and sends Pitch Bend on Channel 6 (pitch class C, new Pythagorean offset + current expressive bend of the bent note).

All retuning occurs instantaneously and correctly because each channel corresponds to exactly one pitch class.

### 8.3 Note Dropping Under Channel Exhaustion

Consider a Zone with 3 Member Channels (`n = 3`, Pitch Class Group = 1, Expression Group = 2). Notes on pitch classes C, E, and G are active on Channels 2, 3, and 4 respectively. A new note on pitch class A arrives:

1. The Pitch Class Group (1 channel) is occupied by pitch class C. Pitch class A is not represented—it needs a Pitch Class Group channel.
2. No unoccupied channels are available.
3. The Tuner must free a channel. The highest note (G) and lowest note (C) are excluded. The remaining candidate is Channel 3 (pitch class E).
4. Channel 3 is freed (Note Off sent for E). The new A note is assigned to Channel 3 with the tuning offset for A.

---

## 9. Summary

The MPE Tuner provides a mechanism for applying microtonal tunings to polyphonic MIDI streams using the MPE protocol, with real-time tuning changes as a core capability for supporting complex tuning systems that exceed twelve pitches per octave. Its design makes a deliberate trade-off: **intonation precision takes precedence over maximizing polyphony and per-note expressive independence**. This trade-off is realized through three key design decisions:

1. **The pitch-class invariant**: All notes on a shared Member Channel must belong to the same pitch class, ensuring that a single Pitch Bend value correctly intones all notes on the channel and enabling instantaneous retuning.

2. **Dual-group channel partitioning**: The Pitch Class Group guarantees that distinct pitch classes receive independent channels, while the Expression Group accommodates duplicate pitch classes with independent expressive bends.

3. **Controlled note dropping**: When channel resources are insufficient, notes are dropped according to well-defined criteria that minimize perceptual disruption, preserving the boundary notes of the texture and favoring the removal of older, less salient notes.

These design decisions depart from certain recommendations of the MPE Specification—most notably, the preference for assigning each new note to its own channel and the reluctance to drop notes. However, they remain within the MPE Specification's framework and produce output that any conformant MPE receiver can interpret correctly. The resulting system enables performers to play in arbitrary microtonal tunings with real-time retuning capability, using any MPE-compatible instrument, with polyphonic expression constrained only by the inherent limitations of the channel allocation strategy.

---

## References

[1] MIDI Manufacturers Association, "MIDI Polyphonic Expression (MPE)," Recommended Practice RP-053, Version 1.0, March 12, 2018.

---

## Appendix A: Channel Group Allocation Table

| Member Channels (`n`) | Pitch Class Group size (`a`) | Expression Group size (`b`) | Formula |
|---|---|---|---|
| 15 | 12 | 3 | b = 3, a = n − b |
| 14 | 11 | 3 | b = 3, a = n − b |
| 13 | 10 | 3 | b = 3, a = n − b |
| 12 | 9 | 3 | b = 3, a = n − b |
| 11 | 8 | 3 | b = 3, a = n − b |
| 10 | 7 | 3 | b = 3, a = n − b |
| 9 | 7 | 2 | b = 2, a = n − b |
| 8 | 6 | 2 | b = 2, a = n − b |
| 7 | 5 | 2 | b = 2, a = n − b |
| 6 | 4 | 2 | b = 2, a = n − b |
| 5 | 3 | 2 | b = 2, a = n − b |
| 4 | 2 | 2 | b = 2, a = n − b |
| 3 | 1 | 2 | b = 2, a = n − b |
| 2 | 1 | 1 | explicit |
| 1 | 1 | 0 | explicit |

## Appendix B: Decision Flowchart for Note Allocation

```
New note arrives with pitch class P
│
├─ Does the Pitch Class Group have an unoccupied channel
│  AND no occupied Pitch Class Group channel holds P?
│   ├─ YES → Assign to unoccupied channel (Pitch Class Group)
│   └─ NO (P is already in Pitch Class Group OR Pitch Class Group is full):
│       Is there an unoccupied channel in the Expression Group?
│       ├─ YES → Assign to unoccupied channel (Expression Group)
│       └─ NO → Is there any occupied channel with pitch class P?
│           ├─ YES → Assign to that channel (prefer lowest note count, then oldest)
│           └─ NO → Free a channel (Section 5.1), then assign
│
├─ Apply high-expression-bend rules (Section 5.2)
│
└─ Compute and send Pitch Bend, CC #74, Channel Pressure, then Note On
```
