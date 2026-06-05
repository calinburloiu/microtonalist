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
