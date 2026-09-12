# Testing

This document describes how to run the test suite during development. For conventions on *writing* tests (BDD style,
Given/When/Then, fixtures,
shared test utilities), see [`test-conventions.md`](test-conventions.md).

Tests are written with [ScalaTest](https://www.scalatest.org/) 3 and use `scalamock` for mocking / stubbing. Production
code lives in `src/main/scala` and tests in `src/test/scala` for each module; test data goes in `src/test/resources`.

> **Tip:** When the development stack is running (`bin/microtonalist-dev-stack start`), prefer `sbtn` over `sbt` so the
> commands execute on the long-lived BSP server instead of spawning a fresh JVM each time. See the
> [Development Setup](README.md) guide.

It is recommended to run the full suite before committing. For small changes, run only the affected module or class.

Run all tests:

```bash
sbtn "root/test"
```

`root` aggregates every module, so this runs the whole suite. Plain `sbtn test` does the same — *as long as* no other
project is selected: without a project prefix, `test` applies to sbt's current project, and that selection persists
across commands in a long-lived `sbtn`/BSP session (a `project <name>` switch sticks). The `root/` prefix is
unambiguous.

> **Note:** `experiments` is the one module `root` does not aggregate (see `build.sbt`). It currently has no tests, but
> any added there would not be picked up by the command above.

Test a single module:

```bash
sbtn "${MODULE}/test"
```

for example:

```bash
sbtn "tuner/test"
```

Test a single class (fully qualified name):

```bash
sbtn "${MODULE}/testOnly ${CLASS_FQN}"
```

for example:

```bash
sbtn "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```

## Passing ScalaTest options

The `test` task takes no arguments — `sbtn "tuner/test -- -oNCXEHLOPQRMWS"` fails to parse. To pass ScalaTest reporter
options, use `testOnly` with a `*` glob (it matches every suite, including the dots in fully qualified names) and put
the options after `--`:

```bash
sbtn "tuner/testOnly * -- -oNCXEHLOPQRMWS"
```

Apart from accepting arguments, `<module>/testOnly *` and `<module>/test` run exactly the same suites. Coding agents use
the `testOnly` form throughout ([`../agents/test.md`](../agents/test.md)) purely to shrink output noise; there is no
reason to type those flags by hand during development.
