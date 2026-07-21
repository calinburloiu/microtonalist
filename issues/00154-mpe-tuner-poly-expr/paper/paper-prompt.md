# Update Paper with Polyphonic Expression for MPE Tuner (Prompt)

Your task is to update the MPE Tuner paper, @docs/architecture/tuner/mpe-tuner-paper.md , with new requirements. For reference, here is the MPE Specification on which the MPE Tuner is based: @docs/architecture/tuner/mpe-spec.md . Don't read the current implementation of the MPE Tuner from `MpeTuner` class. Both the paper and the implementation are work in progress. In this task we are focusing on updating the paper with new findings. Updating the implementation is out of scope.

Maintain a technical academic tone for the paper.

Don't modify committed Scala files from this project or any other committed source code file. During this task you should only update markdown files. You may write temporary throw-away code if you need to accomplish the task.

Use /superpowers:brainstorming. Write the design document in `issues/00154-mpe-tuner-poly-expr/superpowers/`. I don't think it's necessary to also write a plan file, because we are modifying documents and changes can be made in place after the design document is approved.

Sections below are numbered and facts are notated with letters in parenthesis. We can use them for reference when discussing them either in the chat or in the design document. In this way we can make sure nothing is missed.

## 1. Facts to add or update

* (a) We need a section / subsection that formalizes concepts related to control dimensions. Those are already presented in various places of the paper, but in an adhoc fashion. Let's make it official before they are mentioned adhoc. Present the three MPE control dimensions. Define clearly that Pitch Bend is composed as the sum of *Tuning Pitch Bend* and *Expressive Pitch Bend*. The former creates the tuning offset for a pitch class on a particular output Member Channel. The latter is controlled by the player in real-time for expressive purposes. Use a term in general for *Expression Values* which include the CC #74 and Channel Pressure control dimensions and the Expressive Pitch Bend component (without Tuning Pitch Bend). The term for this could be *Expression Value* itself or if you have a suggestion for something else I am open. Like in the MPE specification, let's capitalize all terms like Tuning Pitch Bend, Expressive Pitch Bend, Expression Value etc. (you may recommend other terms that I missed to be capitalized). Terms already capitalized in the MPE specification, should also be capitalized in this paper.
* (b) There needs to be a section that describes Zones for the MPE Tuner:
    - The zone configuration (number of channels for each) is for both input and output in MPE Tuner.
    - In Non-MPE Input Mode, there is no such thing as Zone for the input (obviously), so the zone configuration only affects output. Also, only one zone is accessible from the output in this case. If two are defined, the upper zone is ignored.

Input-mode-specific behavior changes are described in the next sections.

### 1.1. MPE Input Mode

* (a) Any incoming note has an Expression Value for each of the three control dimensions (Expressive Pitch Bend, Channel Pressure and CC #74). These values are taken from the input Member Channel. Multiple notes may come from the same input channel and in this case, all have the same Expression Values. Due to the specific rules of the MPE Tuner, incoming notes from the same input channel may be mapped to different output channels if they have different pitch classes. Multiple notes, with the same pitch class may be mapped to the same output channel when they come from different input channels. Each Expression Value for a control dimension on an output channel is computed as the average of the incoming notes values for that control dimension. Remember that the final Pitch Bend for an output channel is computed as the sum of the Expressive Pitch Bend (which is the average of the incoming notes Pitch Bend) and the Tuning Pitch Bend for that output channel (which is the tuning offset of the channel's pitch class).
* (b) An MPE Tuner implementation is not required to emit all three control dimensions before a Note On. It may choose to optimize by only emitting those that changed. Averaging of the Expression Values for the control dimensions is only used when there is at least one note on an output channel. But when the channel becomes empty, it will preserve its latest Expression Values. This not only helps with the mentioned optimization of only emitting control dimensions when they change, but it also avoids a division by zero if we would calculated the average for an Expression Value for zero incoming notes.
* (c) If there are no active notes on an input Member Channel, the MPE Tuner must remember the values of the three control dimensions received on that channel such that when a new note arrives (and the channel will have exactly one active note) it will be initialized with those control dimension values as Expression Values. Remember that those incoming note Expression Values must be averaged on the output channel.
* (d) If there are active notes on an input channel, when a control dimension update is received on that channel, for each active note, update that note's contribution to the average of the Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

### 1.2. Non-MPE Input Mode

* (a) In Non-MPE Input Mode only Pitch Bend and Channel Pressure control dimensions are used on an output Member Channel. From these, only Channel Pressure can be used as an Expression Value. Pitch Bend is only used for tuning (the Tuning Pitch Bend component), there is no per-channel Expressive Pitch Bend. Pitch Bend messages received on an input channel are forwarded to the Master Channel and applies to all channels. CC #74 does not appear on an output channel and if it's received on an input channel it's forwarded on the output Master Channel.
* (b) A Channel Pressure from an output Member Channel always originates from a converted Polyphonic Key Pressure received on an input channel. A Channel Pressure received on an input channel is always forwarded to the Master Channel.
* (c) A Polyphonic Key Pressure received from an input channel is assumed to be applied to an active note. If one is received on a note for which a Note On was not issued on that input channel, it's ignored. So a Polyphonic Key Pressure always has value 0 for a note at the time a Note On message is issued for it.
* (d) When there are multiple active notes on an output Member Channel, their Channel Pressure Expression Value (that originates from a Polyphonic Key Pressure) is averaged, similar with MPE Input Mode, but dissimilar to it, preserving its latest value on Note Off does not have any effect since on the next Note On on that channel, the value must be reset to 0. Remember that it must be reset because it originates from a Polyphonic Key Pressure which always has a 0 value on Note On.
* (e) When a Polyphonic Key Pressure update is received on an input channel, update that note's contribution to the average of the output Channel Pressure Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

## 2. Miscellaneous updates

* (a) In section 4.2, at the paragraph that defines "Expression Group", also add a phrase  that explains the fact that the Expression Group may provide supplemental channels when the Pitch Class group is full.
* (b) In section 6, some subsections mention MPE features that behave exactly like in the MPE Specification, with no apparent difference. Does it make sense to duplicate that information with so many details? Should we instead just enumerate those features are say that they behave in the same way?
    - The following features behave the same way in the MPE Tuner as in the MPE specification:
        * 6.1 Zone Configuration
        * 6.2 Pitch Bend Sensitivity. This subsection adds things that are not completely accurate. If we remove them, I don't think there is anything specific to be documented for Pitch Bend Sensitivity and it behaves in the same way as in MPE. Things that are not accurate:
            - It seems to suggest that Pitch Bend is only used for tuning, which indeed "rarely exceed ±1 semitone for common temperaments". But Pitch Bend is also used for expression, in which case bends that exceed ±1 semitone are common. So this part is inaccurate and should be removed.
            - It says "Implementations should verify that the receiving instrument's Pitch Bend Sensitivity is configured appropriately". There's no way to do that. MPE Tuner does not take input from the output instrument. This should be removed.
    - My comments on other subsections:
        * 6.3 Message Ordering. We may mention here that an implementation may choose not to output one of these control dimensions if they didn't change on that output channel since last time. Particularly, for Non-MPE Input Mode, there is no reason to emit CC #74 on a Member Channel, because there is no way to control it on Member Channels. It can only be controlled globally on the Master Channel.
* (c) Is Appendix B with its flowchart redundant with the mermaid diagram from section 4.5? If it is, we should remove Appendix B.

Open question for debating together: Does it make sense to move section “Real-Time Tuning Changes” just before “MPE Tuner Output Conformance”? If it does, what changes are required such that the content can still read well without weird forward references.
