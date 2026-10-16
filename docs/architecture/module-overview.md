# Module overview

The project uses a layered module structure; dependency direction flows from `app` downward:

```
app
├── ui               (GUI, depends on tuner)
├── composition      (domain model, depends on intonation + tuner)
├── format           (JSON/file I/O, depends on composition + rig + tuner)
├── rig              (setup of per-instrument MIDI tracks, depends on tuner)
├── tuner            (MIDI tuning, depends on sc-midi + businessync)
├── sc-midi-javamidi (Java Sound implementation of sc-midi, with CoreMIDI4J, depends on sc-midi)
├── sc-midi          (Scala-idiomatic MIDI API, no event bus)
├── intonation       (interval math, no application deps)
├── businessync      (event bus + threading, no application deps)
├── common           (shared utilities)
└── config           (HOCON config, depends on common)
```

`cli` is a separate executable (a utility tool, e.g. listing MIDI devices) that depends only on `sc-midi` and
`sc-midi-javamidi`; `experiments` is a separate executable for ad-hoc research studies that depends on `intonation`.

## Packages

Each module lives in `<group>/<id>/`: a grouping folder at the repository root holds the modules of one area and
nothing else, and only `root` stays at the root. Each module's SBT project ID (equal to the name of its own folder by
build convention; see [`../development/build.md`](../development/build.md)), directory and root package:

| SBT project | Directory | Root package |
| ----------- | --------- | ------------ |
| `app` | `app/app` | `org.calinburloiu.music.microtonalist` |
| `cli` | `app/cli` | `org.calinburloiu.music.microtonalist.cli` |
| `ui` | `ui/ui` | `org.calinburloiu.music.microtonalist.ui` |
| `common` | `base/common` | `org.calinburloiu.music.microtonalist.common` |
| `composition` | `theory/composition` | `org.calinburloiu.music.microtonalist.composition` |
| `config` | `app/config` | `org.calinburloiu.music.microtonalist.config` |
| `tuner` | `tuner/tuner` | `org.calinburloiu.music.microtonalist.tuner` |
| `rig` | `rig/rig` | `org.calinburloiu.music.microtonalist.rig` |
| `format` | `format/format` | `org.calinburloiu.music.microtonalist.format` |
| `businessync` | `base/businessync` | `org.calinburloiu.businessync` |
| `intonation` | `theory/intonation` | `org.calinburloiu.music.intonation` |
| `sc-midi` | `midi/sc-midi` | `org.calinburloiu.music.scmidi` |
| `sc-midi-javamidi` | `midi/sc-midi-javamidi` | `org.calinburloiu.music.scmidi.javamidi` |
| `experiments` | `app/experiments` | `org.calinburloiu.music.microtonalist.experiments` |
