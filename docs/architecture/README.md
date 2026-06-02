# Architecture

This directory documents the architecture of Microtonalist. It is consumed by both humans and coding agents. Each
module (an SBT project, see `build.sbt`) gets its own subdirectory here, and each module's `CLAUDE.md` `@import`s its
architecture document so the relevant context loads on demand when an agent works in that module.

A concise, always-in-context overview — the module dependency graph, the key domain concepts, and the end-to-end data
flow — is maintained for coding agents in [`../agents/architecture.md`](../agents/architecture.md) and imported by the
root [`CLAUDE.md`](../../CLAUDE.md) / [`AGENTS.md`](../../AGENTS.md). This directory holds the deeper, per-module detail:
start with the overview, then follow the module index below into whichever module(s) a task touches.

Some documents note that an area is *subject to change* under the GitHub
[`Architecture`](https://github.com/calinburloiu/microtonalist/milestone/13) milestone (e.g. the Swing→JavaFX GUI
migration, the module-wiring/dependency-injection refactor, and the businessync threading/sequential-consistency work).
Those notes are forward-looking; the surrounding text describes the code as it is today.

## Module dependency overview

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

## Module index

| Module | Directory | Architecture doc |
| ------ | --------- | ---------------- |
| `app` | `app` | [app/README.md](app/README.md) |
| `appConfig` | `config` | [config/README.md](config/README.md) |
| `cli` | `cli` | [cli/README.md](cli/README.md) |
| `ui` | `ui` | [ui/README.md](ui/README.md) |
| `common` | `common` | [common/README.md](common/README.md) |
| `businessync` | `businessync` | [businessync/README.md](businessync/README.md) |
| `composition` | `composition` | [composition/README.md](composition/README.md) |
| `tuner` | `tuner` | [tuner/README.md](tuner/README.md) |
| `format` | `format` | [format/README.md](format/README.md) |
| `intonation` | `intonation` | [intonation/README.md](intonation/README.md) |
| `scMidi` | `sc-midi` | [sc-midi/README.md](sc-midi/README.md) |
| `experiments` | `experiments` | [experiments/README.md](experiments/README.md) |

## Other architecture material

- [`tuner/mpe-spec.md`](tuner/mpe-spec.md) — MPE specification notes.
- [`tuner/mpe-tuner-paper.md`](tuner/mpe-tuner-paper.md) — MPE tuner design paper.
