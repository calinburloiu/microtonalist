# Plan: Keep Apache 2.0 license headers out of agent context

## Context

Every source file in microtonalist begins with a ~15-line Apache 2.0 license header (verified: 203 Scala
files, 4 XML files, 1 Bash script, plus `build.sbt` / `project/build.properties`). These headers are pure
boilerplate that consume tokens and context every time an agent reads a file, yet they carry no information an
agent needs. We want to:

1. **Hide the header from agents at read time** — without deleting it from disk and without renumbering code
   lines (users, agents, and tools reference line numbers).
2. **Tell agents in `CLAUDE.md`** that the header exists but is hidden, so the "missing" top lines don't
   confuse them.
3. **Auto-apply headers to new files** via Google's [`addlicense`](https://github.com/google/addlicense) as a
   git commit hook (plus CI enforcement), so the header set stays complete without manual effort.

This PR commits **only this plan document**. The implementation will be done in a follow-up session on the same
branch/PR, using this plan.

### Key feasibility finding (changes the original idea)

The task proposed a **PostToolUse hook on `Read` that blanks the license lines**. This is **not possible**:
Claude Code's PostToolUse hooks are observational and **cannot modify a tool's output** (confirmed against the
official hooks reference and via the existing repo hook contract). Only **PreToolUse** can rewrite a tool's
**input**, via the `updatedInput` field.

The feasible equivalent (user-approved): a **PreToolUse hook on `Read`** that detects the header and sets the
`Read` `offset` so the header lines are skipped. Empirically verified in this repo: `Read` with `offset: 16`
shows line 17 as `17`, line 19 as `19` — **real line numbers are preserved**; the header lines are simply
absent (not shown as blanks, and not renumbered). **Shebang handling:** a single `offset` cannot keep line 1
(the shebang) while skipping the header on lines 2+, and we must never drop a shebang — so files that start
with a shebang (`#!…`) are **passed through unchanged** (the agent sees their header). This is acceptable
because such files are few (a handful of `.sh` scripts).

## Existing patterns to reuse

- **Hook wiring**: `.claude/settings.json` already registers a `PreToolUse`/`Bash` hook →
  `.claude/hooks/sbt-test-filter.sh`. That script is the template to mirror: bash, `#!/usr/bin/env bash`, reads
  JSON on stdin, uses `jq`, **fails open** (exit 0 with no stdout when anything is off), and emits
  `{"hookSpecificOutput":{"hookEventName":"...","permissionDecision":"allow","updatedInput":{...}}}`.
- **Hook docs**: `docs/development/claude-code-setup.md` has a "Hooks" section documenting `sbt-test-filter` —
  add a sibling subsection for the new hook in the same style.
- **Header format** (exact, to match byte-for-byte): block-comment style for Scala, lines 1–15 = `/*` … `*/`,
  line 16 blank, line 17 `package …`. The `Copyright <year> Calin-Andrei Burloiu` line varies by year
  (2020/2021/2025/2026 seen). XML/FXML/HTML use `<!--` … `-->` (with ` ~ ` line prefixes). Bash/sh/Python use
  `#` line comments after the shebang.

---

## Part 1 — PreToolUse `Read` hook that skips the license header

**New file:** `.claude/hooks/license-header-read-skip.sh` (bash, `chmod +x`).

**Register in** `.claude/settings.json` under `hooks.PreToolUse` with matcher `"Read"` (add alongside the
existing `Bash` entry; do not remove it).

**Behavior (fail-open at every step — exit 0 with no stdout on any miss):**

