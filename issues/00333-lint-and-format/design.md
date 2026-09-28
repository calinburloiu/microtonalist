# Design: linting and scalafmt formatting

- **Date:** 2026-09-27
- **Base commit:** `2b21116efc3789f08af0cd2585168ef63563e212` (`main`)
- **Issues:** #333 (parent), with sub-issues #335 (scalafmt), #334 (compiler warnings and scalafix) and #336 (Scala and
  dependency upgrade)
- **Status:** approved in conversation, section by section; pending review of this written version
- **Revised:** 2026-09-28, with the decisions taken while planning #334 (`plan-334-lint.md`, which the #334 tooling PR
  adds, "Decisions after the design") and the facts that planning measured: sections 1, 4, 5, 6, 7, 8 and appendix B

Each sub-issue gets its own implementation plan in this directory.

## 1. Context and goals

Before this work, the build had no automated code-quality checks:

- `scalacOptions` was only `-deprecation -feature -unchecked` (plus encoding, language imports and `-experimental`).
- Nothing turned warnings into errors.
- There was no scalafmt, scalafix or WartRemover. Formatting relied on IntelliJ IDEA's default settings, and `.idea/`
  is gitignored, so there was no committed project code style.
- A full compile produced only 2 warnings, both deprecations (`TuningService.tunings`).

**Primary goal:** enforce the conventions in `docs/development/coding-conventions.md` and
`docs/development/test-conventions.md` mechanically, so that neither coding agents nor humans drift from them. Local
builds must fail on violations: an agent notices a failure in its own compile loop right away, whereas a warning, or a
CI failure that arrives much later, tends to get ignored.

**Secondary goals:** catch bugs early, and remove unused code and imports.

### Decisions

