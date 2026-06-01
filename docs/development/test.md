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
sbtn test
```

Test a single module:

```bash
sbtn "tuner/test"
```

Test a single class (fully qualified name):

```bash
sbtn "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```
