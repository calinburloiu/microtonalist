# Architecture

This directory documents what spans modules and indexes the architecture doc of each module,
`<group>/<module>/docs/ARCHITECTURE.md`; see the
[`architecture-docs` skill](../../.claude/skills/architecture-docs/SKILL.md) for where each kind of doc lives.

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

Each module lives in a grouping folder, `<group>/<module>/`, and by build convention its SBT project ID equals the name
of its own folder (`build.sbt` sets `.withId(<dir>)`), so a module's doc is at `<group>/<id>/docs/ARCHITECTURE.md`.

| Module | Architecture doc |
| ------ | ---------------- |
| `app` | [app/app/docs/ARCHITECTURE.md](../../app/app/docs/ARCHITECTURE.md) |
| `config` | [app/config/docs/ARCHITECTURE.md](../../app/config/docs/ARCHITECTURE.md) |
| `cli` | [app/cli/docs/ARCHITECTURE.md](../../app/cli/docs/ARCHITECTURE.md) |
| `ui` | [ui/ui/docs/ARCHITECTURE.md](../../ui/ui/docs/ARCHITECTURE.md) |
| `common` | [base/common/docs/ARCHITECTURE.md](../../base/common/docs/ARCHITECTURE.md) |
| `businessync` | [base/businessync/docs/ARCHITECTURE.md](../../base/businessync/docs/ARCHITECTURE.md) |
| `composition` | [theory/composition/docs/ARCHITECTURE.md](../../theory/composition/docs/ARCHITECTURE.md) |
| `tuner` | [tuner/tuner/docs/ARCHITECTURE.md](../../tuner/tuner/docs/ARCHITECTURE.md) |
| `rig` | [rig/rig/docs/ARCHITECTURE.md](../../rig/rig/docs/ARCHITECTURE.md) |
| `format` | [format/format/docs/ARCHITECTURE.md](../../format/format/docs/ARCHITECTURE.md) |
| `intonation` | [theory/intonation/docs/ARCHITECTURE.md](../../theory/intonation/docs/ARCHITECTURE.md) |
| `sc-midi` | [midi/sc-midi/docs/ARCHITECTURE.md](../../midi/sc-midi/docs/ARCHITECTURE.md) |
| `experiments` | [app/experiments/docs/ARCHITECTURE.md](../../app/experiments/docs/ARCHITECTURE.md) |

## Other architecture material

- [`midi-device-lifecycle.md`](midi-device-lifecycle.md) — the *available*/*unavailable*, *open*/*closed* and
  *attached*/*detached* vocabulary of a MIDI device and of a track input/output, and how the three relate.
- [`tuner/tuner/docs/mpe-tuner-paper.md`](../../tuner/tuner/docs/mpe-tuner-paper.md) — MPE tuner design paper.
