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

The project introduces custom coverage commands, defined in `project/Coverage.scala`; see its ScalaDoc for the
workflow's implementation details. Run them through the wrapper scripts in [`bin/`](../../bin/README.md#mtlist-coverage-),
which also isolate the coverage build (see below).

**Run coverage as the final step of any code-changing task, before committing**, to verify that the module's
configured threshold still holds and that any new files meet the 80% target. Pick the scope that matches your change:

- **Larger or multi-module changes** — run the full project-wide workflow with `bin/mtlist-coverage-all` (sbt's
  `coverageAll`). Per-module reports plus an aggregate report are produced. The aggregate combines each module's tests
  with the tests of dependent modules.
- **Smaller changes scoped to one or a few modules** — run `bin/mtlist-coverage-modules <module> [<module> ...]` (sbt's
  `coverageModules`), where each `<module>` is an sbt project ID, equal to the module's base directory name (e.g.
  `intonation`, `tuner`, `config`, `sc-midi`). At least one module must be supplied.

```bash
bin/mtlist-coverage-all
```

```bash
bin/mtlist-coverage-modules intonation
```

```bash
bin/mtlist-coverage-modules tuner intonation
```

There is also `bin/mtlist-coverage-check`, which runs CI's `coverageCheck` command: the same workflow as `coverageAll`,
with only the XML reports.

Each module's report counts only the module's own tests, never those of other modules that exercise its code. To check
it, `common`'s coverage must be the same in both of these runs, although `format`'s tests exercise `common`:

```bash
bin/mtlist-coverage-modules common
bin/mtlist-coverage-modules common format
```

For that, the commands test and report the modules one at a time, dependencies first. They stop at the first module
whose tests fail or whose coverage is below its threshold, so a failing module hides the results of the modules after
it, in CI too.

All three begin with `clean`, so you need not `sbt clean` beforehand.

The scripts run sbt in a fresh JVM with `-Dmicrotonalist.build.targetSuffix=-scoverage`, which builds into
`<project>/target-scoverage/`. That keeps the instrumented coverage build from clashing with concurrent builds of the same
code without instrumentation, such as an IDE's build in `target/` (IntelliJ IDEA is known to cause such issues) or the
development stack's in `target-bsp/`, which can keep running meanwhile. They also pass `-Dsbt.server.autostart=false`,
so that their sbt starts no sbt server: run while the development stack is down, it would take the build's server
socket, which `sbtn` would then connect to and which would keep the stack from starting. If you run a coverage command
with `sbt` directly, pass the same options.

**Coverage commands do not work via `sbtn`** — `sbtn` runs them on the development stack's sbt server, which builds into
`target-bsp/` whatever options `sbtn` gets. This is the one exception to the "prefer `sbtn`" rule in `AGENTS.md`.

If a coverage command fails with TASTy/companion-class errors, `Not found: type X`, or `NoClassDefFoundError` at test
runtime, see [`docs/development/scoverage-issue.md`](scoverage-issue.md) before assuming it is a code defect — the
typical response is to retry the command, not to change source code.

Coverage data and reports live at the repo root under `coverage-reports/<project-id>/scoverage-report/` (configured
via `coverageDataDir` in `build.sbt`); the aggregate is at `coverage-reports/root/scoverage-report/`. The
`coverage-reports/` directory lives outside `target/`, so `sbt clean` does **not** wipe it — reports remain
browsable after a subsequent clean. Run `sbt coverageClean` to discard the persisted reports explicitly.
