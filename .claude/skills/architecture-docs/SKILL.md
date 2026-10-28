---
name: architecture-docs
description: >-
  Use when writing, updating, splitting or reviewing an architecture document — a module's docs/ARCHITECTURE.md or a
  topic doc next to it, a module group's README.md, or a cross-cutting doc under docs/architecture/ — including the
  Documentation step of a change that adds, removes or reshapes a module's key types or internal flows, or that adds,
  moves or removes a module or a module group.
---

# Architecture docs

An architecture doc orients a human or an agent in a module: its responsibility, its few most important types, and how
things flow inside it and across its boundary. It tells the reader which types to look at; their ScalaDoc holds the
details. The documentation conventions (`docs/development/doc-conventions.md`) apply too.

## Where docs live

| Doc | Path |
| --- | ---- |
| A module group's index | `<group>/README.md` |
| A module's architecture | `<group>/<module>/docs/ARCHITECTURE.md` |
| A topic or paper of one module | `<group>/<module>/docs/<topic>.md` |
| A doc that spans only the modules of one group | `<group>/docs/<topic>.md` |
| A doc that spans groups | `docs/architecture/<topic>.md` |
| The index | `docs/architecture/README.md` |

Each module also has a `README.md` for humans and a `CLAUDE.md` for agents. The README holds
a title, the module's one-line description from its group's README, and a link to `docs/ARCHITECTURE.md`; `CLAUDE.md`
holds a title and the `@import` of that doc. Agents don't load the module's README, so the copied description costs no
context. Keep any other architecture-related content out of both.

## Module groups

A module group's `README.md` orients a reader in the group: a human browsing it on GitHub, or an agent, through the
group's `CLAUDE.md`, which holds a title and `@README.md`. Under the title `` # `<group>` module group ``, the README
has:

1. one sentence on the group's area;
2. a table of every module in the group: its name, linked to its `ARCHITECTURE.md`, a one-line description and its root
   package;
3. the concepts and conventions of the group's area, if any, such as a package its modules share or a naming rule: a
   paragraph, or a section per topic; a topic that takes more than a few paragraphs goes in the group's `docs/`;
4. a Mermaid flowchart of the dependencies between its modules, when they have some worth drawing;
5. links to the docs in the group's `docs/`, if any.

It doesn't link to `module-overview.md`, which agents already have loaded.

`module-overview.md` is always loaded, so it stays short as modules are added. It has a row for each group, and a row
for each *main* module only: one that a reader needs to find their way across groups. It leaves the packages to the
group READMEs, and draws the main modules' direct dependencies, leaving out those that others imply.

When a change adds, renames, moves or removes a module, update its three files and its row in its group's README.
A changed description goes in both its group's README and its own. For a main module, also update its row and its node
in `module-overview.md`, and the diagram whenever a main module's dependencies change. A new group gets a `README.md`, a
`CLAUDE.md` and a row in the *Module groups* table of `module-overview.md`.

## What goes where

| Content | Home |
| ------- | ---- |
| Responsibility, key types, flows, threading, open work | the architecture doc |
| A type's methods, parameters, edge cases and mechanics | its ScalaDoc |
| `private` and `protected` members | the code, save in rare cases |
| Which modules depend on which | `build.sbt`, and for the main modules `docs/architecture/module-overview.md` |
| A module's description and root package | its group's `README.md`; the description is also copied into the module's `README.md` |
| The concepts and conventions of a group's area | its group's `README.md`, or for a longer topic the group's `docs/` |
| How the code got here | git, the PRs and the release notes |

Mention a dependency only where it matters to the picture. Avoid documenting `private` and `protected` members; name
one only when the picture needs it, e.g. a `private[module]` type that is a main stage of an internal flow.

## Shape

A module's `ARCHITECTURE.md` usually has these sections; leave out one with nothing to say:

1. **Responsibility** — what the module does, and what it leaves to other modules.
2. **Key types** — grouped, one or two lines each: the type's role, not its API.
3. **Flows** — how a message, event or request moves through the types: a diagram and a few sentences.
4. **Threading** — which thread runs what, when it matters.
5. **Subject to change** — one line per open issue that will change the picture.

## Diagrams

Draw flows in Mermaid, which GitHub renders: `flowchart` for pipelines, `sequenceDiagram` for event and thread
hand-offs, `stateDiagram-v2` for lifecycles. Keep a diagram to a few nodes, and let it replace prose rather than
repeat it. Quote a label that holds punctuation: `a["Track.tune, for every track"]`. Don't name a node after a Mermaid
keyword, such as `call`, `click`, `end` or `style`. CI's `docs` job renders every diagram of the repository's Markdown
with [mermaid-cli](https://github.com/mermaid-js/mermaid-cli) and fails on one that doesn't parse.

Draw an ASCII diagram instead when its layout carries meaning that Mermaid would lose, such as states placed on two
axes, like `MidiDeviceHandle.State` in `docs/architecture/midi-device-lifecycle.md`.

## Size budget

The budget is 1000 words, about two pages. Check it with the bundled script, from the repository root:

```bash
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py [--github] [PATH...]
```

With no path it checks every doc under `docs/architecture/`, `<group>/docs/` and `<group>/<module>/docs/`. Up to
1200 words is OK; 1201–1500 warns, compacting recommended; above 1500 is an error that fails CI, compacting required.
The script is a black box: use `--help` for its usage, and don't read its source.

To compact a doc, in this order: rephrase; remove what ScalaDoc owns; split.

## Splitting

Split a doc that is over budget and covers several capabilities. Move one capability to
`<group>/<module>/docs/<topic>.md`, which has the same budget. The module's `ARCHITECTURE.md` keeps one sentence
summarising it and a link to it. `docs/architecture/README.md` lists, under *Other architecture material*, only docs
that span groups, and the papers.

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

The parenthesis explains why the doc is exempt, e.g. `(paper)`; the script ignores what it holds. No other doc takes
the marker.

## Common mistakes

| Mistake | Instead |
| ------- | ------- |
| A new section on how a new type works: its steps, the methods it calls, its caching or concurrency | One or two lines under *Key types* giving its role, and its place in the diagram of the flow it joins. Its ScalaDoc holds the rest. |
| A row in `module-overview.md` for a module that isn't main, or a package there | Its group's `README.md` |