1. Read stdin JSON. If `jq` is unavailable → exit 0.
2. Extract `tool_input.file_path`, `tool_input.offset`, `tool_input.limit`.
3. **Pass through untouched** (exit 0) if: `offset` or `limit` is already set (the caller asked for a specific
   range — including the documented escape hatch `offset: 1` to view the header); `file_path` is empty; the
   file is not a readable regular text file; or **line 1 is a shebang (`#!…`)** (we never drop a shebang, and a
   single offset can't keep it while skipping the header — these few files are read with the header visible).
4. Scan the first ~25 lines for the marker `Licensed under the Apache License`. If absent → exit 0 (file has no
   header; e.g. the Python tooling files under `.claude/mcp/`).
5. Determine `content_start` by comment style inferred from line 1:
   - Block `/*` … `*/` (`.scala`, `.java`, `.js`, `.css`, `.sbt`): first line containing `*/`, **+1**.
   - XML `<!--` … `-->` (`.xml`, `.fxml`, `.html`): first line containing `-->`, **+1**.
   - Line-comment `#` (`.py`, `.properties`, and `.sh`/`.bash` *without* a shebang — shebang files are already
     passed through in step 3): end of the contiguous top run of `#` lines, **+1**.
   - Then, if the resulting line is blank, **+1** more (skip the single separator blank line). For Scala this
     yields `offset = 17` (starts at `package`).
6. Guard: only act if the marker was found, `content_start > 1`, and the file has more lines than
   `content_start` (don't skip past EOF).
7. Emit, preserving all original input fields and adding the offset:
   ```
   jq -nc --argjson in "$tool_input" --argjson n "$content_start" \
     '{hookSpecificOutput:{hookEventName:"PreToolUse",permissionDecision:"allow",updatedInput:($in + {offset:$n})}}'
   ```
   (Merging onto `$in` rather than emitting `{offset}` alone is safer than relying on field-merge semantics.)

**Scope notes / non-goals:**
- Detection is marker-based, so it is robust across comment styles and never touches non-source/binary files.
- **Edit/Write are unaffected**: the header still exists on disk, so `Edit` string matches still work; if an
  agent needs to edit near the top it re-`Read`s with explicit `offset: 1`.
- **Bash `cat` reads are out of scope** (agents are told to prefer `Read`; reliably rewriting arbitrary `cat`
  pipelines is fragile). The `CLAUDE.md` note + addlicense cover the rest.
- Document the escape hatch: `Read` with explicit `offset: 1` shows the full file including the header.

## Part 2 — `CLAUDE.md` note

Add a short, scannable top-level section (the repo deliberately keeps `CLAUDE.md` lean), e.g. **`# License
Headers`** placed after `# Coverage` and before `# Architecture`. ~6 lines, linking to the detailed doc:

> Every source file starts with a ~15-line Apache 2.0 license header (block, XML, or `#` comment by file type)
> followed by a blank line. A committed `PreToolUse` hook on `Read` automatically skips this header, so files
> appear to start at ~line 17 — **real line numbers are preserved** (the header lines are omitted, not
> renumbered), so don't be confused by the absent top lines. To view the header, `Read` with `offset: 1`.
> Don't add or maintain headers by hand: the `.githooks/pre-commit` `addlicense` hook adds them to new files and
> CI enforces them. See [`docs/development/license-headers.md`].

## Part 3 — `addlicense` for new files (commit hook + CI)

**Custom template** `.license-header.tmpl` (repo root): a Go `text/template` whose inner text reproduces the
existing header wording/indentation exactly, using `{{.Year}}` and `{{.Holder}}` placeholders. addlicense wraps
it in the right comment style per extension (block `/* */` → ` * ` prefix; `#` → `# `; XML → `<!-- -->`). The
implementer **must verify byte-for-byte** that `addlicense -check -f .license-header.tmpl -c "Calin-Andrei
Burloiu" -l apache` passes on the *existing* headers before wiring anything (watch for: addlicense's year
strictness vs. the mixed 2020/2021/2025/2026 years; and whether `.fxml` / `.sbt` / `.properties` are recognized
— if not, those extensions may be skipped and need a documented carve-out or rename strategy).

**Git hook** `.githooks/pre-commit` (committed, `chmod +x`): runs `addlicense` in **add mode** over the staged
in-scope files, then `git add`s any it modified, so new files get headers automatically on commit. Activated by
a one-time `git config core.hooksPath .githooks` (committed hooks live outside `.git/`, which is the standard
shareable approach). Document this one-time step in `CONTRIBUTING.md` and the dev README; optionally fold it
into the existing dev-stack setup script.

**CI enforcement**: add a step to `.github/workflows/scala.yml` that installs `addlicense` (`go install
github.com/google/addlicense@latest`, with a Go setup step) and runs `addlicense -check …` over the source
dirs, failing the build if any in-scope file lacks a header. This covers contributors who didn't enable the
local hook.

**In-scope file types** (per the task, future-proofed via addlicense's extension detection): Scala `.scala`,
Java `.java`, Python `.py`, Bash/sh `.sh`/`.bash`, HTML `.html`, XML `.xml`, JavaFX `.fxml`, JavaScript `.js`,
CSS `.css`. Suggested additions for this repo: `.sbt` and `.properties` (already carry headers today).
**Include the `.claude/mcp` Python tooling** (`cli.py`, `server.py`, `scoverage_core.py`, and their tests),
which is currently header-less, so it gets headers and is covered by the CI `-check`. addlicense inserts the
`#` header at the top (before module docstrings, which remain the first statement — verify the tooling still
imports/runs). Exclude only generated/`target*`/build-output dirs.

## Part 4 — Documentation

- **New** `docs/development/license-headers.md`: full reference — header format per file type, the read-skip
  hook (how it works, the `offset: 1` escape hatch), `addlicense` install + custom template + the
  `core.hooksPath` git hook + CI check, and the in-scope extension list.
- **Update** `docs/development/claude-code-setup.md` "Hooks" section: add a subsection for
  `license-header-read-skip` mirroring the `sbt-test-filter` write-up (what it does, that it's conservative and
  fails open, how to bypass via `offset: 1`).
- **Update** `docs/development/README.md` prerequisites: add `addlicense` + Go as optional tools (needed only to
  run the commit hook / CI check locally).
- **Update** `CONTRIBUTING.md`: one line on enabling git hooks (`git config core.hooksPath .githooks`).

---

## Files at a glance

| Action | Path |
|---|---|
| New | `.claude/hooks/license-header-read-skip.sh` |
| Edit | `.claude/settings.json` (add `PreToolUse`/`Read` entry) |
| New | `.license-header.tmpl` |
| New | `.githooks/pre-commit` |
| Edit | `.github/workflows/scala.yml` (addlicense `-check` step) |
| Edit | `CLAUDE.md` (License Headers note) |
| New | `docs/development/license-headers.md` |
| Edit | `docs/development/claude-code-setup.md`, `docs/development/README.md`, `CONTRIBUTING.md` |

## Verification (for the implementation session)

1. **Read hook, header hidden**: `Read` a Scala file with no offset → output starts at `package` (~line 17),
   line numbers intact; lines 1–16 absent. Repeat for an XML file and a `.sh` file.
2. **Read hook, escape hatch**: `Read` the same file with `offset: 1` → full header visible (hook passes
   through).
3. **Read hook, fail-open**: `Read` a header-less file (e.g. a Markdown doc or a scratch file with no Apache
   marker) and a binary → unchanged. `Read` a shebang `.sh` script → passed through (header visible).
4. **addlicense round-trip**: `addlicense -check -f .license-header.tmpl …` passes on all existing source files
   (no diff), and **adds** `#` headers to the previously header-less `.claude/mcp` Python files; confirm those
   files still import/run (e.g. their test suite passes). Create a new headerless `.scala`/`.py`/`.xml`/`.sh`,
   run the hook in add mode → correct header inserted matching the existing style byte-for-byte.
5. **Commit hook**: with `core.hooksPath` set, `git add` a new headerless file and commit → header added and
   re-staged automatically.
6. **CI**: the new workflow step fails on a deliberately headerless file and passes once fixed.
7. **Build sanity**: `sbtn` compile/tests still green (no file contents changed by the read hook).

## Execution steps for THIS task (committing the plan)

1. Create and switch to branch `feature/license-agents-handling` (off `main`), as explicitly requested.
2. Commit this plan to the repo as `docs/development/license-headers-plan.md`.
3. Push with `git push -u origin feature/license-agents-handling` (retry with backoff on network errors).
4. Open a **draft PR** into `main` (no separate issue, per decision) summarizing the approach and noting that
   implementation follows in the same PR.
