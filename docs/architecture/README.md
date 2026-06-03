# Architecture

This directory documents the architecture of Microtonalist. It is consumed by both humans and coding agents. Each
module (an SBT project, see `build.sbt`) gets its own subdirectory here, and each module's `CLAUDE.md` `@import`s its
architecture document so the relevant context loads on demand when an agent works in that module.

Start with the cross-cutting overview below, then follow the module index into whichever module(s) a task touches. The
three overview documents are the single source of truth for the basics; coding agents get them always-loaded because
[`../agents/architecture.md`](../agents/architecture.md) (imported by the root [`CLAUDE.md`](../../CLAUDE.md) /
[`AGENTS.md`](../../AGENTS.md)) `@import`s them and adds agent-specific guidance on top.

Some documents note that an area is *subject to change* under the GitHub
[`Architecture`](https://github.com/calinburloiu/microtonalist/milestone/13) milestone (e.g. the Swing→JavaFX GUI
migration, the module-wiring/dependency-injection refactor, and the businessync threading/sequential-consistency work).
Those notes are forward-looking; the surrounding text describes the code as it is today.

## Architecture overview

- [`module-overview.md`](module-overview.md) — the layered module structure and dependency graph.
- [`domain-concepts.md`](domain-concepts.md) — the key domain types (intervals, scales, compositions, tunings, tuners)
  and the plugin pattern, grouped by module.
- [`data-flow.md`](data-flow.md) — how a composition file becomes tuning MIDI messages, end to end.

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
