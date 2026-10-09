---
name: architecture-docs
description: >-
  Use when writing, updating, splitting or reviewing a document under docs/architecture/, including the Documentation
  step of a change that adds, removes or reshapes a module's key types or internal flows.
---

# Architecture docs

An architecture doc orients a human or an agent in a module: its responsibility, its few most important types, and how
things flow inside it and across its boundary. It tells the reader which types to look at; their ScalaDoc holds the
details. The documentation conventions (`docs/development/doc-conventions.md`) apply too.

## What goes where

| Content | Home |
| ------- | ---- |
| Responsibility, key types, flows, threading, open work | the architecture doc |
| A type's methods, parameters, edge cases and mechanics | its ScalaDoc |
| `private` and `protected` members | the code |
| Which modules depend on which | `build.sbt` and `docs/architecture/module-overview.md` |
| How the code got here | git, the PRs and the release notes |

Mention a dependency only where it matters to the picture. A `private[module]` type gets one line when it is a main
stage of an internal flow.

## Shape

A module README usually has these sections; leave out one with nothing to say:

1. **Responsibility** — what the module does, and what it leaves to other modules.
2. **Key types** — grouped, one or two lines each: the type's role, not its API.
3. **Flows** — how a message, event or request moves through the types: a diagram and a few sentences.
4. **Threading** — which thread runs what, when it matters.
5. **Subject to change** — one line per open issue that will change the picture.

## Diagrams

Draw flows in Mermaid, which GitHub renders: `flowchart` for pipelines, `sequenceDiagram` for event and thread
hand-offs, `stateDiagram-v2` for lifecycles. Keep a diagram to a few nodes, and let it replace prose rather than
repeat it. Quote a label that holds punctuation: `a["Track.tune, for every track"]`.

## Size budget

The budget is 1000 words, about two pages. Check it with the bundled script, from the repository root:

```bash
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py [--github] [PATH...]
```

With no path it checks every doc under `docs/architecture/`. Up to 1200 words is OK; 1201–1500 warns, compacting
recommended; above 1500 is an error that fails CI, compacting required. The script is a black box: use `--help` for
its usage, and don't read its source.

To compact a doc, in this order: rephrase; remove what ScalaDoc owns; split.

## Splitting

Split a doc that is over budget and covers several capabilities. Move one capability to
`docs/architecture/<module>/<topic>.md`, which has the same budget. The module README keeps one sentence summarising it
and a link to it, and is the only doc linking to it. `docs/architecture/README.md` lists, under *Other architecture
material*, only docs that span modules, and the papers.

## Maintenance checklist

When a change touches what a doc describes:

1. Check that every type the doc names still exists, with Metals `glob-search`.
2. Check that the flows still hold.
3. Remove what the change turned into a detail.
4. Run the size script.
5. Fix the links into headings you renamed: `grep -rn '<file>.md#' --include='*.md' .`

## Exemption

A paper, or a note on an external specification, is exempt from the budget. Mark it in its first 10 lines with:

```markdown
<!-- arch-doc-size: exempt (paper) -->
```

No other doc takes the marker.

## Common mistakes

| Mistake | Instead |
| ------- | ------- |
| A new section on how a new type works: its steps, the methods it calls, its caching or concurrency | One or two lines under *Key types* giving its role, and its place in the diagram of the flow it joins. Its ScalaDoc holds the rest. |