| Topic | Decision |
| --- | --- |
| Tools | Compiler warning flags plus scalafix (#334); scalafmt (#335). No WartRemover or Scapegoat for now. |
| Style | scalafmt reproduces the current style (IntelliJ IDEA defaults) as closely as possible. |
| Local strictness | Warnings are compile errors locally, except the one warning a TDD red-phase stub needs, and unused imports, which `sbtn fix` removes. |
| CI strictness | Strict: no exceptions apart from deprecations. |
| Deprecations | Always warnings, never errors. |
| Unused code | scalafix removes unused imports only. The compiler reports any other unused code, which is fixed by hand. |
| Scalafix locally | Enforced through the CLAUDE.md coding workflow (a "Lint" final check), not through compilation. |
| Warnings and agents | Agents never ignore a warning: CLAUDE.md says so, and the Lint step ends with CI's strict check. |
| Automatic formatting | The pre-commit hook rewrites staged files with scalafmt. |
| Bulk changes | Each bulk change goes in its own squash-merged PR, and its squashed SHA goes in `.git-blame-ignore-revs`. |
| Scala version | Stay on 3.6.3 for #334/#335; upgrade to 3.8.x, and all dependencies, in #336. |
| "Avoid `new`" | Becomes a recommendation, not enforced. |

## 2. Delivery structure

### Order

1. The #335 stack (scalafmt).
2. The #334 stack (compiler warnings and scalafix). Going second means the scalafix autofix output is formatted by the
   same `fix` alias.
3. #336 (Scala and dependency upgrade), which runs after both stacks and doesn't depend on them otherwise.

### PR stacks

Each of #335 and #334 is delivered as three squash-merged PRs, each one based on the previous PR's branch:

| Sub-issue | Tooling PR | Bulk PR | Finish PR |
| --- | --- | --- | --- |
| #335 | `feature/scalafmt` | `feature/scalafmt-reformat` | `feature/scalafmt-enforce` |
| #334 | `feature/lint` | `feature/lint-autofix` | `feature/lint-enforce` |

PR titles use the `[#333/#335]` and `[#333/#334]` prefixes.

- **Tooling PR:** configuration, plugins, hooks, docs and hand fixes. It enforces nothing yet, so the build stays
  green while the codebase is still unformatted and unfixed.
- **Bulk PR:** only tool output, with no hand edits. One command reproduces it: `sbtn scalafmtAll scalafmtSbt` (plus
  `experiments/scalafmtAll`) for #335, and `sbtn fix` for #334. Reviewers check it by re-running the command and
  diffing, not by reading it line by line. It's regenerated right before merging, after rebasing on the latest `main`,
  so it never carries conflict resolutions. Merge it right after the tooling PR, in one sitting: in between, `main`
  has the tooling but not the tool output, so `sbtn fix` and the pre-commit hook would put it into unrelated commits.
  Merging it soon also keeps branches in progress from conflicting with it.
- **Finish PR:** after the bulk PR merges, rebase the finish branch onto `main` and add the bulk PR's **squashed SHA
  on `main`** to `.git-blame-ignore-revs`. #335's finish PR creates that file. The same PR turns on that sub-issue's
  enforcement.

Why the finish step waits for the merge: squash-merging discards the branch commit SHAs, so a SHA can only go into
`.git-blame-ignore-revs` once the squashed commit exists on `main`. Keeping the bulk change in a PR of its own means
the ignored commit contains nothing but mechanical changes.

GitHub's blame view reads `.git-blame-ignore-revs` automatically. For local `git blame`, contributors run
`git config blame.ignoreRevsFile .git-blame-ignore-revs`, documented next to the existing `core.hooksPath` step.

### Documents

- This design doc is committed in the first PR (the #335 tooling PR).
- Each sub-issue's plan (`plan-335-scalafmt.md`, `plan-334-lint.md`, `plan-336-upgrade.md`) lives in this directory
  and is committed in that sub-issue's first PR.

## 3. #335: scalafmt

### Plugin and config

- `project/plugins.sbt`: `addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.6")`. It's the newest release that runs
  on sbt 1.10.7: every 2.6.x release refuses to run on sbt older than 1.12.9. #336 upgrades sbt, and then the plugin.
  The plugin downloads the scalafmt version that `.scalafmt.conf` pins, so the output doesn't depend on it.
- `.scalafmt.conf` at the repo root, with `version = 3.11.5`. The `.conf` extension isn't covered by the license
  header check, just like the existing HOCON files.

### Tuning the config to the current style

The target is the current code, which IntelliJ's default formatter produced. The loop:

1. Run `scalafmtAll` (plus `scalafmtSbt` and `experiments/scalafmtAll`).
2. Measure `git diff --shortstat` and group the changes by kind.
3. Adjust one setting.
4. Reset and repeat.

The loop stops when what's left fixes real inconsistencies rather than changing the style. If IntelliJ's command-line
formatter (`format.sh`, which may need the IDE closed) can run, its default output on sample files settles unclear
cases. Before the bulk PR, the user reviews sample files for any remaining style differences.

Starting settings, based on the existing code:

| Setting | Value | Reason |
| --- | --- | --- |
| `runner.dialect` | `scala3`; `sbt1` for `*.sbt`; `scala212` for `project/*.scala` | Each file uses its own dialect |
| `maxColumn` | `120` | The documented line length |
| `indent.main` | `2` | The documented indentation |
| `newlines.source` | `keep` | Keeps existing line breaks the way IntelliJ does (see below) |
| `align.openParenDefnSite` (and `align.openParenCallSite`, if the code shows it) | `true` | Matches `class MpeTuner(private val …,\n               private val …) extends Tuner {` |
| `danglingParentheses.*` | tuned | The code keeps `)` on the last line rather than on its own line |
| `docstrings.style` | `Asterisk` | The existing ` * ` ScalaDoc style |
| `docstrings.wrap`, `comments.wrap` | `keep`, `no` | scalafmt can't break only the comment lines that are too long: it refills every ScalaDoc paragraph, or every multi-line `/* */` comment, license headers included |
| `rewrite.scala3.convertToNewSyntax`, `rewrite.scala3.removeOptionalBraces` | `false` | Brace syntax convention |
| Import sorting | not configured | #334's `OrganizeImports` owns import order |
| `project.git` | not set | `true` would leave new files unformatted until they're `git add`ed. sbt formats only its source directories, and the hook passes file names |

#### Why `newlines.source = keep`

`newlines.source` decides who controls line breaks: the author or scalafmt.

| Mode | Existing line breaks | New line breaks |
| --- | --- | --- |
| unset (the default, "classic") | Some are kept (e.g. one argument per line); most others are joined if the result fits | Where scalafmt's heuristics choose |
| **`keep`** | **Kept wherever the syntax and the other settings allow** | **Where a code line would exceed `maxColumn`, and where a few settings require one** |
| `fold` | Removed wherever possible | Only when needed to fit |
| `unfold` | Ignored | Once a construct doesn't fit on one line, every element gets its own line |

`keep` is the closest match to IntelliJ, whose default reformat also keeps the line breaks the author wrote ("Keep when
reformatting → Line breaks"). The bulk diff shrinks to indentation, spacing and alignment fixes, and lines over 120
columns are still split. **Trade-off:** line breaks aren't standardized. Two authors can break the same expression
differently and both pass `scalafmtCheck`. The setting can be tightened later, one construct at a time.

### sbt aliases (in `build.sbt`)

- `fix`: `scalafmtAll`, `scalafmtSbt`, `experiments/scalafmtAll`.
- `lint`: `scalafmtCheckAll`, `scalafmtSbtCheck`, `experiments/scalafmtCheckAll`.

`root` doesn't aggregate `experiments`, which is why both aliases name it explicitly. #334 extends both aliases.

### Pre-commit hook

`.githooks/pre-commit` gets a scalafmt step:

- It formats staged `.scala` and `.sbt` files with the `scalafmt` command-line tool (installed with
  `cs install scalafmt`), which reads the version from `.scalafmt.conf`. It then re-stages the files, as the hook
  already does for addlicense.
- If `scalafmt` isn't on `PATH`, it skips with a message, and CI enforces formatting instead.
- The hook is restructured so each tool is skipped independently. Right now it exits early when `addlicense` is
  missing, which would skip the scalafmt step as well.
- **Known and accepted limitation:** re-staging a whole file pulls in any unstaged hunks, so partial commits
  (`git add -p`) include them. The addlicense step already behaves this way.

### Editors and agents

- Metals picks up `.scalafmt.conf` by itself, so editors that use Metals format like `sbtn fix`. Agents format with
  `sbtn fix`: with the development stack's standalone Metals client, the Metals MCP `format-file` tool doesn't write
  its edits to the file.
- `.idea/` stays gitignored. The docs explain how to set IntelliJ's Scala formatter to Scalafmt (Settings → Editor →
  Code Style → Scala → Formatter), optionally with reformat on save.

### Enforcement (finish PR)

- A new `lint` job in `.github/workflows/scala.yml` runs `sbt lint`, in parallel with the existing test job.
- The CLAUDE.md coding workflow gains a **Lint** final-check task: run `sbtn fix`, then `sbtn lint`.
- `.git-blame-ignore-revs` is created, containing the #335 bulk PR's squashed SHA.

### Docs

- `docs/development/coding-conventions.md`: "General formatting" changes from IntelliJ defaults to scalafmt.
- `docs/development/build.md`: the `fix` and `lint` commands.
- `CONTRIBUTING.md`: installing the scalafmt CLI, `blame.ignoreRevsFile`, and IntelliJ's formatter setting.

## 4. #334: compiler warnings

### Flags

These are added to `compilerOptions` in `build.sbt`:

| Flag | Scope | Purpose |
| --- | --- | --- |
| `-Wunused:all` | main and test | Unused imports, private members, locals, parameters, and `@nowarn` annotations that suppress nothing |
| `-no-indent`, `-old-syntax` | main and test | Compile *errors* for indentation syntax and `if x then`, enforcing the brace convention |
| `-Wvalue-discard`, `-Wnonunit-statement` | **main only** | ScalaTest's `shouldBe` returns `Assertion`, so these would fire on nearly every test line. `Test / scalacOptions` inherits `Compile / scalacOptions`, so the build removes them from `Test` explicitly (verified with `show Test/scalacOptions`). |

Not included: `-Wsafe-init` (slower compiles, and no documented convention needs it) and `-Wshadow`. Either can be
added later.

### Warnings policy

The build property `microtonalist.build.strictWarnings` works like the existing `microtonalist.build.targetSuffix`:

| Mode | Used by | `-Wconf` |
| --- | --- | --- |
| Default | Local builds, Metals/BSP (`sbtn`), IntelliJ, the CI coverage job | `-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w,msg=unused import:w` |
| Strict (`-Dmicrotonalist.build.strictWarnings=true`) | CI `lint` job, the last check of the CLAUDE.md Lint step | `-Wconf:any:e,cat=deprecation:w` |

- **Default mode** turns every warning into a compile error except deprecations and two more:
  - "unused explicit parameter", the only warning a red-phase stub triggers (see appendix A);
  - "unused import", so that the code still compiles and `sbtn fix` can remove it: scalafix only runs on code that
    compiles, so an error-level unused import could only be removed by hand. `sbtn lint` fails on it all the same,
    through the `OrganizeImports` check.
- **Strict mode** makes both fatal too, so an unused parameter left over after the green phase, or an unused import,
  fails CI.
- **Deprecations are never fatal.**
- The long-running `sbtn` server can't take the property for a single command. A strict local run therefore uses
  plain `sbt -Dmicrotonalist.build.strictWarnings=true lint`, which spawns a separate JVM that writes to `target/`, so
  it doesn't collide with the server's `target-bsp/`. The CLAUDE.md Lint step ends with it, so that an agent finishes a
  task with no warning left but deprecations.

**`-Wconf` ordering (verified on 3.6.3, appendix A):** within a single `-Wconf` option, the **rightmost** matching rule
wins, even though the compiler's help text says "leftmost". The broad `any:e` must therefore come first, followed by
the exceptions. Any change to these strings must be re-tested.

**Phase quirk (verified):** a fatal error in an earlier compiler phase (e.g. unused code) stops compilation before
later phases report their warnings (e.g. deprecations). Those warnings appear on the next compile, once the errors are
fixed.

### Exceptions

- Use `@nowarn("msg=…")` at the narrowest scope, with a comment giving the reason, and a `// TODO #<issue>` if it's
  temporary.
- `-Wunused:nowarn` (part of `all`) flags any `@nowarn` that no longer suppresses anything.

### When it turns on

- **#334 tooling PR:** adds the flags without any `-Wconf` policy, so they're plain warnings. Hand fixes happen while
  the build stays green.
- **#334 finish PR:** adds the `-Wconf` policy and the strict property, after the bulk PR has removed the mechanical
  violations.

### CLAUDE.md red-phase note

Next to the existing "thinnest possible stub" rule: "A stub may leave constructor parameters unused; that warning is
allowed until green. Everything else must compile."

## 5. #334: scalafix

### Setup

- `project/plugins.sbt`: `addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.9")`.
- `ThisBuild / semanticdbEnabled := true`. Scala 3 produces SemanticDB itself, and BSP builds already enable it.

### Rules (`.scalafix.conf`)

The plan's first step checks that each rule works on Scala 3.6.3, and drops any that doesn't.

- **No `RemoveUnused`.** scalafix removes unused imports only, through `OrganizeImports`. The compiler reports any other
  unused code (methods, values, classes, parameters), which is fixed by hand. On a scratch copy of the code,
  `RemoveUnused` deleted `TrackManager`'s private `@Subscribe` handlers, which only Guava's `EventBus` calls, and the
  build stayed green; it also turned unused test values into dead statements, one of which hid a missing check.
- **`OrganizeImports`**, configured to match IntelliJ's default layout, which the existing code follows:
  - Groups: other packages, one blank line, then `java`, `javax` and `scala` with no blank line between them:
    `blankLines = Manual` and `groups = ["*", "---", "re:javax?\\.", "scala."]`. The layout without `---`, one blank
    line between each group, changed 110 files, against 75.
  - `targetDialect = Scala3`, `removeUnused = true`, `groupedImports = Keep`.
  - How many imports from one package get merged into a `*` import (`coalesceToWildcardImportThreshold`) is tuned by
    minimizing the bulk diff, like scalafmt. Measured: left unset; a threshold of 5 made the diff bigger.
  - The plan checks that IntelliJ's "Optimize imports" produces the same result, so the IDE and the CI check never
    undo each other.
- **`DisableSyntax`:** each check's message points to the relevant section of the convention docs.
  - `noReturns = true`.
  - Regex `//\s*TODO(?! #\d+)` for TODOs without an issue number. It deliberately doesn't match a mid-sentence
    "the onAttach TODO above".
  - Regex `Thread\.sleep`. The one production use (the shutdown hook in `MicrotonalistApp`) gets a
    `// scalafix:ok DisableSyntax.threadSleep` comment, and a comment giving the reason.
  - `noReturns` has a fixed message; only the regex checks take a custom one.
  - Regex `AnyFlatSpec`.
- **`NoValInForComprehension`, `RedundantSyntax`:** cheap syntax checks.

### Documented conventions and how each is enforced

The same "Enforced by …" information is added to each entry in the convention docs.

| Convention | Enforced by |
| --- | --- |
| Brace syntax | `-no-indent`, `-old-syntax`, and scalafmt (`removeOptionalBraces = false`) |
| 2-space indentation, 120-column lines | scalafmt |
| No `return` | `DisableSyntax.noReturns` |
| TODOs have issue numbers | `DisableSyntax` regex |
| No sleeping in tests | `DisableSyntax` regex |
| Tests use `AnyWordSpec`, not `AnyFlatSpec` | `DisableSyntax` regex |
| No unused code | `-Wunused:all`; `OrganizeImports` removes unused imports |
| Avoid `new` | Not enforced. Reworded as a recommendation ("Prefer omitting `new`"); the code has 539 existing `new X(...)` calls. |
| `enum` for simple enumerations; no `case class` with `var` fields; for-comprehensions for nested monads; `_value` backing fields; ScalaDoc on public identifiers; Given/When/Then comments; no `if` around assertions; fixtures; shared test utilities | Not enforced; documented only |

### Existing violations

**Tooling PR, fixed by hand:**

- The 2 `return` statements, in `MergeTuningReducer` and `JsonPreprocessorHttpRefLoader` (which has no test yet):
  rewritten with `boundary`.
- The 3 TODOs without an issue number, in `PlatformUtils.scala`, `TuningMapper.scala` and `TuningReference.scala`:
  each gets an issue number or is removed, decided with the user one by one.
- The `Thread.sleep` in `ConcurrentMidiTransmitterTest`: replaced with a latch, per the test conventions.
- Whatever `-Wvalue-discard` and `-Wnonunit-statement` report, plus every `-Wunused` finding other than an unused
  import. Unused imports are left for the bulk PR. **The plan starts by counting these per module and category. If
  there are too many to fix by hand in one PR, stop and re-scope with the user.** Measured while planning: 24 (14
  discarded values, 7 unused private members, 3 unused local definitions).
- The 2 deprecated `TuningService.tunings` uses stay as they are, since deprecations remain warnings.

**Bulk PR:** only the output of `sbtn fix` (scalafix autofixes, then scalafmt): unused imports removed, imports ordered,
and redundant syntax simplified. It removes no definition.

### Aliases and enforcement

- **Tooling PR:** `fix` becomes `scalafixAll`, `experiments/scalafixAll`, then the #335 formatting commands, since the
  bulk PR is its output. `lint` doesn't change yet: its new checks would fail CI before the bulk PR merges.
- **Finish PR:** `lint` becomes `Test/compile` and `experiments/Test/compile`, `scalafixAll --check` and
  `experiments/scalafixAll --check`, then the #335 format checks.
- CI's `lint` job runs `sbt -Dmicrotonalist.build.strictWarnings=true lint`.
- The `-Wconf` policy from section 4 turns on.
- `.git-blame-ignore-revs` gets the #334 bulk PR's squashed SHA.

### Docs

- **New `docs/development/linting.md`**, read on demand. It holds the rationale: what each flag and rule does, the
  warnings policy, the strict property, the `-Wconf` ordering and phase quirks, rules for suppressing warnings, and how
  to add a rule.
- **CLAUDE.md** keeps only the facts an agent needs every time:
  - Never ignore a warning: deal with each one the change introduced before the task ends.
  - Warnings are errors, except red-phase constructor parameters, unused imports (`sbtn fix` removes them) and
    deprecations (don't add a use of a deprecated API).
  - Nothing removes other unused code automatically. Before deleting an "unused" private method, check that nothing
    calls it through reflection. Before deleting an unused test value, check whether the case forgot to check it.
  - The Lint step is `sbtn fix`, then `sbtn lint`, then CI's strict check,
    `sbt -Dmicrotonalist.build.strictWarnings=true lint`, which fails on every warning but a deprecation.
  - The `@nowarn` and `// scalafix:ok` rules.
- **`coding-conventions.md` and `test-conventions.md`:** the "Enforced by …" notes, and the reworded "Avoid `new`".
- **`build.md`:** the strict property.

## 6. #336: Scala and dependency upgrade

**When:** after the #334 stack merges. Warnings are fatal by then, so the upgrade has to arrive warning-free, and any
shift in the new checker's output shows up immediately. It's a single PR, unless the new checker's findings are large,
in which case the same tooling, bulk and finish split applies.

**Scope:**

- **Scala:** `scalaVersion` goes from 3.6.3 to the latest 3.8.x (currently **3.8.4**).
- **All external dependencies, where possible:**
  - The libraries in `project/Dependencies.scala`: coremidi4j, ficus, guava, logback, play-json, scala-logging,
    scalamock, scalatest.
  - Any dependencies declared inline in `build.sbt`.
  - The sbt plugins: sbt-assembly, sbt-buildinfo, sbt-scoverage, sbt-scalafmt, sbt-scalafix; plus scalafmt's
    `version`. sbt-scalafmt goes from 2.5.6 to 2.6.x once sbt is upgraded (see the `TODO #336` in
    `project/plugins.sbt`).
  - sbt itself, within 1.x, and to at least 1.12.9, which sbt-scalafmt 2.6.x requires.
  - A dependency that can't be upgraded (incompatible, or needing a code migration out of scope) stays where it is, and
    the reason is recorded in the PR.
- **Compatibility checks**, each confirmed by a full compile and test run:
  - scalamock, which needs `-experimental`
  - play-json
  - sbt-scoverage, whose instrumentation is built into the Scala 3 compiler
  - sbt-assembly
  - Metals 1.6.9, which must support the new Scala version for the BSP and MCP tools to keep working
  - scalafix reading the new SemanticDB output
- **New checker findings:** fix what the reworked `-Wunused` reports. Any `@nowarn` added in #334 that becomes
  unnecessary is flagged by `-Wunused:nowarn` and removed.
- **Red-phase exception:** keep `msg=unused explicit parameter:w`. Scala 3.8.4 still warns about unused constructor
  parameters (appendix A); only the private-`???` false positive goes away. Keep `msg=unused import:w` too, and check
  that the unused-import message still matches it.
- **`-Wconf` ordering:** re-test on the new version with the scratch-file method from appendix A.

**Out of scope:** new warning flags that 3.7 or 3.8 may add. They'd go in a separate issue if wanted.

## 7. Verification

These changes are build configuration, with no production logic to drive by tests. The few hand fixes (`return` to
`boundary`, sleep to latch) are behavior-preserving refactors that the existing tests cover. Each piece is proven
instead:

- **Negative checks:** an uncommitted scratch file holds one deliberate violation per rule: indentation syntax, an
  unused import, an unused private method, `return`, a bare TODO, `Thread.sleep`, `AnyFlatSpec`, and unformatted code.
  Each must fail `compile` or `lint`, and then the file is deleted. A red-phase stub and an unused import must compile
  in default mode and fail in strict mode. `sbtn fix` must remove an unused import, and must fail on an unused private
  method, leaving it in place.
- **Bulk PRs are reproducible:** re-running the command on the base commit gives an empty diff against the PR.
- **Tests and coverage:** the full test suite (`sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`) and `coverageCheck` pass
  after every PR. A scalafix removal that breaks a `given` import fails compilation.
- **Hooks and editors:** the pre-commit step formats a staged, badly formatted file, and skips cleanly when
  `scalafmt` isn't on `PATH`. Metals `compile-full` still works, and Metals reads `.scalafmt.conf`.
- **CI:** the finish PR's first `lint` run is green.

## 8. Risks

| Risk | Mitigation |
| --- | --- |
| scalafmt can't exactly reproduce some IntelliJ default | Tune the config to minimize the diff; the user reviews sample files for any remaining style differences before the bulk PR |
| `OrganizeImports` and IntelliJ's "Optimize imports" disagree | Tune them to agree; if they can't, document "run `sbtn fix` instead of IntelliJ's Optimize Imports" |
| A scalafix rule doesn't support Scala 3.6.3 | Drop it; the compiler still covers unused code |
| Agents ignore the warnings that stay warnings locally | CLAUDE.md tells them to deal with each one; the Lint step ends with CI's strict check |
| An unused private method is only called through reflection (e.g. Guava's `@Subscribe`) | No automatic removal; `@nowarn` with the reason; tests cover the handlers |
| Scala 3 coverage instrumentation emits warnings, which become fatal in `coverageCheck` | Check early in #334; if needed, a narrow `-Wconf` rule for instrumented builds |
| Too many hand fixes in #334 | Count them first; stop and re-scope with the user |
| Bulk PRs conflict with branches in progress | Merge them soon after they open; other branches rebase and run `sbtn fix` |
| A module failing to compile degrades Metals features for that module | Same as any compile error today; accepted |
| The pre-commit hook pulls unstaged hunks into partial commits | Accepted, as with addlicense |
| The extra CI `lint` job recompiles everything | It runs in parallel with the test job, so wall-clock time barely changes |

## Appendix A: compiler experiments

Scratch files were compiled directly with the Scala compiler JARs from the Coursier cache, outside the repo.

**Which `-Wunused:all` warnings typical stubs trigger:**

| Construct | 3.6.3 | 3.8.4 |
| --- | --- | --- |
| Parameters of a public `def f(a: Int): Int = ???` | no warning | no warning |
| Unused constructor parameter, e.g. `class Foo(x: Int)` ("unused explicit parameter") | **warns** | **warns** |
| Private `def g(c: Int): Int = ???` ("unused private member") | **warns** | no warning |
| Unused import, unused local, private method that's never called | warns | warns |
| Unused parameter of a public method with a real body, or of an overriding method | no warning | no warning |

**`-Wconf` behavior on 3.6.3:**

- `-Wconf:msg=unused explicit parameter:w,any:e` makes **everything** an error: `any:e` is rightmost, so it wins.
- `-Wconf:any:e,msg=unused explicit parameter:w` makes the unused constructor parameter a warning and everything else
  an error. A red-phase stub compiles with "1 warning found".
- `-Wconf:any:e,cat=deprecation:w,msg=unused explicit parameter:w` also keeps a deprecation a warning. Deprecations are
  reported only when no earlier phase failed.

## Appendix B: existing violations at the base commit

| Check | Count |
| --- | --- |
| `new X(...)` calls | 539 (not enforced) |
| TODOs without an issue number | 3 |
| `return` statements | 2 (`MergeTuningReducer`, `JsonPreprocessorHttpRefLoader`) |
| `Thread.sleep` | 1 in a test (`ConcurrentMidiTransmitterTest`), 1 in production (`MicrotonalistApp`) |
| `AnyFlatSpec` | 0 |
| Indentation syntax or `if … then` | 0 found by grep; `-no-indent` / `-old-syntax` will confirm |
| Compiler warnings (existing flags) | 2 deprecations |
| Compiler warnings with the #334 flags (measured while planning #334) | 37: 14 discarded values, 11 unused imports, 7 unused private members, 3 unused local definitions, 2 deprecations |
| Tracked Scala sources | 245 files, about 45.5k lines |
