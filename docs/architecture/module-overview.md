# Module overview

The project uses a layered module structure; dependency direction flows from `app` downward:

```
app
├── ui            (GUI, depends on tuner)
├── composition   (domain model, depends on intonation + tuner)
├── format        (JSON/file I/O, depends on composition + tuner)
├── tuner         (MIDI tuning, depends on sc-midi + businessync)
├── sc-midi       (Scala-idiomatic MIDI API, depends on businessync)
├── intonation    (interval math, no application deps)
├── businessync   (event bus + threading, no application deps)
├── common        (shared utilities)
└── config        (HOCON config, depends on common)
```

`cli` is a separate executable (a utility tool, e.g. listing MIDI devices) that depends only on `sc-midi`;
`experiments` is a separate executable for ad-hoc research studies that depends on `intonation`.

## Packages

Each module's SBT project name, directory, and root package:

| SBT project | Directory | Root package |
| ----------- | --------- | ------------ |
| `app` | `app` | `org.calinburloiu.music.microtonalist` |
| `cli` | `cli` | `org.calinburloiu.music.microtonalist.cli` |
| `ui` | `ui` | `org.calinburloiu.music.microtonalist.ui` |
| `common` | `common` | `org.calinburloiu.music.microtonalist.common` |
| `composition` | `composition` | `org.calinburloiu.music.microtonalist.composition` |
| `appConfig` | `config` | `org.calinburloiu.music.microtonalist.config` |
| `tuner` | `tuner` | `org.calinburloiu.music.microtonalist.tuner` |
| `format` | `format` | `org.calinburloiu.music.microtonalist.format` |
| `businessync` | `businessync` | `org.calinburloiu.businessync` |
| `intonation` | `intonation` | `org.calinburloiu.music.intonation` |
| `scMidi` | `sc-midi` | `org.calinburloiu.music.scmidi` |
| `experiments` | `experiments` | `org.calinburloiu.music.microtonalist.experiments` |
