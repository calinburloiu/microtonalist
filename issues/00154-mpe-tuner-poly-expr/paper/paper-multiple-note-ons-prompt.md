You are an expert in MIDI and your task is to update the MPE Tuner paper, @docs/architecture/tuner/mpe-tuner-paper.md , with details about the cases when Note On messages are emitted for already active notes. Use /superpowers:brainstorming.

Do not look for the implementation in this repository, it is work in progress and not updated with the paper yet. We are only updating the paper in this session.

There are two cases when Note On messages are emitted for already active notes:

1. Note On messages coming from the same input channel
2. Note On messages coming from different input channels, but allocated to the same output Member Channel

This cases are detailed in the next sections. You can also find a section with citations from the MIDI 1.0 specification. Use those that you find appropriate, I just put them there to help you.

TODO:
- Note identify: input channel + note number
- Aggregation model: by note identify, overrides in case 1.
- Allocation: reference counting by note identify to forward the correct number of Note Offs.

## Note Identify

Internally, in MPE Tuner's allocation algorithm and aggregation model, a note is identified by its incoming channel number and its note number.

This has two implications:
- **On aggregation**, duplicates with the same note identify are merged into a single note with the latest Expression Values. Averaging is only concerned with the input channel component of the note identity, because it's performed based on the Expression Values coming from different input channel. Discriminating duplicates further and not merging them, does not make sense as far as the average is concerned.
- For **the allocation algorithm** it is important to know how many duplicates are per note identity because the MIDI 1.0 specification mandates that for any Note On there should be a Note Off. So reference counting per note identify needs to be performed which is incremented on Note On and decremented on Note Off. In this way, we avoid a reallocation - we only allocate when reference count increases from 0 to 1 -, and on Note Off, we don't deallocate the note until reference reaches 0. Not deallocating prematurely is important because after the first Note Off we still want to know to what channel that note and Expression Values need to be forwarded for the future Note Off messages. Additionally, the averaging is kept until all Note Offs arrived.

## 1. Note On messages coming from the same input channel

A Note On is emitted and without it being followed by a corresponding Note Off, one or more Note On messages on the same input channel with the same note numbers are emitted. All these duplicates with the same identify.

This case preserves player's intention to mark a note as active more than one time. Some synthesizers support this and emit multiple sounds for this, others re-emit the note (as if a Note Off / Note On pair is received).

How the MPE Tuner handles this:

- With respect to the aggregation model from Section 7.1, if a note is already active, the new Note On with the same identity will override the Expression Values for that note with the newest values coming from the same input channel. The average computed based on them might be updated as a result of that and in that case their control dimensions will be emitted to the same allocated output Member Channel.
- A Note On for an already active note from an input channel will be forwarded to the same output channel where it was already allocated. In this way, player's intention is preserved. Reference counting is incremented for that note identity.
- A Note Off for an already active note from an input channel will be forwarded to the same output channel where it was allocated. Reference counting is decremented for that note identity and if it reaches 0, the note is deallocated.

## 2. Note On messages coming from different input channels, but allocated to the same output Member Channel

This is an edge case, that should rarely happen in practice which surfaces a limitation of the MPE Tuner paper.

W.r.t. note identify, each Note On messages with the same note number coming from different input channels, has a different note identity, discriminated by input channel.

We decided not to avoid it or attenuate its effect. The problem with this issues is that is does not totally preserve player's intention. A player emits the same Note On message from different input channels and the allocation algorithm happens to allocate them sometimes to the same Output Member Channel. So two rare events should compound: the player to emit the notes in that way and the allocation algorithm to actually send to the same output channel. When this happens, the output device might not treat duplicated notes on the same channel correctly. But the good news is that each note gets its own share in the aggregation model.

How the MPE Tuner handles this:

- With respect to the aggregation model from Section 7.1, each note gets its own share and for each Expression Value, there is different item to average for each duplicated note that has a different identify.
- No reference counting greater than 1 happens, since those notes have different identifies.

## MIDI 1.0 citations: repeated Note On for an already-active note

Research for the MPE Tuner paper update on Note On messages emitted for already-active
notes (`issues/00154-mpe-tuner-poly-expr/paper/paper-multiple-note-ons-prompt.md`).

Source: `/Users/calinburloiu/Downloads/complete_midi_96-1-3.pdf` — *The Complete MIDI 1.0
Detailed Specification*, Document Version 96.1, Third Edition. Citations use the paper's
notation `[2, p. N]` with the specification's internal pagination.

### Pagination caveat (important)

The main body follows the known mapping **internal page N = PDF page N + 32**.

The appendix *"Additional Explanations and Application Notes"* uses its own **A-N**
pagination (PDF p. 91 = printed "A-1", confirmed from the running header), so the `N + 32`
rule does **not** apply there. Those passages are cited as `[2, p. A-4]`. If any of them are
used, reference [2] in the paper may need a word about appendix pagination, since it
currently says only "Page references follow the internal pagination of the MIDI 1.0
Detailed Specification."

