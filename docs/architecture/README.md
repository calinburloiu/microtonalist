# Architecture

This directory documents the architecture of Microtonalist that spans modules. Each module (an SBT project, see
`build.sbt`) documents its own architecture in `<module>/docs/ARCHITECTURE.md`. Next to it, the module's `README.md`
links to it for human readers, and the module's `CLAUDE.md` `@import`s it so the relevant context loads on demand when
an agent works in that module. All of them are consumed by both humans and coding agents.

Start with the cross-cutting overview below, then follow the module index into whichever module(s) a task touches. The
three overview documents are the single source of truth for the basics; coding agents get them always-loaded because
the root [`AGENTS.md`](../../AGENTS.md) (which [`CLAUDE.md`](../../CLAUDE.md) is a symlink to) `@import`s all three in
its *Architecture* section and adds agent-specific guidance on top.

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

By build convention each module's SBT project ID equals its base directory name (`build.sbt` sets `.withId(<dir>)`).

| Module | Architecture doc |
| ------ | ---------------- |
| `app` | [app/docs/ARCHITECTURE.md](../../app/docs/ARCHITECTURE.md) |
| `config` | [config/docs/ARCHITECTURE.md](../../config/docs/ARCHITECTURE.md) |
| `cli` | [cli/docs/ARCHITECTURE.md](../../cli/docs/ARCHITECTURE.md) |
| `ui` | [ui/docs/ARCHITECTURE.md](../../ui/docs/ARCHITECTURE.md) |
| `common` | [common/docs/ARCHITECTURE.md](../../common/docs/ARCHITECTURE.md) |
| `businessync` | [businessync/docs/ARCHITECTURE.md](../../businessync/docs/ARCHITECTURE.md) |
| `composition` | [composition/docs/ARCHITECTURE.md](../../composition/docs/ARCHITECTURE.md) |
| `tuner` | [tuner/docs/ARCHITECTURE.md](../../tuner/docs/ARCHITECTURE.md) |
| `rig` | [rig/docs/ARCHITECTURE.md](../../rig/docs/ARCHITECTURE.md) |
| `format` | [format/docs/ARCHITECTURE.md](../../format/docs/ARCHITECTURE.md) |
| `intonation` | [intonation/docs/ARCHITECTURE.md](../../intonation/docs/ARCHITECTURE.md) |
| `sc-midi` | [sc-midi/docs/ARCHITECTURE.md](../../sc-midi/docs/ARCHITECTURE.md) |
| `experiments` | [experiments/docs/ARCHITECTURE.md](../../experiments/docs/ARCHITECTURE.md) |

## Other architecture material

- [`midi-device-lifecycle.md`](midi-device-lifecycle.md) — the *available*/*unavailable*, *open*/*closed* and
  *attached*/*detached* vocabulary of a MIDI device and of a track input/output, and how the three relate.
- [`tuner/docs/mpe-tuner-paper.md`](../../tuner/docs/mpe-tuner-paper.md) — MPE tuner design paper.
