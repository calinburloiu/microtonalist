# Architecture

This directory documents the architecture of Microtonalist. It is consumed by both humans and coding agents. Each
module (an SBT project, see `build.sbt`) gets its own subdirectory here, and each module's `CLAUDE.md` `@import`s its
architecture document so the relevant context loads on demand when an agent works in that module.

> **Status:** Most per-module documents below are placeholders to be filled in. Until they are written, the
> authoritative architecture overview (module dependency graph, key domain concepts, threading model, data flow) lives
> in the "Architecture" section of the root [`CLAUDE.md`](../../CLAUDE.md) / [`AGENTS.md`](../../AGENTS.md).

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
