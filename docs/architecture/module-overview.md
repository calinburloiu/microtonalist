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

Each module's SBT project ID (equal to its base directory name by build convention; see
[`../development/build.md`](../development/build.md)) and root package:

| SBT project / directory | Root package |
| ----------------------- | ------------ |
| `app` | `org.calinburloiu.music.microtonalist` |
| `cli` | `org.calinburloiu.music.microtonalist.cli` |
| `ui` | `org.calinburloiu.music.microtonalist.ui` |
| `common` | `org.calinburloiu.music.microtonalist.common` |
| `composition` | `org.calinburloiu.music.microtonalist.composition` |
| `config` | `org.calinburloiu.music.microtonalist.config` |
| `tuner` | `org.calinburloiu.music.microtonalist.tuner` |
| `format` | `org.calinburloiu.music.microtonalist.format` |
| `businessync` | `org.calinburloiu.businessync` |
| `intonation` | `org.calinburloiu.music.intonation` |
| `sc-midi` | `org.calinburloiu.music.scmidi` |
| `experiments` | `org.calinburloiu.music.microtonalist.experiments` |