All page numbers below were verified page-by-page against the printed running headers.

### Scope note

This PDF (the 96.1 third-edition compilation) contains **no MPE / RP-053 text** — searches
for "Polyphonic Expression", "MPE", and "RP-053" found nothing. RP-053 (2018) postdates the
compilation, so reference [1] cannot be sourced from this file.

---

### 1. Repeated Note On for an already-sounding note (same channel, same note number)

**Most direct hit.** From "Additional Explanations and Application Notes", section
ASSIGNMENT OF NOTE ON/OFF COMMANDS:

> "If an instrument receives two or more Note On messages with the same key number and MIDI
> channel, it must make a determination of how to handle the additional Note Ons. It is up
> to the receiver as to whether the same voice or another voice will be sounded, or if the
> messages will be ignored."

`[2, p. A-4]` — Establishes that the case is legal and that receiver behavior
(retrigger / layer / ignore) is explicitly left undefined by the specification.

Related, but scoped to Omni-Off/Mono (one voice per channel) rather than the general case:

> "Should more than one Note-On message be sent for a given channel, the receivers response
> is not specified."

`[2, p. 22]` — Same "unspecified" framing, but Mono-mode-specific.

Tangential — the spec contemplates a second Note On before the first Note Off, though for a
*different* key number (Mono-mode legato), not the same note number retriggering:

> "If a Note-On is received, and then a second Note-On received without the first Note-Off
> being received, then the receiving instrument should change pitch to the new note, but not
> restart the envelopes (they should continue as if the same note was still being held)."

`[2, p. 22]`

### 2. Matching Note Offs to Note Ons

> "The transmitter, however, must send a corresponding Note Off message for every Note On
> sent. If the transmitter were to send only one Note Off message, and if the receiver in
> fact assigned the two Note On messages to different voices, then one note would linger.
> Since there is no harm or negative side effect in sending redundant Note Off messages this
> is the recommended practice."

`[2, p. A-4]` — The transmitter obligation is **per Note On, not per note number**, and
redundant Note Offs are explicitly endorsed. Directly relevant to the Tuner as a
transmitter on its output side.

> "The correct procedure of sending a Note-Off for each and every Note-On must still be
> followed."

`[2, p. 25]` — General statement of the one-Note-Off-per-Note-On rule, in the ALL SOUND OFF
discussion. (The paper already cites `[2, pp. 24–25]` nearby, in Section 3.5.)

> "MIDI rules still apply - a Note-Off must eventually be sent for every note."

`[2, p. 22]` — Same rule, in the Mono-mode legato discussion.

> "Nor is it a replacement for sending Note-Offs for every Note-On sent."

`[2, p. 14]` — Same rule again, in the Legato Footswitch note.

**Negative result:** no text was found stating that a single Note Off turns off *all*
instances of a repeated note number. The spec's only guidance places the burden on the
transmitter to send matching Note Offs, precisely to avoid that ambiguity.

### 3. Voice assignment / note stealing / polyphony limits

> "In Poly mode there are no particular rules which define how to assign voices when more
> than one Note On message is received and recognized. If more Note On messages are
> transmitted than the receiver is capable of playing, the receiver is free to use any
> method of dealing with this 'overflow' situation (such as first vs. last note priority)."

`[2, p. A-4]` — Confirms there is no spec-mandated voice-stealing algorithm; it is
receiver-defined. The Q1 passage above (`[2, p. A-4]`, "same voice or another voice") also
bears on this.

Out of scope for `[2, …]` but noted: the bundled **GM Level 1 Developer Guidelines, Second
Revision** (a distinct document with its own "Page N" numbering, PDF p. 317) discusses
voice-stealing / oscillator-stealing priority for General MIDI. It would need its own
reference entry if cited.

### 4. Same note number active simultaneously on the same channel vs. different channels

**No supporting text found.** Targeted searches ("on a different channel", "on different
channels", "same note on two/different") returned nothing.

The closest material is the general Poly-mode description — "those notes will be played
simultaneously to the limit of the receiver's number of voices" `[2, p. 21]` — and the
Omni-Off/Mono one-voice-per-channel rule `[2, p. 22]`. Neither compares same-channel against
cross-channel duplicate note numbers, so neither should be cited as answering this question
without acknowledging the gap.

---

### Bearing on the two cases in the prompt

- **Case 1 (same input channel).** `[2, p. A-4]` legitimizes the case and confirms that
  "some synthesizers support this and emit multiple sounds for this" is the spec's own
  expectation, not an assumption. The per-Note-On Note Off obligation at `[2, p. A-4]` and
  `[2, p. 25]` constrains what the Tuner must emit on its output as a transmitter.
- **Case 2 (different input channels, same output Member Channel).** The A-4 passage shows
  the downstream ambiguity is real: a receiver may assign the two Note Ons to different
  voices, or to the same one, or ignore the second. This supports framing Case 2 as an
  acknowledged limitation rather than a defect with a defined remedy.

