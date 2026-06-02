# Data flow

How a composition file becomes tuning MIDI messages, end to end:

```
JSON composition file
  → DefaultCompositionRepo (format module)
  → Composition
  → TuningList.fromComposition()   (applies TuningMapper, TuningReducer, fill)
  → TuningSession.tunings
  → TuningIndexUpdatedEvent (via Businessync)
  → TrackManager → Track.tune()
  → TunerProcessor → Tuner
  → MTS/MPE MIDI messages
  → MIDI output device
```

> **More detail:** the threading model behind this flow is documented in [`businessync/`](businessync/README.md);
> serialization in [`format/`](format/README.md); the startup wiring that builds this pipeline in
> [`app/`](app/README.md).
