# `theory` module group

Music theory as pure domain logic: interval math, and the composition model that turns scales into tunings.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`intonation`](intonation/docs/ARCHITECTURE.md) | Intervals, scales and intonation standards: the math of microtonal pitch | `org.calinburloiu.music.intonation` |
| [`composition`](composition/docs/ARCHITECTURE.md) | A composition, resolved into a `TuningList` by the `TuningMapper` and `TuningReducer` plugins | `org.calinburloiu.music.microtonalist.composition` |
