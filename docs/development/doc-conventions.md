# Documentation Conventions

Conventions for every kind of documentation in this repository: ScalaDoc, code comments, Markdown docs and agent
instructions. Code conventions live in [`coding-conventions.md`](coding-conventions.md).

## General rules

- **Essentials only.** Write what the reader needs, as briefly as a human would write it.
- **Don't restate the code.** Say what the code can't: purpose, contract, constraints, the reason for a non-obvious
  choice. A reader who has the code shouldn't have to read it again in prose.
- **One home per fact.** Each fact lives in the kind of doc the table below gives it. Elsewhere, link to it instead of
  copying it.
- **Describe the current state.** History lives in git, the PRs and the release notes.
- **Cite issue numbers only for open work:** a `TODO #n`, an area that is subject to change, a known limitation.

## Kinds of docs

| Kind | Location | Purpose |
| ---- | -------- | ------- |
| ScalaDoc | public identifiers | The contract of the identifier. |
| Code comments | code | Why the code does something non-obvious. |
| Architecture docs | `docs/architecture/` | An overview that orients a reader in a module; see the `architecture-docs` skill. |
| Development guides | `docs/development/` | How-to guides for human developers. |
| Agent docs | `AGENTS.md`, `docs/agents/`, `.claude/skills/` | Imperative instructions. The facts an agent needs stay in always-loaded context; their rationale goes in a doc loaded on demand. |
| READMEs | a directory's root | What the directory holds and how to start. |
| Papers and reference notes | e.g. `docs/architecture/tuner/mpe-tuner-paper.md` and `mpe-spec.md` | A design paper, or notes on an external specification. |
| Release notes | `docs/release-notes.md` | What changed for users in each release; see the `release` skill. |
| Agent eval reports | `docs/agent-evals/` | Archived results of an experiment on the agent setup, dated and tied to the commit it measured. Not loaded by agents. |

## ScalaDoc

A ScalaDoc states a contract:

1. a one-sentence summary;
2. then, only where a caller can't infer it from the signature: units, failure behaviour, thread-safety and
   non-obvious constraints.

How the implementation works isn't part of the contract. A rationale that takes a paragraph belongs in the
architecture doc or the paper; cite its section by name.

## Code comments

A comment gives a non-obvious *why*: an ordering that matters, an invariant, a workaround. It never repeats what the
next line says, and it stays within a sentence or two, pointing to the architecture doc or the paper for a longer
rationale.

## Papers

Papers use a technical academic tone: precise definitions, each claim followed by its justification, no conversational
asides. Their length isn't capped, but their prose stays tight.
