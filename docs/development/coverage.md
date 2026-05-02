# Coverage

> For routine coverage inquiries — checking a class's coverage, finding gaps, verifying a module still meets
> its threshold — prefer the `scoverage-inspector` skill over running these commands by hand.

Code coverage is measured via [scoverage](https://github.com/scoverage/sbt-scoverage). Each SBT project has
per-module statement and branch thresholds configured in `build.sbt` via the `coverageSettings` helper.

The project-wide target is **80% statement and branch coverage for every module**. Modules that have not yet
reached 80% are configured with their current coverage minus a 3% buffer and an open issue tracking the work
needed to reach 80%.

**Per-module coverage must never decrease below the configured threshold.** When changing code in a module:

- The threshold in `build.sbt` is a floor, not a target. It can stay flat or be raised toward 80%, but never lowered.
- If your change reduces coverage below the configured threshold, add tests so it stays at or above the threshold.
- If your change raises coverage, you may raise the threshold in `build.sbt` accordingly, but keep the 3% buffer. Once
  both statement and branch reach 80%, switch the module to `coverageSettings(stmt = 80, branch = 80)` and close the
  tracking issue.
- **New files must always meet the 80% statement and branch coverage target on their own**, regardless of the module's
  current threshold. The per-module floor exists to track legacy code paying down toward 80%; it is not a license for
  newly authored code to ship under-tested.

## Running coverage

**Run coverage as the final step of any code-changing task, before committing**, to verify that the module's
configured threshold still holds and that any new files meet the 80% target. Pick the scope that matches your change:

- **Larger or multi-module changes** — run the full project-wide workflow with `sbt coverageAll`. Per-module reports
  plus an aggregate report are produced. The aggregate combines each module's tests with the tests of dependent
  modules.
- **Smaller changes scoped to one or a few modules** — run `sbt "coverageModules <module> [<module> ...]"`, where
  each `<module>` is an sbt project ID (e.g. `intonation`, `tuner`, `appConfig`). At least one module must be
  supplied. Only the listed modules' tests run, so coverage is not inflated by tests from other modules exercising
  the same code, and all listed modules share a single coverage session.

Both commands are defined in `project/Coverage.scala`; see its ScalaDoc for the workflow's implementation details.
There is also a `coverageCheck` command used by CI.

The two-pass workflow exists to work around a known sbt-scoverage + Scala 3 multi-module compile bug. If a coverage
command fails with TASTy/companion-class errors, `Not found: type X`, or `NoClassDefFoundError` at test runtime, see
[`docs/development/scoverage-issue.md`](scoverage-issue.md) before assuming it is a code defect — the typical response
is to retry the command, not to change source code.

**Coverage commands do not work via `sbtn`** — run them through a fresh `sbt` JVM instead. This is the one
exception to the "prefer `sbtn`" rule in `AGENTS.md`.

Coverage commands redirect every project's `target` to `target-scoverage/` for the duration of the run,
regardless of the `microtonalist.targetSuffix` system property. This keeps instrumented `.class`/`.tasty` files
completely separate from the regular `target/` and BSP `target-bsp/` trees, so **no post-run `sbt clean` is
required** — neither the incremental compiler nor `sbt run` / `sbt assembly` will see instrumented bytecode.

```bash
sbt coverageAll
```

```bash
sbt "coverageModules intonation"
```

```bash
sbt "coverageModules tuner intonation"
```

Coverage data and reports live at the repo root under `coverage-reports/<project-id>/scoverage-report/` (configured
via `coverageDataDir` in `build.sbt`); the aggregate is at `coverage-reports/root/scoverage-report/`. Both
`coverage-reports/` and the per-project `target-scoverage/` directories live outside the normal `target/` tree, so
`sbt clean` does **not** wipe them. Run `sbt coverageClean` to discard all coverage artefacts — reports and
instrumented class files — in one shot.
