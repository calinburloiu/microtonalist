# AGENTS.md / CLAUDE.md

This file provides guidance to coding agents (e.g. Claude Code) when working with code in this repository.

Microtonalist is a microtuner application that allows tuning musical keyboards and synthesizers in real-time for playing
music with microtones. It supports various protocols for tuning output instruments like MIDI Tuning Standard (_MTS_),
_Monophonic Pitch Bend_ and MIDI Polyphonic Expression (_MPE_). Users work with a sequence of _scales_, as a high-level
concept, and the application maps them to octave-based _tunings_, as a low-level concept, which assign a tuning value to
each pitch class of the keyboard.

Microtonalist is built as a stand-alone multi-platform desktop application that runs on JVM. The code is written in
Scala 3 and is built by using sbt 1.

# Architecture

The architecture docs are organized as follows:

- **Shared overview documents (`docs/architecture/`)** — the single source of truth for the cross-cutting basics,
  imported here and linked from the human-facing index:
    * @../architecture/module-overview.md
    * @../architecture/domain-concepts.md
    * @../architecture/data-flow.md
- **Per-module deep dives (`docs/architecture/$MODULE/README.md`)** — one document per module (an architecture document
  covering responsibility, key types, dependencies, and module-specific concerns). Each module's `$MODULE/CLAUDE.md`
  `@import`s its document, so when you work inside a module that detail loads automatically. To read another module's
  architecture without editing in it, open its `docs/architecture/$MODULE/README.md` directly.
- **Per-topic documents** — some directories carry focused topic docs alongside the module README, e.g.
  [`docs/architecture/tuner/mpe-spec.md`](../architecture/tuner/mpe-spec.md) for MPE tuning.
- **Human-facing index (`docs/architecture/README.md`)** — the same material framed for human readers, with a table
  mapping each module to its directory and document.

**Before coding**, identify which architecture documents are strictly relevant to the task (this overview plus the
README(s) of the module(s) you will touch and their immediate collaborators) and read them. Architecture docs may note
that an area is *subject to change* under the GitHub `Architecture` milestone; treat those notes as forward-looking, not
as the current state of the code.

# Contributing (issues, PRs, branches)

GitHub conventions — issues, branches, pull requests, labels, milestones, and the Projects-v2 `gh` fallback — live in
the `contributing` skill (`.claude/skills/contributing/`). Invoke it when creating an issue or opening a PR.

Only load or update files from `issues/` directory when explicitly asked to by the user. You may load or update files linked to them, directly or indirectly. But do not search for other files from that directory to load or update, because they may contain stale docs (plans, design docs, specs) or unrevised reports with incorrect data.

All files from that directory must be dated and linked to a git commit SHA, such that the agent knows if the information inside them is state.
