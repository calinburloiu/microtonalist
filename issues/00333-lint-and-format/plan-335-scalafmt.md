# scalafmt (#335) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-09-27
- **Base commit:** `0b15ccf090bcb682c383a0e878a4eeab1d4681ea` (`feature/scalafmt`)
- **Issues:** #335, a sub-issue of #333

**Goal:** Format all Scala and sbt code with scalafmt, configured to reproduce the IntelliJ IDEA default style the code
already follows, and enforce it in CI and in the agent workflow.

**Architecture:** Three stacked, squash-merged PRs. The tooling PR adds `sbt-scalafmt`, a tuned `.scalafmt.conf`, the
`fix`/`lint` aliases, the pre-commit scalafmt step and docs, and enforces nothing. The bulk PR holds only the output of
`sbtn fix`. The finish PR, which starts after the bulk PR merges, records the bulk PR's squashed SHA in
`.git-blame-ignore-revs`, adds the CI `lint` job, and adds the Lint step to the CLAUDE.md workflow.

**Tech Stack:** sbt 1.10.7, Scala 3.6.3, sbt-scalafmt 2.5.6, scalafmt 3.11.5 (sbt plugin and Coursier CLI), bash 3.2+
(the pre-commit hook), GitHub Actions.

**Spec:** [`design.md`](design.md), sections 2 (delivery structure), 3 (#335: scalafmt) and 7 (verification). The
design is the source of truth; this plan doesn't reopen its decisions.

## Global Constraints

- `project/plugins.sbt`: `addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")`, the newest release that runs on
  sbt 1.10.7: every 2.6.x release refuses to run on sbt older than 1.12.9. #336 upgrades sbt, and then the plugin to
  2.6.x. The plugin downloads the scalafmt version that `.scalafmt.conf` pins, so the output doesn't depend on it.
- `.scalafmt.conf` at the repo root with `version = 3.11.5`, and no license header (the `.conf` extension isn't checked).
- Style targets: `maxColumn = 120`, `indent.main = 2`, `newlines.source = keep`, `docstrings.style = Asterisk`, no
  docstring or comment wrapping, `rewrite.scala3.convertToNewSyntax = false`, `rewrite.scala3.removeOptionalBraces`
  off, no import sorting (#334's `OrganizeImports` owns it), `project.git = true`.
- Dialects: `scala3` by default, `sbt1` for `*.sbt`, `scala212` for `project/*.scala`.
- Aliases in `build.sbt`: `fix` = `scalafmtAll`, `scalafmtSbt`, `experiments/scalafmtAll`; `lint` = `scalafmtCheckAll`,
  `scalafmtSbtCheck`, `experiments/scalafmtCheckAll`.
- Scala stays on 3.6.3. No other plugin or dependency changes.
- Branches: `feature/scalafmt` (tooling), `feature/scalafmt-reformat` (bulk), `feature/scalafmt-enforce` (finish).
  Switch branches in place; don't use git worktrees.
- PR titles start with `[#333/#335]`; commit subjects too. Every PR is opened as a draft through the `contributing`
  skill's script, with label `feature` and milestone `Agentic Coding`.
- Only the finish PR links #335 for closing. The tooling and bulk PR bodies contain no closing keyword (close, fix,
  resolve or their variants) in front of an issue number, not even negated; GitHub links it anyway.
- The bulk PR contains only formatter output, reproducible with `sbtn fix`, and no hand edits.
- `.git-blame-ignore-revs` holds the bulk PR's squashed SHA on `main`, never a branch SHA.
- Run sbt through `sbtn`. Coverage commands are the exception: plain `sbt` with
  `-Dmicrotonalist.build.targetSuffix=-scoverage`.
- Once Task 4 adds the hook's scalafmt step, commit any later change to a `.scala` or `.sbt` file on
  `feature/scalafmt` with `git commit --no-verify`, so that its formatting stays in the bulk PR. None of those files
  needs the addlicense step (they already have headers, or are `.sbt`, which addlicense skips).
- End commit messages with the `Co-Authored-By` and `Claude-Session` trailer lines the harness specifies, and PR bodies
  with its attribution lines.

## Review Focus

1. **A new file that isn't `git add`ed yet** (the usual state during the agent's Lint step) must be formatted by
   `sbtn fix` and checked by `sbtn lint`, even with `project.git = true`. Tested in Task 1, Step 8.
2. **A staged Scala file that doesn't parse** must abort the commit and show scalafmt's error, not pass silently or be
   skipped. Tested in Task 4, case 3.
3. **A renamed and edited file** must be formatted by the hook. The old hook's `--diff-filter=ACM` drops renames.
   Tested in Task 4, case 2.
4. **One of `addlicense` and `scalafmt` missing** must skip only that tool's step, and a commit without Scala or sbt
   files must not start scalafmt. Tested in Task 4, cases 4–6.
5. **A formatting violation in `experiments` or `build.sbt`** (outside what `root` aggregates) must make `sbtn lint`
   exit non-zero. Tested in Task 7, Step 8.

## Conventions for this plan

- `$SCRATCH` is the session's scratchpad directory, never `/tmp`. Scratch files that the steps create inside the repo
  are never committed, and each step that creates one deletes it.
- `$REPO` is the repository root, `/Users/calinburloiu/Development/microtonalist`.
- **Stop points** are marked **STOP**. At each one, report to the user in chat (explain; don't just point to a file)
  and wait, unless the run is unattended.

### Unattended run

When the user has said they're away, run Tasks 1–7 without waiting at any STOP point: make the decision, record it in
the decision log, and continue. The run ends at Task 7, Step 11. Tasks 8–10 need merges only the user makes, so they
never run unattended. Never merge a PR or push to `main`.

The **decision log** is `$SCRATCH/decisions.md`: one numbered entry per decision the user would otherwise have made,
written as soon as it's made (the file survives context compaction). Each entry gives the task and step, what was
decided, the alternatives, why, and how to change it later: which file or setting to edit, and that Task 8
regenerates the bulk PR afterwards. At the end, the log goes into the tooling PR body (Task 6, Step 4, updated in
Task 7, Step 11) and into the final report.

Defaults for the decisions this plan already foresees:

- **Task 1, Step 8** (sbt skips untracked files): take option (a), drop `project.git = true`.
- **Task 2, Step 4** (IntelliJ IDEA is running, so `format.sh` fails): skip the step and judge by the code's own
  consistency.
- **Task 2, Steps 5–6**: each style difference kept because no setting removes it is a decision.
- **Task 3**: approve the config yourself, by Task 2's stop rule; see "Unattended run" in Task 3.
- **Task 7, Step 4** (the formatter output breaks compilation): fix `.scalafmt.conf` on `feature/scalafmt`, push it
  to the tooling PR, and restart Task 7 from Step 1 on a fresh branch.
- **Anything unforeseen**: choose the option most consistent with the design that is easiest to undo, and log it.
  Stop only when every option would be irreversible or reach outside the plan's scope, and then say why in the final
  report.

## Pre-measured starting point

Measured while writing this plan, with the scalafmt 3.11.5 CLI on a scratch copy of the base commit (247 `.scala` and
`.sbt` files, about 45.5k lines). `sbtn fix` uses the same scalafmt version and the same files, so it should reproduce
these numbers:

| # | Config change | Files | Lines +/− | Kept | Why |
| --- | --- | --- | --- | --- | --- |
| 0 | Design's starting settings, plus `align.preset = none` and `rewrite.trailingCommas.style = keep` | 148 | +2383/−1247 | yes | Starting point |
| 1 | `binPack.defnSite = always`, `binPack.callSite = always` | 130 | +1347/−1013 | yes | IntelliJ packs parameters and arguments; scalafmt put one per line |
| 2 | `indent.extendSite = 2`, `docstrings.forceBlankLineBefore = false` | 98 | +1160/−958 | yes | `extends` on its own line is indented by 2; no blank line forced before a ScalaDoc |
| 3 | `spaces.beforeInfixArgInParens = AfterSymbolic` | 138 | +2199/−1997 | no | It also removes the space in `"…" in {` |
| 4 | `literals.hexDigits = Upper`, `newlines.configStyle.{callSite,defnSite}.prefer = false` | 81 | +885/−831 | yes | The code writes `0xFF`; a `)` on its own line no longer forces one argument per line |

With config 4, only 44 files, +167/−113 remain when whitespace is ignored (`git diff -w`); the rest is indentation.
The dialect overrides were checked too: `enum` fails to parse under `project/*.scala` (`scala212`) and `*.sbt`
(`sbt1`), and parses in module sources (`scala3`).

Remaining categories seen in a sample, for the tuning loop:

- **Probably style differences to tune:** continuation indentation of infix operators (the `&&` lines in
  `Scale.equals`); long `import a.{B, C, …}` lines over 120 columns wrapped into multi-line selectors; hand-aligned
  `case … =>` arrows (`build.sbt`, `project/Coverage.scala`) collapsed by `align.preset = none`; case bodies rewrapped
  where a line exceeds 120 columns (`Scale.scala`, `case CentsIntonationStandard =>`); ScalaTest's
  `contain inOrder(` becoming `contain inOrder (` (about 56 lines).
- **Probably real inconsistencies to keep:** mis-indented test bodies (`MpeTunerTest`, `"input channel" in new
  Fixture(…)`), `tailPitches *` → `tailPitches*`, `"file://"+x` → `"file://" + x`, `ps@_*` → `ps @ _*`.

## File structure

**Tooling PR (`feature/scalafmt`):**

- Create `.scalafmt.conf`: scalafmt version, dialects and style.
- Modify `project/plugins.sbt`: the sbt-scalafmt plugin.
- Modify `build.sbt`: the `fix` and `lint` aliases, after `commands ++= Coverage.commands`.
- Modify `.githooks/pre-commit`: two independent steps, addlicense and the new scalafmt step.
- Modify `docs/development/coding-conventions.md`: "General formatting".
- Modify `docs/development/build.md`: new "Formatting" section (commands, pre-commit hook, editors).
- Modify `CONTRIBUTING.md`: formatting setup (scalafmt CLI, IntelliJ IDEA).
- Modify `docs/development/README.md`: the scalafmt CLI prerequisite.
- Modify `docs/development/license-headers.md`: a pointer to the hook's scalafmt step.
- Already on the branch: `issues/00333-lint-and-format/design.md` and this plan.

**Bulk PR (`feature/scalafmt-reformat`):** only the `.scala` and `.sbt` files that `sbtn fix` changes.

**Finish PR (`feature/scalafmt-enforce`):**

- Create `.git-blame-ignore-revs`.
- Modify `.github/workflows/scala.yml`: the `lint` job.
- Modify `AGENTS.md` (`CLAUDE.md` is a symlink to it): the Lint final check.
- Modify `CONTRIBUTING.md`: `blame.ignoreRevsFile`. It goes in this PR, not the tooling PR, because `git blame` fails
  with `fatal: could not open object name list` while the configured file doesn't exist.
- Modify `docs/development/build.md` and the `.githooks/pre-commit` header comment: CI now enforces formatting.

---

## Phase 1: Tooling PR (`feature/scalafmt`)

### Task 1: sbt-scalafmt, the starting config, and the `fix`/`lint` aliases

**Files:**

- Create: `.scalafmt.conf`
- Modify: `project/plugins.sbt`, `build.sbt` (after line 24, `commands ++= Coverage.commands`)
- Modify: `docs/development/coding-conventions.md` ("General formatting"), `docs/development/build.md` (new section)

**Interfaces:**

- Consumes: nothing.
- Produces: the sbt commands `fix` and `lint`, used by every later task, by the CI `lint` job (Task 10) and by the
  CLAUDE.md Lint step (Task 10); `.scalafmt.conf`, tuned in Task 2 and read by the hook (Task 4) and Metals (Task 5);
  the `build.md` "Formatting" section, which Tasks 4, 5 and 10 extend.

- [ ] **Step 1: Check that the aliases don't exist yet**

Run: `sbtn lint; echo "exit=$?"`
Expected: an error that `lint` isn't a valid command or key, and a non-zero exit.

- [ ] **Step 2: Add the plugin**

Append to `project/plugins.sbt`:

```scala
// TODO #336 Upgrade to sbt-scalafmt 2.6.x, which refuses to run on sbt older than 1.12.9, after upgrading sbt
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")
```

- [ ] **Step 3: Create `.scalafmt.conf` with the pre-measured config**

```hocon
# scalafmt configuration. It reproduces IntelliJ IDEA's default Scala style, which the code followed before scalafmt
# was adopted, so that adopting it changes as little as possible. See docs/development/build.md#formatting.
version = 3.11.5

runner.dialect = scala3
fileOverride {
  "glob:**/*.sbt" {
    runner.dialect = sbt1
  }
  # sbt 1 compiles the build definition with Scala 2.12.
  "glob:**/project/*.scala" {
    runner.dialect = scala212
  }
}

# Only format the files tracked by git.
project.git = true

maxColumn = 120
indent.main = 2
indent.extendSite = 2

# Keep the line breaks the author wrote, as IntelliJ IDEA does, and only break lines longer than maxColumn.
newlines.source = keep
newlines.configStyle.callSite.prefer = false
newlines.configStyle.defnSite.prefer = false

# Keep several parameters or arguments on a line, instead of one per line.
binPack.defnSite = always
binPack.callSite = always

align.preset = none
align.openParenDefnSite = true
align.openParenCallSite = false

danglingParentheses.defnSite = false
danglingParentheses.callSite = false
danglingParentheses.ctrlSite = false

docstrings.style = Asterisk
docstrings.wrap = no
docstrings.forceBlankLineBefore = false
comments.wrap = no

literals.hexDigits = Upper

rewrite.trailingCommas.style = keep
# Brace syntax, as the coding conventions require.
rewrite.scala3.convertToNewSyntax = false
rewrite.scala3.removeOptionalBraces = no
```

- [ ] **Step 4: Add the aliases to `build.sbt`**

Insert after `commands ++= Coverage.commands` (line 24), separated by a blank line:

```scala
// Code formatting: `fix` rewrites the sources with scalafmt and `lint` checks them without changing anything. `root`
// doesn't aggregate `experiments`, so both name it explicitly. See docs/development/build.md#formatting.
addCommandAlias("fix", "scalafmtAll; scalafmtSbt; experiments/scalafmtAll")
addCommandAlias("lint", "scalafmtCheckAll; scalafmtSbtCheck; experiments/scalafmtCheckAll")
```

- [ ] **Step 5: Restart the dev stack so sbt and Metals load the plugin**

Run: `bin/microtonalist-dev-stack restart`, then `bin/microtonalist-dev-stack status; echo "exit=$?"`.
Expected: exit 0. Then call `mcp__metals__list-modules`, which must answer (see `docs/agents/dev-stack.md` if not).

- [ ] **Step 6: Check that `lint` runs and fails on the unformatted code**

Run: `sbtn lint; echo "exit=$?"`
Expected: scalafmt reports files that aren't formatted, and the exit is non-zero. This is the correct state until the
bulk PR merges.

- [ ] **Step 7: Commit the plugin, config and aliases**

The next steps run the formatter and then reset its output with `git restore`, which would also revert uncommitted
edits to `build.sbt` and `project/plugins.sbt`. So commit them first:

```bash
git add project/plugins.sbt build.sbt .scalafmt.conf
git commit -m "[#333/#335] Add sbt-scalafmt, a starting .scalafmt.conf, and the fix and lint aliases"
```

The hook doesn't have its scalafmt step yet, so `build.sbt` is committed unformatted, as intended.

- [ ] **Step 8: Check what `fix` covers, including a new untracked file (Review Focus 1)**

```bash
cat > common/src/main/scala/org/calinburloiu/music/microtonalist/common/UntrackedScratch.scala <<'EOF'
package org.calinburloiu.music.microtonalist.common

object UntrackedScratch {
  def f(a:Int,b:Int)=a+b
}
EOF
sbtn fix; echo "exit=$?"
grep -n 'def f' common/src/main/scala/org/calinburloiu/music/microtonalist/common/UntrackedScratch.scala
git diff --shortstat -- '*.scala' '*.sbt'
git diff --stat -- build.sbt project/
```

Expected:

- exit 0, so every command in the alias resolved, including `experiments/scalafmtAll`;
- `def f(a: Int, b: Int) = a + b`, so the untracked file was formatted;
- about 81 files changed, +885/−831 (see "Pre-measured starting point"; a large difference means the plugin isn't
  using `.scalafmt.conf`, so investigate before continuing);
- `build.sbt`, `project/Coverage.scala` and `project/Dependencies.scala` are listed, so `scalafmtSbt` covers
  `project/*.scala`.

If the untracked file is **not** formatted, `project.git = true` makes sbt skip untracked files. **STOP** and ask the
user to choose: (a) drop `project.git = true` (recommended: sbt only formats its source directories anyway, and the
hook passes explicit file names), or (b) keep it and document "`git add` new files before `sbtn fix`" in `build.md` and
in the Lint step (Task 10).

- [ ] **Step 9: Check that the formatted tree passes `lint` and the formatted build still loads**

Run: `sbtn reload; echo "exit=$?"`, then `sbtn lint; echo "exit=$?"`.
Expected: both exit 0.

- [ ] **Step 10: Probe the dialect overrides**

```bash
for name in project/DialectProbe.scala DialectProbe.sbt common/src/main/scala/DialectProbe.scala; do
  printf 'enum Color {\n  case Red\n}\n' | scalafmt --non-interactive --stdin --assume-filename "$name" >/dev/null 2>&1
  echo "$name exit=$?"
done
```

Expected: `exit=2` for the first two (the `scala212` and `sbt1` dialects reject `enum`) and `exit=0` for the third
(`scala3`). No file is created; `--assume-filename` only picks the override.

- [ ] **Step 11: Reset the formatter output**

```bash
rm common/src/main/scala/org/calinburloiu/music/microtonalist/common/UntrackedScratch.scala
git restore -- '*.scala' '*.sbt'
git status --short
```

Expected: empty. Then run `sbtn reload`.

- [ ] **Step 12: Update the coding conventions**

In `docs/development/coding-conventions.md`, replace the bullets of "General formatting" with:

```markdown
* Code is formatted with [scalafmt](https://scalameta.org/scalafmt/), configured in `.scalafmt.conf` to reproduce
  IntelliJ IDEA's default Scala style. Format with `sbtn fix` and check with `sbtn lint`; see
  [`build.md`](build.md#formatting).
* Indentation is done with 2 spaces.
* Lines have a maximum length of 120 characters.
* scalafmt keeps the line breaks you write, and only adds one where a line would exceed 120 characters.
* All public identifiers (classes, methods, fields, etc.) are properly documented via ScalaDocs.
```

- [ ] **Step 13: Add the "Formatting" section to `docs/development/build.md`**

Insert between "## Compiling" and "## Building the fat JAR":

````markdown
## Formatting

Scala sources and the sbt build definition (`build.sbt` and `project/*.scala`) are formatted with
[scalafmt](https://scalameta.org/scalafmt/). Its configuration, `.scalafmt.conf` at the repository root, pins the
scalafmt version and reproduces IntelliJ IDEA's default Scala style. It sets `newlines.source = keep`: scalafmt keeps the
line breaks the author wrote and only adds one where a line would exceed 120 columns, so two ways of breaking the same
expression can both pass the check.

Format everything:

```bash
sbtn fix
```

Check the formatting without changing any file. It fails if a file isn't formatted:

```bash
sbtn lint
```

Both are command aliases defined in `build.sbt`. Besides the modules that `root` aggregates, they cover the build
definition and the `experiments` module, which `root` doesn't aggregate.
````

- [ ] **Step 14: Commit the docs**

```bash
git add docs/development/coding-conventions.md docs/development/build.md
git commit -m "[#333/#335] Document formatting with scalafmt"
```

### Task 2: Tune `.scalafmt.conf` to the current style

**Files:**

- Modify: `.scalafmt.conf` (left uncommitted; Task 3 commits it after the user's review)
- Scratch: `$SCRATCH/scalafmt-tuning.md` (the tuning log, pasted into the tooling PR body in Task 6)

**Interfaces:**

- Consumes: `sbtn fix` (Task 1).
- Produces: the final `.scalafmt.conf`; the tuning log; the list of remaining change categories, each marked as a real
  inconsistency or a style difference, for Task 3.

The loop is design section 3's: run the formatter, measure, group the changes by kind, adjust one setting, reset, and
repeat. It stops when what's left fixes real inconsistencies rather than changing the style. The scalafmt settings are
documented at <https://scalameta.org/scalafmt/docs/configuration.html>; scalafmt fails with an error naming the key
when a setting doesn't exist.

- [ ] **Step 1: Start the tuning log**

Create `$SCRATCH/scalafmt-tuning.md` with the table from "Pre-measured starting point" (rows 0–4). Add one row per
iteration below.

- [ ] **Step 2: Measure the current config**

```bash
sbtn fix
git diff --shortstat -- '*.scala' '*.sbt'
git diff -w --shortstat -- '*.scala' '*.sbt'
git diff -- '*.scala' '*.sbt' | awk '/^diff --git/{f=$3} /^@@/{n[f]++} END{for(k in n) print n[k], k}' | sort -rn | head -15
git diff -w -U0 -- '*.scala' '*.sbt' | grep -E '^[-+][^-+]' | head -80
```

The first run must reproduce row 4 (about 81 files, +885/−831). The last two commands show the files with the most
hunks and the changes that aren't only whitespace. For a whitespace-only category, read a few hunks with
`git diff -U1 -- <file>`.

- [ ] **Step 3: Group the changes by kind**

List each kind of change with its hunk count and one example: indentation of X, spacing around Y, wrapping of Z, and
so on. Start from the categories in "Pre-measured starting point". Mark each as a **style difference** (the code
consistently does it another way, e.g. `inOrder(`) or a **real inconsistency** (the code does it several ways, or it's
plainly misformatted, e.g. the mis-indented test bodies in `MpeTunerTest`).

- [ ] **Step 4: Settle unclear categories with IntelliJ IDEA's formatter**

When it's unclear which kind a category is, check what IntelliJ IDEA's default formatter does with the original files:

```bash
rm -rf "$SCRATCH/intellij" && mkdir -p "$SCRATCH/intellij/orig" "$SCRATCH/intellij/fmt"
for f in <two or three files with hunks of that category>; do
  git show "HEAD:$f" > "$SCRATCH/intellij/orig/$(basename "$f")"
  git show "HEAD:$f" > "$SCRATCH/intellij/fmt/$(basename "$f")"
done
printf 'object Probe {\n  def f(a:Int,b:Int)={a+b}\n}\n' > "$SCRATCH/intellij/fmt/Probe.scala"
"/Applications/IntelliJ IDEA.app/Contents/bin/format.sh" -allowDefaults "$SCRATCH/intellij/fmt"/*.scala
diff -u "$SCRATCH/intellij/orig" "$SCRATCH/intellij/fmt"
```

- `Probe.scala` must come out formatted (`def f(a: Int, b: Int) = {`…). If it doesn't, IntelliJ isn't formatting Scala
  here, so skip this step and rely on the code's own consistency.
- If `format.sh` fails because IntelliJ IDEA is running, ask the user whether to close it or skip this step.
- A scalafmt change that IntelliJ also makes is a real inconsistency: keep it. A scalafmt change that IntelliJ doesn't
  make is a style difference: tune it in Step 5.

- [ ] **Step 5: Adjust one setting, reset, and measure again**

Change one setting in `.scalafmt.conf`, then:

```bash
git restore -- '*.scala' '*.sbt'
sbtn fix
git diff --shortstat -- '*.scala' '*.sbt'
git diff -w --shortstat -- '*.scala' '*.sbt'
```

Keep the change if it removes a style-difference category without adding new changes elsewhere, otherwise revert it.
Log it either way (setting, files, +/−, kept or not, why). Candidates, in this order:

1. `importSelectors = singleLine`, for the long imports that get wrapped. Check with Step 4 that IntelliJ keeps them on
   one line.
2. `align.preset = some` against `none`, for the hand-aligned `case … =>` arrows. Keep whichever changes fewer lines.
3. The `indentOperator.*` settings, for the continuation lines of infix operators (`&&` in `Scale.equals`).
4. The `indent.*` settings (`indent.callSite`, `indent.defnSite`, `indent.caseSite`, `indent.ctrlSite`), for any other
   indentation category.
5. The case-body rewrapping in `Scale.scala`: look for a `newlines.*` setting that keeps the original break.
6. `contain inOrder (`: no setting found yet (row 3 of the log). If none works, it stays as a style difference for the
   user's review.

- [ ] **Step 6: Stop when only real inconsistencies are left**

Stop when every remaining category is either a real inconsistency, or a style difference that no setting removes
without a bigger diff elsewhere. Leave the final reformat in the working tree for Task 3.

### Task 3: Checkpoint: the user reviews sample reformatted files

**Files:**

- Commit: `.scalafmt.conf`
- Scratch: `$SCRATCH/scalafmt-preview.diff`

**Interfaces:**

- Consumes: the final config, the tuning log and the categories (Task 2).
- Produces: the approved `.scalafmt.conf`, committed. The bulk PR (Task 7) is generated from it.

- [ ] **Step 1: Save the full preview**

Run: `git diff -- '*.scala' '*.sbt' > "$SCRATCH/scalafmt-preview.diff"`.
The working tree keeps the reformat, so the user can also browse it in the IDE or with `git diff`.

- [ ] **Step 2: Pick the sample files**

Pick about six: the most changed file under `src/main`, the most changed file under `src/test`, `build.sbt`, and one
file for each remaining category that isn't only indentation, e.g. `MpeTunerTest.scala` for `inOrder (`.

- [ ] **Step 3: Present the review in chat**

Explain the following in the chat message itself, since the user may not open any file:

- The final numbers: files and +/− lines, and the part that remains when whitespace is ignored.
- The tuning log table.
- Each remaining category: hunk count, one example, and whether it's a real inconsistency or a style difference kept
  (and why no setting removes it).
- The diffs of the sample files (for a long file, its first hunks of each category).
- Where the full reformat is: the working tree, and `$SCRATCH/scalafmt-preview.diff`.

Then ask the user to approve the config or name the changes to undo.

- [ ] **Step 4: STOP until the user approves**

If the user asks for changes, go back to Task 2, Step 5 for those categories, then present only what changed.

**Unattended run:** skip Steps 3–4. Approve the config yourself if Task 2's stop rule holds, and log it as a decision.
Write the review material from Step 3 to `$SCRATCH/config-review.md` for the tooling PR body (Task 6), without the
sample diffs, since the bulk PR shows the full reformat. Instead, name the sample files and the categories to look at
in each.

- [ ] **Step 5: Reset the reformat and commit the config**

```bash
git restore -- '*.scala' '*.sbt'
git status --short          # expected: only .scalafmt.conf
git add .scalafmt.conf
git commit -m "[#333/#335] Tune .scalafmt.conf to the current code style"
```

### Task 4: The pre-commit hook's scalafmt step

**Files:**

- Modify: `.githooks/pre-commit` (keep lines 1–16, the shebang and the license header; replace the rest)
- Modify: `docs/development/build.md`, `CONTRIBUTING.md`, `docs/development/README.md`,
  `docs/development/license-headers.md`
- Scratch: `$SCRATCH/hook-check.sh` (not committed)

**Interfaces:**

- Consumes: `.scalafmt.conf` (Task 3), which the scalafmt CLI reads; `.license-header.tmpl`.
- Produces: the hook functions `staged_files_matching`, `add_license_headers` and `format_scala`. Task 10 edits only
  the hook's header comment.

The design verifies the hook by hand-run checks rather than committed tests (section 7). The script below automates
them in throwaway repositories under `$SCRATCH`, so the real index is never touched.

- [ ] **Step 1: Write the check script**

Create `$SCRATCH/hook-check.sh`:

```bash
#!/usr/bin/env bash
#
# hook-check.sh — exercises .githooks/pre-commit in throwaway git repositories and prints PASS or FAIL per case.
# Usage (from the microtonalist root):  REPO="$PWD" SCRATCH="<session scratchpad>" bash "$SCRATCH/hook-check.sh"
#
set -uo pipefail

REPO="${REPO:?set REPO to the microtonalist checkout}"
SCRATCH="${SCRATCH:?set SCRATCH to the session scratchpad directory}"
HOOK="$REPO/.githooks/pre-commit"
T="$SCRATCH/hook-test"
failures=0

pass() { echo "PASS: $1"; }
fail() { echo "FAIL: $1"; failures=$((failures + 1)); }

# Directories that each put only one of the two tools on PATH (plus git and java), to simulate the other one missing.
# Cases run with them use /usr/bin/env's bash from /bin, i.e. macOS's bash 3.2.
ONLY_ADDLICENSE="$SCRATCH/bin-only-addlicense"
ONLY_SCALAFMT="$SCRATCH/bin-only-scalafmt"
rm -rf "$ONLY_ADDLICENSE" "$ONLY_SCALAFMT"
mkdir -p "$ONLY_ADDLICENSE" "$ONLY_SCALAFMT"
wrap() { printf '#!/bin/sh\nexec "%s" "$@"\n' "$(command -v "$2")" > "$1/$2" && chmod +x "$1/$2"; }
wrap "$ONLY_ADDLICENSE" addlicense
wrap "$ONLY_ADDLICENSE" git
wrap "$ONLY_SCALAFMT" scalafmt
wrap "$ONLY_SCALAFMT" java
wrap "$ONLY_SCALAFMT" git
BASE_PATH="/usr/bin:/bin"

new_repo() {
  rm -rf "$T" && mkdir -p "$T" && cd "$T" || exit 1
  git init -q
  cp "$REPO/.scalafmt.conf" "$REPO/.license-header.tmpl" .
  # Big enough that editing one line keeps it above git's 50% rename-similarity threshold.
  printf 'object Base {\n  def f(a: Int): Int = a\n  def g1: Int = 1\n  def g2: Int = 2\n  def g3: Int = 3\n  def g4: Int = 4\n  def g5: Int = 5\n}\n' > Base.scala
  git add -A
  git -c user.name=t -c user.email=t@t commit -qm base --no-verify
}

UGLY='object Ugly {\n  def f(a:Int,b:Int)=a+b\n}\n'
FORMATTED='  def f(a: Int, b: Int) = a + b'

# 1. A new, badly formatted Scala file gets a license header and is formatted.
new_repo
printf "$UGLY" > Ugly.scala && git add Ugly.scala
if "$HOOK"; then
  grep -q 'Licensed under the Apache License' <<<"$(git show :Ugly.scala)" && pass "1a header added" || fail "1a header added"
  grep -qF "$FORMATTED" <<<"$(git show :Ugly.scala)" && pass "1b staged file formatted" || fail "1b staged file formatted"
else
  fail "1 hook exited non-zero"
fi

# 2. A renamed and edited file is formatted too.
new_repo
git mv Base.scala Renamed.scala
sed -i '' 's/def f(a: Int)/def f(a:Int)/' Renamed.scala && git add Renamed.scala
git diff --cached --name-status | grep -q '^R' || fail "2 setup: expected a staged rename"
"$HOOK" >/dev/null 2>&1 && grep -qF '  def f(a: Int): Int = a' <<<"$(git show :Renamed.scala)" \
  && pass "2 renamed file formatted" || fail "2 renamed file formatted"

# 3. A staged file that doesn't parse aborts the commit and shows scalafmt's error.
new_repo
printf 'object Bad {\n' > Bad.scala && git add Bad.scala
out="$("$HOOK" 2>&1)"; status=$?
[ "$status" -ne 0 ] && grep -q 'Bad.scala' <<<"$out" \
  && pass "3 parse error aborts the commit" || fail "3 parse error aborts the commit (status=$status)"

# 4. Without scalafmt, the header is still added and the hook succeeds with a message.
new_repo
printf "$UGLY" > Ugly.scala && git add Ugly.scala
out="$(PATH="$ONLY_ADDLICENSE:$BASE_PATH" "$HOOK" 2>&1)"; status=$?
[ "$status" -eq 0 ] && grep -q 'scalafmt not found' <<<"$out" \
  && grep -q 'Licensed under the Apache License' <<<"$(git show :Ugly.scala)" \
  && pass "4 skips scalafmt, still adds the header" || fail "4 skips scalafmt, still adds the header (status=$status)"

# 5. Without addlicense, the file is still formatted and the hook succeeds with a message.
new_repo
printf "$UGLY" > Ugly.scala && git add Ugly.scala
out="$(PATH="$ONLY_SCALAFMT:$BASE_PATH" "$HOOK" 2>&1)"; status=$?
[ "$status" -eq 0 ] && grep -q 'addlicense not found' <<<"$out" \
  && grep -qF "$FORMATTED" <<<"$(git show :Ugly.scala)" \
  && pass "5 skips addlicense, still formats" || fail "5 skips addlicense, still formats (status=$status)"

# 6. A commit without Scala or sbt files doesn't start scalafmt (no message even though scalafmt is missing).
new_repo
echo notes > notes.md && git add notes.md
out="$(PATH="$ONLY_ADDLICENSE:$BASE_PATH" "$HOOK" 2>&1)"; status=$?
[ "$status" -eq 0 ] && ! grep -q 'scalafmt' <<<"$out" \
  && pass "6 no Scala files, no scalafmt" || fail "6 no Scala files, no scalafmt (status=$status, output: $out)"

# 7. A staged .sbt file is formatted with the sbt dialect.
new_repo
printf 'lazy val x=1\n' > build.sbt && git add build.sbt
"$HOOK" >/dev/null 2>&1 && grep -qF 'lazy val x = 1' <<<"$(git show :build.sbt)" \
  && pass "7 sbt file formatted" || fail "7 sbt file formatted"

echo "failures: $failures"
[ "$failures" -eq 0 ]
```

- [ ] **Step 2: Run it against the current hook and confirm the right cases fail**

Run: `REPO="$PWD" SCRATCH="$SCRATCH" bash "$SCRATCH/hook-check.sh"`
Expected (checked while writing this plan): PASS for `1a` and `6`; FAIL for `1b`, `2`, `3`, `4`, `5` and `7`;
`failures: 6`.

- [ ] **Step 3: Replace the hook's body**

Keep lines 1–16 of `.githooks/pre-commit` (shebang and license header) and replace everything after them with:

```bash
#
# pre-commit — prepare the staged source files for the commit.
#
# 1. addlicense adds the Apache 2.0 license header to new source files. Existing
#    files (which already contain a "Copyright" line) are left untouched.
#    See docs/development/license-headers.md.
# 2. scalafmt formats the staged `.scala` and `.sbt` files with the repository's
#    `.scalafmt.conf`. See docs/development/build.md#pre-commit-hook.
#
# Each step rewrites the files in the working tree and re-stages them, so its
# changes land in this commit. Re-staging a whole file also stages the file's
# unstaged hunks, so a partial commit (`git add -p`) includes them.
#
# Enable once per clone with:  git config core.hooksPath .githooks
# The hook is a convenience: when a step's tool is not installed, that step is
# skipped with a message and the other step still runs. CI enforces the license
# headers via `addlicense -check`.
#

set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

# Prints the staged (Added/Copied/Modified/Renamed) files whose path matches the
# extended regular expression $1, one per line.
staged_files_matching() {
  git diff --cached --name-only --diff-filter=ACMR | grep -E "$1" || true
}

# Adds license headers to the staged, in-scope files. addlicense recognises the
# comment style for these extensions; `.sbt` and `.fxml` are deliberately left
# out of add mode because addlicense would pick the wrong style for them — add
# their headers by hand (CI's `-check` still enforces them).
add_license_headers() {
  # (A `while read` loop instead of `mapfile` keeps this working on the bash 3.2
  # that ships with macOS.)
  local files=()
  local f
  while IFS= read -r f; do
    [ -n "$f" ] && files+=("$f")
  done < <(staged_files_matching '\.(scala|java|py|sh|bash|html|xml|js|css|properties)$')

  if [ "${#files[@]}" -eq 0 ]; then
    return 0
  fi
  if ! command -v addlicense >/dev/null 2>&1; then
    echo "pre-commit: addlicense not found on PATH; skipping license-header insertion (CI will enforce)." >&2
    return 0
  fi

  addlicense -ignore '**/tests/resources/**' -f .license-header.tmpl -c "Calin-Andrei Burloiu" -l apache "${files[@]}"
  # Re-stage anything addlicense rewrote so the header lands in this commit.
  git add -- "${files[@]}"
}

# Formats the staged Scala and sbt files. scalafmt reads its version, its style
# and each file's dialect from .scalafmt.conf.
format_scala() {
  local files=()
  local f
  while IFS= read -r f; do
    [ -n "$f" ] && files+=("$f")
  done < <(staged_files_matching '\.(scala|sbt)$')

  if [ "${#files[@]}" -eq 0 ]; then
    return 0
  fi
  if ! command -v scalafmt >/dev/null 2>&1; then
    echo "pre-commit: scalafmt not found on PATH; skipping formatting. Install it with: cs install scalafmt" >&2
    return 0
  fi

  if ! scalafmt --non-interactive --respect-project-filters "${files[@]}"; then
    echo "pre-commit: scalafmt failed (see above). Fix the error, or bypass the hook with 'git commit --no-verify'." >&2
    exit 1
  fi
  # Re-stage the formatted files so the formatting lands in this commit.
  git add -- "${files[@]}"
}

add_license_headers
format_scala
```

Notes on the choices:

- Each function looks for its files before its tool, so a commit without in-scope files prints nothing and never
  starts scalafmt's JVM (case 6).
- The filter gains `R`, so renamed and edited files are processed (case 2). It's harmless for addlicense, which skips
  files that already have a header.
- scalafmt runs without `--quiet`, which would hide its parse error (case 3).

- [ ] **Step 4: Run the checks again**

Run: `REPO="$PWD" SCRATCH="$SCRATCH" bash "$SCRATCH/hook-check.sh"`
Expected: all eight checks PASS, `failures: 0`, exit 0.

- [ ] **Step 5: Document the hook in `docs/development/build.md`**

Append to the "Formatting" section:

````markdown
### Pre-commit hook

The pre-commit hook in [`.githooks/`](../../.githooks/pre-commit) formats the staged `.scala` and `.sbt` files with the
scalafmt command-line tool and re-stages them, so commits come out formatted even without running `sbtn fix`. Enable the
hooks once per clone with `git config core.hooksPath .githooks`, and install the tool with Coursier:

```bash
cs install scalafmt
```

The tool downloads the scalafmt version that `.scalafmt.conf` pins. If it isn't installed, the hook skips formatting
with a message. If a staged file doesn't parse, the hook prints scalafmt's error and aborts the commit. Re-staging a
whole file also stages its unstaged hunks, so a partial commit (`git add -p`) includes them; the hook's license-header
step already behaves this way.
````

- [ ] **Step 6: Document the setup in `CONTRIBUTING.md`, the prerequisites and the license-header doc**

In `CONTRIBUTING.md`, after the paragraph "See [License headers](docs/development/license-headers.md) for details.",
add:

````markdown
### Formatting

Code is formatted with scalafmt; see the [Build reference](docs/development/build.md#formatting). The git hook above
also formats the staged Scala and sbt files. For that, install the scalafmt command-line tool with
[Coursier](https://get-coursier.io/):

```bash
cs install scalafmt
```
````

In `docs/development/README.md`, add to "Prerequisites" after the `addlicense` item:

```markdown
* [scalafmt](https://scalameta.org/scalafmt/) command-line tool
    - Optional: for the formatting step of the pre-commit hook. Install with `cs install scalafmt`. See
      [`build.md`](build.md#pre-commit-hook).
```

In `docs/development/license-headers.md`, "Local git hook" section, after the paragraph ending "Enable it once per
clone:" and its code block, add:

```markdown
The same hook also formats the staged `.scala` and `.sbt` files with scalafmt; see
[`build.md`](build.md#pre-commit-hook).
```

- [ ] **Step 7: Commit**

```bash
git add .githooks/pre-commit docs/development/build.md CONTRIBUTING.md docs/development/README.md \
  docs/development/license-headers.md
git commit -m "[#333/#335] Format staged Scala and sbt files in the pre-commit hook"
```

This commit already runs the new hook. It stages no `.scala` or `.sbt` file, so the hook changes nothing.

### Task 5: Editors: Metals and IntelliJ IDEA

**Files:**

- Modify: `docs/development/build.md` ("Formatting" section), `CONTRIBUTING.md` ("Formatting" subsection)

**Interfaces:**

- Consumes: `.scalafmt.conf` (Task 3); the running dev stack (restarted in Task 1).
- Produces: nothing that later tasks call.

- [ ] **Step 1: Check that Metals formats with the repository's config**

```bash
F=format/src/main/scala/org/calinburloiu/music/microtonalist/format/JsonCommonMidiFormat.scala
scalafmt --non-interactive --stdin --assume-filename "$F" < "$F" > "$SCRATCH/cli-format.scala"
```

Call `mcp__metals__format-file` with `fileInFocus` set to `$REPO/$F`, write the returned text to
`$SCRATCH/metals-format.scala`, and run `diff "$SCRATCH/cli-format.scala" "$SCRATCH/metals-format.scala"`.
Expected: no differences, apart from possibly a trailing newline. The file is small (37 lines) and the pre-measured
config changes it, so a match shows that Metals uses `.scalafmt.conf` and not scalafmt's defaults. If
`diff "$F" "$SCRATCH/cli-format.scala"` shows that the tuned config no longer changes it, use another small file from
`$SCRATCH/scalafmt-preview.diff` instead.

- [ ] **Step 2: Check that Metals still compiles**

Call `mcp__metals__compile-full`.
Expected: success, with only the two known deprecation warnings (`TuningService.tunings`).

- [ ] **Step 3: Document the editors in `docs/development/build.md`**

Append to the "Formatting" section:

```markdown
### Editors

Metals reads `.scalafmt.conf` by itself, so formatting from an editor that uses Metals, or with the Metals MCP
`format-file` tool, matches `sbtn fix`. For IntelliJ IDEA, see [`CONTRIBUTING.md`](../../CONTRIBUTING.md#formatting).
```

- [ ] **Step 4: Document the IntelliJ IDEA setting in `CONTRIBUTING.md`**

Append to the "Formatting" subsection added in Task 4:

```markdown
To format with the same rules in IntelliJ IDEA, open **Settings → Editor → Code Style → Scala**, set **Formatter** to
**Scalafmt**, and keep the default configuration file, `.scalafmt.conf`. Optionally, also turn on **Settings → Tools →
Actions on Save → Reformat code**. `.idea/` isn't committed, so each developer does this once.
```

- [ ] **Step 5: Commit**

```bash
git add docs/development/build.md CONTRIBUTING.md
git commit -m "[#333/#335] Document formatting with Metals and IntelliJ IDEA"
```

### Task 6: Verify the tooling branch and open the draft PR

**Files:** none changed.

**Interfaces:**

- Consumes: everything from Tasks 1–5.
- Produces: the tooling PR's number, `<tooling PR>`, used in Task 7's PR body.

- [ ] **Step 1: Run the full test suite**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`
Expected: all tests pass.

- [ ] **Step 2: Coverage**

Invoke the `scoverage-inspector` skill (the CLAUDE.md Coverage step). This PR changes no Scala statements and adds no
Scala file, so its policy reduces to "no module drops below its floor". Check that with:

Run: `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`
Expected: success. Coverage commands don't work through `sbtn`, so this uses plain `sbt`.

- [ ] **Step 3: Confirm the branch's contents**

```bash
git status --short                  # expected: empty
git log --oneline main..HEAD        # expected: the design doc and plan commits, Task 1's two, and Tasks 3, 4 and 5
git diff --stat main..HEAD          # expected: only the files listed under "Tooling PR" in "File structure"
sbtn lint; echo "exit=$?"           # expected: non-zero (the code isn't formatted until the bulk PR)
```

- [ ] **Step 4: Write the PR body**

Write `$SCRATCH/pr-tooling.md`, replacing each `<…>` with its value:

```markdown
Part of #335, a sub-issue of #333. This is the first of three stacked PRs: tooling (this one), then the bulk reformat,
then the finish PR, which turns enforcement on. This PR enforces nothing, so CI stays green while the code is still
unformatted.

## Changes

- `sbt-scalafmt` 2.5.6, and a `.scalafmt.conf` pinning scalafmt 3.11.5. It's tuned to reproduce the IntelliJ IDEA
  default style that the code already follows, and keeps the author's line breaks (`newlines.source = keep`).
- sbt aliases: `fix` formats everything and `lint` checks it, including `experiments` and the build definition.
- The pre-commit hook formats the staged `.scala` and `.sbt` files. Each of its two steps is skipped on its own when
  its tool isn't installed, and a file that doesn't parse aborts the commit.
- Docs: coding conventions, build reference (new "Formatting" section), contributing guide, development setup and the
  license-header doc.
- The design doc for #333 and this sub-issue's implementation plan, under `issues/00333-lint-and-format/`.

## Config tuning

<the tuning log table from $SCRATCH/scalafmt-tuning.md>

What the bulk PR will still change: <the remaining categories, with counts, as approved in Task 3>.

<unattended run only: "## Config review", with the content of $SCRATCH/config-review.md>

<unattended run only: "## Decisions made without the user", with the entries of $SCRATCH/decisions.md>

## Verification

- `sbtn lint` fails on the unformatted code. `sbtn fix` formats every module, `experiments`, the build definition and
  new untracked files; afterwards `sbtn lint` passes and the build still loads.
- The dialect overrides apply: `scala212` for `project/*.scala`, `sbt1` for `*.sbt`, `scala3` elsewhere.
- Pre-commit hook, in throwaway repositories: it formats new, renamed and `.sbt` files; aborts on a parse error; skips
  each tool independently (also under macOS's bash 3.2); and doesn't start scalafmt for commits without Scala files.
- Metals `format-file` output matches the scalafmt CLI, and Metals `compile-full` succeeds.
- The full test suite and `coverageCheck` pass.

<the harness's PR attribution lines>
```

Check the body for closing keywords: `grep -niE '(close|fix|resolve)[sd]?:? +#[0-9]' "$SCRATCH/pr-tooling.md"` must
print nothing.

- [ ] **Step 5: Open the draft PR**

Invoke the `contributing` skill, then run its script from the usage it documents (don't read the script's source).
Don't pass an issue spec: that would add `Resolves #335`.

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr \
  "[#333/#335] Add scalafmt with a config matching the IntelliJ IDEA default style" \
  "$(cat "$SCRATCH/pr-tooling.md")" --milestone "Agentic Coding" --dry-run
```

Check the dry run: title, label `feature`, milestone, draft, no `Resolves` line. Then run the same command without
`--dry-run`.

- [ ] **Step 6: Check the PR links no issue for closing**

```bash
gh api graphql -f query='query { repository(owner: "calinburloiu", name: "microtonalist") {
  pullRequest(number: <tooling PR>) { isDraft baseRefName closingIssuesReferences(first: 10) { nodes { number } } } } }'
```

Expected: `"isDraft":true`, `"baseRefName":"main"` and `"nodes":[]`.

## Phase 2: Bulk PR (`feature/scalafmt-reformat`)

### Task 7: Generate, verify and open the bulk PR

**Files:** only the `.scala` and `.sbt` files that `sbtn fix` changes.

**Interfaces:**

- Consumes: the tooling branch; `<tooling PR>` (Task 6).
- Produces: the bulk PR's number, `<bulk PR>`, used in Tasks 8–10.

- [ ] **Step 1: Branch off the tooling branch**

Run: `git switch -c feature/scalafmt-reformat` (from `feature/scalafmt`, with a clean working tree).

- [ ] **Step 2: Run the formatter**

Run: `sbtn fix; echo "exit=$?"`
Expected: exit 0.

- [ ] **Step 3: Check that only formatter output changed**

```bash
git status --short | grep -vE '\.(scala|sbt)$'      # expected: no output
git diff --shortstat
git grep -ho '[{}]' HEAD -- '*.scala' '*.sbt' | wc -l
git grep -ho '[{}]' -- '*.scala' '*.sbt' | wc -l
git grep -hwo 'then' HEAD -- '*.scala' '*.sbt' | wc -l
git grep -hwo 'then' -- '*.scala' '*.sbt' | wc -l
```

Expected: the shortstat matches the numbers approved in Task 3. Each pair of counts is equal (the second command of a
pair reads the working tree), so no braces were added or removed and no `if … then` syntax appeared.

- [ ] **Step 4: Check that the build loads and compiles**

Run: `sbtn reload; echo "exit=$?"`, then call `mcp__metals__compile-full`.
Expected: exit 0; the compile succeeds with only the two known deprecation warnings. If the formatter output breaks
compilation, **STOP** and report: the fix belongs in `.scalafmt.conf` on the tooling branch, never in a hand edit here.

- [ ] **Step 5: Run the tests and coverage**

Run: `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`, then (with the `scoverage-inspector` skill's policy, as in Task 6)
`sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`.
Expected: both pass.

- [ ] **Step 6: Commit**

```bash
git add -u
git commit -m "[#333/#335] Reformat the code with scalafmt" -m "Output of \`sbtn fix\`, with no hand edits."
git status --short          # expected: empty
```

The hook runs scalafmt on the staged files again. It must change nothing (an empty `git status` afterwards), which
shows the output is stable.

- [ ] **Step 7: Check that `lint` passes and that the commit is reproducible**

```bash
sbtn lint; echo "exit=$?"                       # expected: exit=0
git switch --detach feature/scalafmt
sbtn fix
git diff --stat feature/scalafmt-reformat       # expected: no output
git restore -- '*.scala' '*.sbt'
git switch feature/scalafmt-reformat
```

- [ ] **Step 8: Negative checks: violations make `lint` fail (Review Focus 5)**

Run each check on its own, since `lint` stops at the first failing command. After each one, the cleanup must leave
`git status --short` empty.

```bash
# a. A module source file.
printf 'package org.calinburloiu.music.microtonalist.common\n\nobject LintScratch {\n  def f(a:Int)=a\n}\n' \
  > common/src/main/scala/org/calinburloiu/music/microtonalist/common/LintScratch.scala
sbtn lint; echo "exit=$?"       # expected: non-zero, naming LintScratch.scala
rm common/src/main/scala/org/calinburloiu/music/microtonalist/common/LintScratch.scala

# b. The experiments module, which root doesn't aggregate.
printf 'package org.calinburloiu.music.microtonalist.experiments\n\nobject LintScratch {\n  def f(a:Int)=a\n}\n' \
  > experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments/LintScratch.scala
sbtn lint; echo "exit=$?"       # expected: non-zero, naming LintScratch.scala
rm experiments/src/main/scala/org/calinburloiu/music/microtonalist/experiments/LintScratch.scala

# c. The build definition.
printf 'lazy val lintScratch=1\n' >> build.sbt
sbtn lint; echo "exit=$?"       # expected: non-zero, naming build.sbt
git restore build.sbt
sbtn reload                     # in case the build was reloaded with the scratch line
```

- [ ] **Step 9: Write the PR body**

Write `$SCRATCH/pr-bulk.md`, replacing each `<…>`:

````markdown
Part of #335, a sub-issue of #333. The second of three stacked PRs, based on #<tooling PR>.

This PR holds only the formatter's output, with no hand edits. One command reproduces it:

```bash
sbtn fix   # scalafmtAll, scalafmtSbt, experiments/scalafmtAll
```

**Reviewing it:** rather than reading the diff line by line, re-run the command on the base branch and compare:

```bash
git switch --detach <base branch>
sbtn fix
git diff --stat feature/scalafmt-reformat   # expected: no output
git restore -- '*.scala' '*.sbt' && git switch -
```

The commit is regenerated right before merging, on top of the latest `main`, so it never carries conflict resolutions.
Please merge it right after the tooling PR: in between, `main` has the tooling but not the formatted code, so `sbtn fix`
and the pre-commit hook would reformat whole files in unrelated commits. Once it's merged, the finish PR adds its
squashed SHA to `.git-blame-ignore-revs`, so `git blame` skips it.

## Verification

- `sbtn lint` passes. A deliberately unformatted file in a module, in `experiments` or in `build.sbt` makes it fail.
- No braces were added or removed and no `if … then` appeared (the counts are unchanged), so formatting didn't touch the
  brace syntax.
- The build still loads (`sbtn reload`), Metals `compile-full` succeeds, and the full test suite and `coverageCheck`
  pass.
- Re-running the pre-commit hook on the committed files changes nothing.

<the harness's PR attribution lines>
````

Check it as in Task 6: `grep -niE '(close|fix|resolve)[sd]?:? +#[0-9]' "$SCRATCH/pr-bulk.md"` prints nothing.

- [ ] **Step 10: Open the draft PR and stack it on the tooling PR**

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr "[#333/#335] Reformat the code with scalafmt" \
  "$(cat "$SCRATCH/pr-bulk.md")" --milestone "Agentic Coding" --dry-run
```

Check the dry run, then run it without `--dry-run`. The script opens the PR against `main`, so retarget it at once:
GitHub MCP `update_pull_request` with `base: "feature/scalafmt"`, or `gh pr edit <bulk PR> --base feature/scalafmt`.
Then run the GraphQL query from Task 6, Step 6 for `<bulk PR>`.
Expected: `"isDraft":true`, `"baseRefName":"feature/scalafmt"`, `"nodes":[]`. The PR's "Files changed" count matches
Step 3.

- [ ] **Step 11: STOP and report**

Give the user both PR links, the bulk PR's shortstat, and the next steps: they review the tooling PR; once they merge
it, Task 8 regenerates the bulk commit and they merge the bulk PR right away, in the same sitting; the finish PR starts
after the bulk PR merges.

**Unattended run:** if Tasks 6–7 added decision log entries after the tooling PR was opened, update the tooling PR
body's "Decisions made without the user" section (GitHub MCP `update_pull_request`). The final report lists every
decision in the log, each with how to change it, followed by anything that failed or was skipped.

### Task 8: Regenerate the bulk commit (on the user's request)

Run this task only when the user asks: when the tooling PR changes `.scalafmt.conf`, `build.sbt` or any Scala file
during review, and always right before the bulk PR merges, after the tooling PR has merged.

**Files:** the same as Task 7.

**Interfaces:**

- Consumes: `<bulk PR>`; the base `B`: `origin/feature/scalafmt` while the tooling PR is open, `origin/main` once it
  has merged.
- Produces: the regenerated bulk commit, force-pushed to `<bulk PR>`.

- [ ] **Step 1: Reset the branch onto the base**

```bash
git status --short                          # expected: empty
git fetch origin
git switch feature/scalafmt-reformat
git reset --hard "$B"
sbtn reload
```

The old bulk commit is discarded on purpose. It's regenerated in Step 2, so it never carries conflict resolutions.

- [ ] **Step 2: Regenerate and verify**

Run Task 7, Steps 2–8, with `$B` as the base in Step 7's reproducibility check instead of `feature/scalafmt`.

- [ ] **Step 3: Push and retarget**

Run: `git push --force-with-lease origin feature/scalafmt-reformat`.
If `B` is `origin/main` and the PR's base is still `feature/scalafmt`, set it to `main` (GitHub MCP
`update_pull_request`, or `gh pr edit <bulk PR> --base main`). Update the base branch named in the PR body.

- [ ] **Step 4: STOP**

Tell the user the bulk PR is regenerated and ready. Recommend a squash merge right away: until it merges, `main` has the
tooling but not the formatted code, so `sbtn fix` and the pre-commit hook reformat whole files in unrelated commits,
and other branches that move `main` make it conflict.

## Phase 3: Finish PR (`feature/scalafmt-enforce`), after the bulk PR merges

### Task 9: `.git-blame-ignore-revs`

**Files:**

- Create: `.git-blame-ignore-revs`
- Modify: `CONTRIBUTING.md` (after the `git config core.hooksPath .githooks` block)

**Interfaces:**

- Consumes: `<bulk PR>`, merged into `main`.
- Produces: `.git-blame-ignore-revs`, which #334's finish PR appends to.

- [ ] **Step 1: Confirm the merge and get the squashed SHA**

Call the GitHub MCP `pull_request_read` (method `get`) for `<bulk PR>`.
Expected: `merged: true`. Take `merge_commit_sha` as `SHA`. If the PR isn't merged, **STOP**: this phase waits for it.

- [ ] **Step 2: Create the branch and check the SHA**

```bash
git fetch origin
git switch -c feature/scalafmt-enforce origin/main
git log -1 --format='%H %s' "$SHA"                           # expected: "[#333/#335] Reformat the code with scalafmt (#<bulk PR>)"
git merge-base --is-ancestor "$SHA" origin/main && echo on-main   # expected: on-main
```

- [ ] **Step 3: Check that blame attributes a reformatted line to the bulk commit**

```bash
F=<a file listed by: git show --stat "$SHA">
git show "$SHA" -U0 -- "$F" | grep '^@@' | head -3     # pick a hunk; its "+L" is a line number in the new file
git blame -L "$L,$L" -- "$F"                                               # expected: attributed to $SHA
git blame --ignore-revs-file .git-blame-ignore-revs -L "$L,$L" -- "$F"     # expected: fatal: could not open object name list
```

Prefer a hunk that only re-indents a line: git maps it back to its previous version most reliably.

- [ ] **Step 4: Create `.git-blame-ignore-revs`**

```
# Commits that `git blame` skips: bulk changes made by a tool, with no hand edits. GitHub's blame view reads this file
# automatically; for local `git blame`, run once per clone:
#
#   git config blame.ignoreRevsFile .git-blame-ignore-revs
#
# Each entry is a commit's full SHA on `main`, after its PR was squash-merged.

# [#333/#335] Reformat the code with scalafmt (#<bulk PR>)
<SHA>
```

- [ ] **Step 5: Check that blame now skips the bulk commit**

Run: `git blame --ignore-revs-file .git-blame-ignore-revs -L "$L,$L" -- "$F"`
Expected: the line is attributed to a commit older than `$SHA`.

- [ ] **Step 6: Document `blame.ignoreRevsFile` in `CONTRIBUTING.md`**

After the `git config core.hooksPath .githooks` code block, add:

````markdown
Bulk, tool-generated changes, such as reformatting the whole codebase, are listed in `.git-blame-ignore-revs`. GitHub's
blame view skips them automatically; to make local `git blame` skip them too, run once per clone:

```bash
git config blame.ignoreRevsFile .git-blame-ignore-revs
```
````

- [ ] **Step 7: Commit**

```bash
git add .git-blame-ignore-revs CONTRIBUTING.md
git commit -m "[#333/#335] Skip the scalafmt reformat in git blame"
```

### Task 10: The CI `lint` job, the CLAUDE.md Lint step, and the finish PR

**Files:**

- Modify: `.github/workflows/scala.yml`, `AGENTS.md`, `docs/development/build.md`, `.githooks/pre-commit` (header
  comment only)

**Interfaces:**

- Consumes: the `lint` alias (Task 1); `<bulk PR>` and `SHA` (Task 9).
- Produces: the CI `lint` job, which #334's finish PR switches to strict mode.

- [ ] **Step 1: Confirm nothing enforces formatting yet**

Run: `grep -n 'lint' .github/workflows/scala.yml AGENTS.md`
Expected: no match for a `lint` job or a Lint step.

- [ ] **Step 2: Add the `lint` job**

In `.github/workflows/scala.yml`, between the `build` job and the `license-headers` job, add (same indentation as
`build`):

```yaml
  lint:

    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v4
    - name: Set up JDK 23
      uses: actions/setup-java@v4
      with:
        java-version: '23'
        distribution: 'temurin'
        cache: 'sbt'
    - uses: sbt/setup-sbt@v1
    - name: Check formatting
      run: sbt lint
```

Run: `ruby -ryaml -e 'puts YAML.load_file(".github/workflows/scala.yml")["jobs"].keys'`
Expected: `build`, `lint`, `license-headers`.

- [ ] **Step 3: Add the Lint step to the CLAUDE.md coding workflow**

In `AGENTS.md`, "Coding Workflow", add a last item to the final checks, after **Documentation** (the Documentation
check can edit ScalaDocs, so Lint comes after it):

```markdown
    - **Lint**. Run `sbtn fix` to format the code, then make sure `sbtn lint` passes.
```

- [ ] **Step 4: Update the docs and the hook comment for CI enforcement**

In `docs/development/build.md`, "Formatting" section, after the `sbtn lint` code block's paragraph, add:

```markdown
CI's `lint` job runs `sbt lint` on every pull request, in parallel with the tests.
```

In the same section's "Pre-commit hook" subsection, change "If it isn't installed, the hook skips formatting with a
message." to "If it isn't installed, the hook skips formatting with a message, and CI's `lint` job catches unformatted
code instead."

In `.githooks/pre-commit`, change the header comment's last sentence, "CI enforces the license headers via
`addlicense -check`.", to "CI enforces the license headers via `addlicense -check` and the formatting via `sbt lint`."

- [ ] **Step 5: Verify locally**

```bash
sbtn lint; echo "exit=$?"                               # expected: exit=0
REPO="$PWD" SCRATCH="$SCRATCH" bash "$SCRATCH/hook-check.sh"   # expected: failures: 0 (the comment change broke nothing)
```

Then run the full test suite (`sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`) and, with the `scoverage-inspector` skill's
policy as in Task 6, `sbt -Dmicrotonalist.build.targetSuffix=-scoverage coverageCheck`. Both must pass.
If `$SCRATCH/hook-check.sh` is gone (a new session), recreate it from Task 4, Step 1.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/scala.yml AGENTS.md docs/development/build.md .githooks/pre-commit
git commit -m "[#333/#335] Enforce scalafmt formatting in CI and in the agent workflow"
```

- [ ] **Step 7: Open the draft PR**

Write `$SCRATCH/pr-finish.md`:

```markdown
The last of the three #335 PRs. The bulk reformat merged in #<bulk PR> as `<short SHA>`.

- `.git-blame-ignore-revs` lists that commit, so `git blame` skips it. GitHub reads the file by itself, and
  `CONTRIBUTING.md` documents `blame.ignoreRevsFile` for local blame.
- A new `lint` CI job runs `sbt lint`, in parallel with the tests.
- The CLAUDE.md coding workflow gains a final **Lint** check: `sbtn fix`, then `sbtn lint`.

## Verification

- `git blame --ignore-revs-file .git-blame-ignore-revs` attributes a reformatted line to the commit before the
  reformat.
- `sbtn lint` passes locally, and the `lint` CI job is green on this PR.
- The pre-commit hook checks, the full test suite and `coverageCheck` pass.

<the harness's PR attribution lines>
```

This PR does close #335, so pass the issue spec. The script adds the `[#333/#335]` prefix and the `Resolves #335`
line:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 333/335 \
  "Enforce scalafmt formatting in CI and the agent workflow" "$(cat "$SCRATCH/pr-finish.md")" --dry-run
```

Check the dry run (title prefix, `Resolves #335`, milestone inherited from #335), then run it without `--dry-run`.
Run the GraphQL query from Task 6, Step 6 for this PR.
Expected: `"nodes":[{"number":335}]`.

- [ ] **Step 8: Wait for CI and report**

Watch the PR's checks (GitHub MCP `pull_request_read`, or `gh pr checks <finish PR> --watch`).
Expected: `build`, `lint` and `license-headers` all green. The `lint` job must be green on its first run (design
section 7). Then report the PR link and the CI result to the user. **STOP.**

---

## Design coverage

| Design item | Task |
| --- | --- |
| §2 Three squash-merged PRs, titles, branches | 6, 7, 10 |
| §2 Bulk PR: only tool output, reproducible, regenerated before merging | 7, 8 |
| §2 Finish PR: squashed SHA in `.git-blame-ignore-revs`; `blame.ignoreRevsFile` docs | 9 |
| §3 Plugin and config, starting settings | 1 |
| §3 Tuning loop, IntelliJ `format.sh`, the user's sample review | 2, 3 |
| §3 `fix` and `lint` aliases | 1 |
| §3 Pre-commit hook: format and re-stage, skip without the CLI, independent steps | 4 |
| §3 Metals and IntelliJ IDEA | 5 |
| §3 Enforcement: CI `lint` job, CLAUDE.md Lint step | 10 |
| §3 Docs: coding conventions, `build.md`, `CONTRIBUTING.md` | 1, 4, 5, 9, 10 |
| §7 Negative checks (unformatted code fails `lint`) | 1, 7 |
| §7 Bulk PR reproducible | 7, 8 |
| §7 Tests and `coverageCheck` after every PR | 6, 7, 10 |
| §7 Hooks and editors | 4, 5 |
| §7 The finish PR's first `lint` run is green | 10 |
