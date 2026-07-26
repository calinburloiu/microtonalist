# Update Paper with Polyphonic Expression for MPE Tuner (Prompt)

Your task is to update and refactor the incomplete MPE Tuner implementation (from `tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`) according to the MPE Tuner paper (from `docs/architecture/tuner/mpe-tuner-paper.md`). For reference, here is the MPE Specification on which the MPE Tuner is based: `docs/architecture/tuner/mpe-spec.md`.

## TODO for this prompt

* [ ] Check TODOs

## 1. Missing or incomplete functionality

* Resolve TODO: "Warn when MpeTuner is configured with non-MPE input mode while both zones are enabled".

Input-mode-specific behavior changes are described in the next sections.

### 1.1. MPE Input Mode

* (a) **Expression Value Processing**. Any incoming note has an Expression Value for each of the three control dimensions (Expression Pitch Bend, Channel Pressure and CC #74), see paper subsection 1.3. These values are taken from the input Member Channel. Multiple notes may come from the same input channel and in this case, all have the same Expression Values. Due to the specific rules of the MPE Tuner, incoming notes from the same input channel may be mapped to different output channels if they have different pitch classes. Multiple notes, with the same pitch class may be mapped to the same output channel when they come from different input channels. Each Expression Value for a control dimension on an output channel is computed as the average of the incoming notes values for that control dimension (see paper section 7). Remember that the final Pitch Bend for an output channel is computed as the sum of the Expression Pitch Bend (which is the average of the incoming notes Pitch Bend) and the Tuning Pitch Bend for that output channel (which is the tuning offset of the channel's pitch class).
    - TODO
* (b) **Omit unchanged control dimensions**. An MPE Tuner implementation is not required to emit all three control dimensions before a Note On. It may choose to optimize by only emitting those that changed.
* (c) **Empty channels keep the last values of the control dimensions**. Averaging of the Expression Values for the control dimensions is only used when there is at least one note on an output channel. But when the channel becomes empty, it will preserve its latest Expression Values. This not only helps with the mentioned optimization of only emitting control dimensions when they change, but it also avoids a division by zero if we would calculate the average for an Expression Value for zero incoming notes.
* (d) **Input channels are tracked for control dimensions**. Even if there are no active notes on an input Member Channel, the MPE Tuner must remember the values of the three control dimensions received on that channel such that when a new note arrives it will be initialized with those control dimension values as Expression Values. Remember that the incoming note Expression Values must be averaged on the output channel. When there are no active notes on an input Member Channel, there is no need to emit control dimensions to the output Member Channels.
    - `ScMidiChannelStateTracker` is already used in `MpeTuner` to track the state of the input channels. When a note is allocated, it may pull the latest Expression Value from `ScMidiChannelStateTracker`.
* (e) **Fan-out control dimension updates**. If there are active notes on an input channel, when a control dimension update is received on that channel, for each active note, update that note's contribution to the average of the Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

### 1.2. Non-MPE Input Mode

* (a) In Non-MPE Input Mode only Pitch Bend and Channel Pressure control dimensions are used on an output Member Channel. From these, only Channel Pressure can be used as an Expression Value. Pitch Bend is only used for tuning (the Tuning Pitch Bend component), there is no per-channel Expression Pitch Bend. Pitch Bend messages received on an input channel are forwarded to the Master Channel and applies to all channels. CC #74 does not appear on an output channel and if it's received on an input channel it's forwarded on the output Master Channel.
* (b) A Channel Pressure from an output Member Channel always originates from a converted Polyphonic Key Pressure received on an input channel. A Channel Pressure received on an input channel is always forwarded to the Master Channel.
* (c) A Polyphonic Key Pressure received from an input channel is assumed to be applied to an active note. If one is received on a note for which a Note On was not issued on that input channel, it's ignored. So a Polyphonic Key Pressure always has value 0 for a note at the time a Note On message is issued for it.
* (d) When there are multiple active notes on an output Member Channel, their Channel Pressure Expression Value (that originates from a Polyphonic Key Pressure) is averaged, similar with MPE Input Mode, but dissimilar to it, preserving its latest value on Note Off does not have any effect since on the next Note On on that channel, the value must be reset to 0. Remember that it must be reset because it originates from a Polyphonic Key Pressure which always has a 0 value on Note On.
* (e) When a Polyphonic Key Pressure update is received on an input channel, update that note's contribution to the average of the output Channel Pressure Expression Values. Each time the average Expression Value for an output channel changes, it needs to be sent to the output channel.

## 2. Implementation details
