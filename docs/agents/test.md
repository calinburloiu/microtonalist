# Running Tests (Agents)

How to run the test suite as a coding agent. For conventions on *writing* tests (BDD style, Given/When/Then, fixtures,
shared test utilities), see [`../development/test-conventions.md`](../development/test-conventions.md).

Currently, Metals MCP cannot run tests with this setup (with SBT and BSP). So run all tests through `sbtn` so they
execute on the BSP server (see the "sbt invocations: prefer the BSP server via `sbtn`" section in the root `CLAUDE.md`
and [`dev-stack.md`](dev-stack.md)).

For small changes, it is recommended to only test individual files or SBT projects. It is recommended to run the full
suite before committing.

Running all tests:

```bash
sbtn test
```

Testing a single SBT project `${PROJECT}`:

```bash
sbtn "${PROJECT}/test"
```

Testing a single test class `${CLASS}` (declared with fully qualified name) in an SBT project `${PROJECT}`:

```bash
sbtn "${PROJECT}/testOnly ${CLASS}"
```

For example:

```bash
sbtn "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```
