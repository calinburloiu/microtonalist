# `rig` module group

The rig: the setup of tracks a performer plays, one per instrument.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`rig`](rig/docs/ARCHITECTURE.md) | Tracks, which route MIDI from an input to an output through a tuning changer and a tuner, and the rig session behind a thread-safe service | `org.calinburloiu.music.microtonalist.rig` |
| [`rig-format`](rig-format/docs/ARCHITECTURE.md) | The JSON format of rigs (`.mtlist.tracks`) and of their track I/O plugins, and the repositories that read them | `org.calinburloiu.music.microtonalist.rig.format` |
