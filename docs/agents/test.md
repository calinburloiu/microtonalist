# Running Tests (Agents)

This document describes how to run the test suite as a coding agent. For conventions on *writing* tests (BDD style,
Given/When/Then, fixtures, shared test utilities), see
[`../development/test-conventions.md`](../development/test-conventions.md).

Currently, Metals MCP cannot run tests with this setup (with SBT and BSP). So run all tests through `sbtn` so they
execute on the BSP server (see the "sbt invocations: prefer the BSP server via `sbtn`" section in the root `CLAUDE.md`
and [`dev-stack.md`](dev-stack.md)).

For small changes, it is recommended to only test individual files or modules (SBT projects). It is recommended to run
the full suite before finishing an issue.

Always append the ScalaTest reporter flags `-- -oNCXEHLOPQRMWS` to shrink the output.

## Commands

Running all tests (across all modules):

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```

The `root/` prefix is required: it targets the aggregating `root` project so the run fans out to every module. A bare
`sbtn "testOnly * -- -oNCXEHLOPQRMWS"` is **not** reliable for the full suite — it runs only in the sbtn session's
*current project* (the one marked `*` in `sbtn projects`), which a previous `${MODULE}/testOnly` command may have
switched away from `root`. When that happens the bare command silently passes while exercising just one module's tests.

### Trimming the noise of a full run

The `-oNCXEHLOPQRMWS` flags already suppress every per-test and per-suite line, but a whole-project run still prints
three kinds of noise the ScalaTest reporter flags cannot touch: sbt's `No tests to run for …` / `Passed: Total 0 …`
lines for the modules that have no tests, the SLF4J logging-init warnings, and the per-module
`Run completed in …` / `All tests passed.` summary chatter. Pipe the run through a `grep -v` filter to drop exactly
those lines:

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 \
  | grep -vE 'SLF4J|No tests to run for|Passed: Total 0, Failed 0, Errors 0, Passed 0|Run completed in|All tests passed\.'
```

This roughly halves the output (≈60 → ≈26 lines when green), leaving only the per-module
`Total number of tests run` / `Suites:` / `Tests: succeeded … failed …` counts and the final elapsed time. The filter
is deliberately conservative: it keeps every failure line — the `*** FAILED ***` detail, assertion message, stack
trace, per-module `failed N` count, and the `[error] … sbt.TestsFailedException` tail all pass through untouched.

> **Caveat:** the trailing `grep` makes the pipeline's exit status reflect `grep`, not `sbt`, so don't rely on the exit
> code to detect failures — read the output. On failure the run still prints `Tests: … failed N`, `*** N TESTS
> FAILED ***`, and `[error] (Test / testOnly) sbt.TestsFailedException: Tests unsuccessful`.

Testing a single module `${MODULE}`:

```bash
sbtn "${MODULE}/testOnly * -- -oNCXEHLOPQRMWS"
```

Testing a single test class `${CLASS}` (declared with fully qualified name) in a module `${MODULE}`:

```bash
sbtn "${MODULE}/testOnly ${CLASS} -- -oNCXEHLOPQRMWS"
```

For example:

```bash
sbtn "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest -- -oNCXEHLOPQRMWS"
```
