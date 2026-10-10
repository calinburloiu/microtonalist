# #359 `architecture-docs` skill tests

- Date: 2026-10-10
- Base: `doc/doc-conventions` at `3d76274a`

Each run is a fresh `general-purpose` subagent in a clone of the branch, told to read `doc-conventions.md` first.

- **add**: document `DeferrableRead` in `docs/architecture/format/README.md`, whose *Deferred reads* section
  (94 words) was removed first.
- **trim**: trim `docs/architecture/format/README.md` (1283 words, a warning).

| Run | Words, before → after | Status | Restates the code | *Dependencies* kept | History | Diagram for a flow | Ran the size script | Read the script's source |
| --- | --------------------- | ------ | ----------------- | ------------------- | ------- | ------------------ | ------------------- | ------------------------ |
| red-add-1 | 1185 → 1426 (+241) | WARNING | the read steps (`readRepr`, `loadDeferredData`, `fromReprToDomain`, `value`), the per-composition cache and its key, concurrency | yes (untouched) | no | no: a numbered list | no | no |
| red-add-2 | 1185 → 1342 (+157) | WARNING | concurrency, the `ScaleFormatContext` of each load, read-once caching | yes (untouched) | no | no: prose | no | no |
| red-trim-1 | 1283 → 739 | OK | the package object's `resolveBaseUriWithOverride` and `resolveLibraryUrl`, `NoJsonPreprocessor` | yes | no | no: prose | no | no |
| red-trim-2 | 1283 → 798 | OK | `TypeSpec`'s internals, the settings merge expression, `FormatModule`'s constructor parameters | yes | no | no: prose | no | no |
| green-add-1 | 1185 → 1199 (section: 95) | OK | no | yes, shortened | no | yes: a flowchart | yes | no |
| green-add-2 | 1185 → 1157 (section: 102) | OK | no | yes, cut to a sentence | no | yes: a flowchart | yes | no |
| green-trim-1 | 1283 → 575 | OK | no | no | no | yes: a flowchart | yes | no |
| green-trim-2 | 1283 → 652 | OK | no | no | no | yes: a flowchart | yes | no |

## RED

Both *add* runs wrote a section of their own on how deferred loading works, step by step, at 157 and 241 words. Both
left the `DeferrableRead` contract to its ScalaDoc, yet not the mechanics around it:

> I kept the `load`/`value` details (idempotence, exceptions) out of the README, because the `DeferrableRead` ScalaDoc
> already covers them and the conventions give each fact one home. (red-add-1)

> The section leaves out `DeferredRead`'s status values, locking and `value` behaviour, because the ScalaDoc already
> states that contract. (red-add-2)

red-add-1 also documented a suspected bug as a fact of the design ("tunings sharing a scale URI all get the scale
loaded for the first of them, name included"), with no issue to cite.

Both *trim* runs reached about 750 words and kept every capability the pass criteria name, but kept the *Dependencies*
section, trimming only inside it ("The closing "I/O layer" sentence of Dependencies (module-overview covers it)",
red-trim-2). Both also checked the doc against the code and fixed claims that had gone wrong (the `$ref` override
direction, a library repo for compositions).

No run measured the doc against a budget:

> I did not run the doc size check, because its script is part of the `architecture-docs` skill, which I was told not
> to look for. (red-add-1)

red-add-2 grep-matched lines of this PR's plan in its clone's `issues/`, including the pass criteria; it says it didn't
open the file. The GREEN clones drop `issues/00359-doc-conventions/`.

Failure patterns seen in more than one run:

1. **A new type gets its own section on how it works** (both *add* runs): steps, methods called, caching, concurrency.
   An output of the wrong shape: the skill needs a positive recipe of what documenting a type looks like.
2. **A flow told in prose or a numbered list**, never a diagram (all four runs). An omitted element: the *Flows* slot of
   the skill's *Shape* and its *Diagrams* section ask for the diagram.
3. **The *Dependencies* section kept** (both *trim* runs). An output of the wrong shape: the *Shape* recipe has no such
   section, and *What goes where* gives the graph to `build.sbt` and `module-overview.md`.
4. **Details that the code owns kept** (both *trim* runs): helper names, constructor parameters, a type's internals.
   An output of the wrong shape: *What goes where* gives them to ScalaDoc and the code.
5. **No size check** (all four runs). An omitted element: the *Size budget* section and step 4 of the *Maintenance
   checklist* hold it.

Patterns 2–5 are covered by the skill's base text. Pattern 1 isn't, so it gets the *Common mistakes* row.

## GREEN

The skill as written passed all four runs, so no loophole needed closing. Against the pass criteria of the plan:

- **add**: both runs added a *Reading a composition* section of about 100 words, a flowchart and one paragraph, giving
  `DeferrableRead`'s role and its place in the read flow, without the cache, locking or load status. Both measured the
  doc, found it over 1200 words after the addition, and compacted it back to OK by rephrasing and dropping what
  ScalaDoc owns, as the skill orders:

  > The addition first pushed the doc to 1259 words, over the 1200 warning threshold. Splitting would have meant a new
  > file, so I compacted the README instead, as the skill directs (green-add-1)

- **trim**: both runs rebuilt the doc on the skill's *Shape*, at 575 and 652 words, kept the six capabilities, added no
  fact, and dropped the *Dependencies* section, keeping inline only the dependencies that matter to the picture:

  > Dependencies section: removed, because the skill gives that to `build.sbt` and `module-overview.md`. (green-trim-2)

All four ran the size script, one through `--help` first, and none read its source. Every run drew the read flow as a
Mermaid flowchart, which no RED run did. Step 1 of the maintenance checklist asks for Metals `glob-search`; the clones
aren't indexed by Metals, so the runs checked the names with `grep` instead, which is expected outside the main checkout.
green-trim-2 put a line with no issue number under *Subject to change*; the skill's *Shape* already asks for one line per
open issue there, so it gets no new guidance.
