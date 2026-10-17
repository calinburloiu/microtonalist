# Data flow

How a composition file becomes tuning MIDI messages, end to end:

```
JSON composition file
  → DefaultCompositionRepo (composition-format module)
  → Composition
  → TuningList.fromComposition()   (applies TuningMapper, TuningReducer, fill)
  → TuningSession.tunings          (tuner module)
  → TuningIndexUpdatedEvent (via Businessync)
  → RigManager → Track.tune()      (rig module)
  → TunerProcessor → Tuner
  → MTS/MPE MIDI messages
  → MIDI output device
```

> **More detail:** the threading model behind this flow is documented in
> [`businessync`](../../base/businessync/docs/ARCHITECTURE.md); the rig in
> [`rig`](../../rig/rig/docs/ARCHITECTURE.md); serialization in [`format`](../../format/format/docs/ARCHITECTURE.md);
> the startup wiring that builds this pipeline in [`app`](../../app/app/docs/ARCHITECTURE.md).
