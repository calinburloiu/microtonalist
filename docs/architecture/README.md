# Architecture

This directory documents what spans module groups; see the
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

- [`module-overview.md`](module-overview.md) — the module groups, the main modules and their dependencies.
- [`domain-concepts.md`](domain-concepts.md) — the key domain types (intervals, scales, compositions, tunings, tuners)
  and the plugin pattern, grouped by module.
- [`data-flow.md`](data-flow.md) — how a composition file becomes tuning MIDI messages, end to end.

## Module index

The [*Module groups*](module-overview.md#module-groups) table of the module overview links the `README.md` of each
module group, which lists the group's modules and links the architecture doc of each,
`<group>/<module>/docs/ARCHITECTURE.md`.

## Other architecture material

- [`midi-device-lifecycle.md`](midi-device-lifecycle.md) — the *available*/*unavailable*, *open*/*closed* and
  *attached*/*detached* vocabulary of a MIDI device and of a track input/output, and how the three relate.
- [`tuner/tuner-mpe/docs/mpe-tuner-paper.md`](../../tuner/tuner-mpe/docs/mpe-tuner-paper.md) — MPE tuner design paper.
