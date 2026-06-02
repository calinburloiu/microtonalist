# Architecture (Agents)

This is the crucial, always-loaded architecture overview for coding agents. It is `@import`ed by the root
[`AGENTS.md`](../../AGENTS.md) / `CLAUDE.md` so it loads in every session. Deeper, context-specific detail lives in
the per-module and per-topic documents under [`docs/architecture/`](../architecture/) and is loaded on demand.

## How architecture docs are organized

- **This file (`docs/agents/architecture.md`)** — the always-in-context overview. It adds agent-specific guidance (how
  the docs are organized and what to read before coding), then `@import`s the three shared overview documents below so
  they stay always-loaded for agents. It holds no architecture content of its own.
- **Shared overview documents (`docs/architecture/`)** — the single source of truth for the cross-cutting basics,
  imported here and linked from the human-facing index:
  [`module-overview.md`](../architecture/module-overview.md) (the module dependency graph),
  [`domain-concepts.md`](../architecture/domain-concepts.md) (the key domain types), and
  [`data-flow.md`](../architecture/data-flow.md) (the end-to-end data flow). Edit these there, not here.
- **Per-module deep dives (`docs/architecture/$MODULE/README.md`)** — one document per SBT module (an architecture
  document covering responsibility, key types, dependencies, and module-specific concerns). Each module's
  `$MODULE/CLAUDE.md` `@import`s its document, so when you work inside a module that detail loads automatically. To read
  another module's architecture without editing in it, open its `docs/architecture/$MODULE/README.md` directly.
- **Per-topic documents** — some directories carry focused topic docs alongside the module README, e.g.
  [`docs/architecture/tuner/mpe-spec.md`](../architecture/tuner/mpe-spec.md) and
  [`docs/architecture/tuner/mpe-tuner-paper.md`](../architecture/tuner/mpe-tuner-paper.md) for MPE tuning.
- **Human-facing index (`docs/architecture/README.md`)** — the same material framed for human readers, with a table
  mapping each module to its directory and document.

**Before coding**, identify which architecture documents are strictly relevant to the task (this overview plus the
README(s) of the module(s) you will touch and their immediate collaborators) and read them. Architecture docs may note
that an area is *subject to change* under the GitHub `Architecture` milestone; treat those notes as forward-looking, not
as the current state of the code.

## Always-loaded overview

The module dependency graph, the key domain concepts, and the end-to-end data flow are imported below so they remain in
context for every session. They are maintained under [`docs/architecture/`](../architecture/) as the single source of
truth — humans read them via the [index](../architecture/README.md); edit them there, not here.

@../architecture/module-overview.md
@../architecture/domain-concepts.md
@../architecture/data-flow.md
