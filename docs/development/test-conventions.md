# Scala Test Conventions

Conventions for writing Scala test files in this repository. General/production code conventions live in
[`coding-conventions.md`](coding-conventions.md). For how to *run* tests, see the agent guide
[`../agents/test.md`](../agents/test.md) or the human guide [`test.md`](test.md).

## Directory structure and naming

Tests and production code follow the default Scala and SBT directory structure. For each SBT project, production code is
in `src/main/scala` and tests are in `src/test/scala`. Use `src/test/resources` for test data if necessary.

Conventionally, the tests for a given production class use the same package and class name is suffixed with `Test`
(e.g. the test class for `RatioInterval` is `RatioIntervalTest`).

## Behavior-driven style

The project uses the `scalatest` library for testing and `scalamock` for mocking / stubbing. Tests are written using
ScalaTest 3. The "behavior-driven" style of development (BDD) is preferred for writing tests by making tests classes
extend `org.scalatest.flatspec.AnyFlatSpec` and `org.scalatest.matchers.should.Matchers`. When using this style of
tests, test cases are grouped in behavior sections by using `behavior of`. When adding a new test case to a
behavior-driven suite consider the following:

* **Check the test class ScalaDoc first.** Some test classes carry a ScalaDoc comment that documents
  class-specific conventions — categories, subgroup structure, test-naming rules.
  When present, those conventions take precedence over the general guidance below.
* Analyze the current behavior section in the test file.
* Determine if there is an existing behavior section that is appropriate for the new test and if not create a new
  behavior section.
* Add the test in the determined section near a similar test. If there isn't a similar one, add it at the end of the
  behavior section.

## Use Given / When / Then comments in tests

Tests cases should be written using the `Given` / `When` / `Then` format. Some tests may not have a `Given` section,
while others may have multiple instances if the three. It's acceptable to combine two, like `When / Then` in some cases.

Wrong:

```scala
  it should "map just Cireșar scale" in {
  val ciresar = RatiosScale("Cireșar", 1 /: 1, 9 /: 8, 6 /: 5, 9 /: 7, 3 /: 2, 8 /: 5, 9 /: 5, 27 /: 14, 9 /: 4)
  val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13)
  val tuning = mapper.mapScale(ciresar, cTuningReference)

  tuning.completedCount shouldEqual 8
  tuning.eFlat shouldEqual 15.64
}
```

Correct:

```scala
  it should "map just Cireșar scale" in {
  // Given
  val ciresar = RatiosScale("Cireșar", 1 /: 1, 9 /: 8, 6 /: 5, 9 /: 7, 3 /: 2, 8 /: 5, 9 /: 5, 27 /: 14, 9 /: 4)
  val mapper = AutoTuningMapper(shouldMapQuarterTonesLow = false, quarterToneTolerance = 13)

  // When
  val tuning = mapper.mapScale(ciresar, cTuningReference)

  // Then
  tuning.completedCount shouldEqual 8
  tuning.eFlat shouldEqual 15.64
}
```

## Use fixtures to reduce duplication in test cases setup

Simplify the setup of test cases, typically the `Given` section, by using `trait` or `abstract class` fixtures. They
should contain code that repeats in many test cases. But be mindful not to sacrifice readability.

## No `if`s in tests

Do not use `if` statements in test code. Tests should always assert the condition rather than conditionally executing
assertions. An `if` silently skips assertions when the condition is false, hiding potential failures.

Wrong:

```scala
val output = tuner.tune(tuning)
if (output.nonEmpty) {
  output.head.value shouldBe expected
}
```

Correct:

```scala
val output = tuner.tune(tuning)
output should not be empty
output.head.value shouldBe expected
```

## Shared test utilities

When a test helper is needed across multiple modules, do **not** use sbt's `test->test` configuration dependencies —
they break scoverage instrumentation under the default class-loader layering. Instead, put the shared helper in the
`common-test-utils` module (or create a similar `*-test-utils` module if a different scope is required) and depend on
it from `Test` scope:

```scala
lazy val myModule = (project in file("my-module"))
  .dependsOn(commonTestUtils % Test)
```

Test-utility modules have `coverageEnabled := false` so that they do not appear in coverage reports.
