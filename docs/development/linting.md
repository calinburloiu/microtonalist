# Linting

How the build checks the code beyond formatting: compiler warning flags and
[scalafix](https://scalacenter.github.io/scalafix/) rules. Formatting is in [`build.md`](build.md#formatting). The
conventions that these checks enforce are in [`coding-conventions.md`](coding-conventions.md) and
[`test-conventions.md`](test-conventions.md), where each one says what enforces it.

## Commands

- `sbtn fix` applies the scalafix autofixes, then formats the code with scalafmt. scalafix compiles the code first and
  also runs the `DisableSyntax` checks, so a compile error or a finding such as a bare TODO stops `sbtn fix` before it
  formats anything. Fix it, or format only: `sbtn "scalafmtAll; scalafmtSbt; experiments/scalafmtAll"`.
- `sbtn lint` checks the formatting.

Both are command aliases in `build.sbt`. Besides the modules that `root` aggregates, they cover the `experiments`
module, which `root` doesn't aggregate.

scalafix is incremental: it skips a file whose content it has already processed, even when that run's changes were
reverted since. To run it on every file, add `--no-cache`:

```bash
sbtn "scalafixAll --no-cache; experiments/scalafixAll --no-cache"
```

## Compiler warnings

The flags are in `compilerOptions` and `mainOnlyCompilerOptions` in `build.sbt`:

| Flag | Code | What it reports |
| --- | --- | --- |
| `-Wunused:all` | main and test | Unused imports, private members, local definitions and parameters, and `@nowarn` annotations that suppress nothing |
| `-no-indent`, `-old-syntax` | main and test | Compile errors, not warnings: indentation syntax and `if … then`, for the brace syntax convention |
| `-Wvalue-discard` | main | A non-`Unit` value discarded where `Unit` is expected, e.g. `map.remove(key)` as the last statement of a `Unit` method |
| `-Wnonunit-statement` | main | A non-`Unit` expression used as a statement |
| `-deprecation`, `-feature`, `-unchecked` | main and test | Deprecated APIs, language features that need an import, unchecked type patterns |

Tests don't get `-Wvalue-discard` and `-Wnonunit-statement`: ScalaTest's assertions return `Assertion`, so they would
fire on nearly every line. `Test / scalacOptions` starts from `Compile / scalacOptions`, so `build.sbt` removes them
from it explicitly.

Not used, for now: `-Wsafe-init` (slower compiles, and no convention needs it) and `-Wshadow`.

When a value is discarded on purpose, assign it to `_`:

```scala
if (handle.state == State.Closed) {
  val _ = handles.remove(handle.id)
}
```

On Scala 3.6.3, ascribing `: Unit` to the expression doesn't silence `-Wvalue-discard`.

## scalafix rules

The rules are in `.scalafix.conf`:

| Rule | Kind | What it does |
| --- | --- | --- |
| `OrganizeImports` | autofix | Removes the unused imports and orders the rest in IntelliJ IDEA's default layout: the other packages, one blank line, then `java`, `javax` and `scala` |
| `RedundantSyntax` | autofix | Removes redundant syntax, e.g. the `s` of an `s"…"` literal without an interpolated value |
| `NoValInForComprehension` | autofix | Removes `val` from the definitions in a `for` comprehension |
| `DisableSyntax` | check | Reports `return` (`noReturns`), and the regular expressions below |

| `DisableSyntax` id | Pattern | Convention |
| --- | --- | --- |
| `todoWithoutIssue` | `//\s*TODO(?! #\d+)` | [TODOs have issue numbers](coding-conventions.md#todos-have-issue-numbers). A mid-sentence "the onAttach TODO above" doesn't match. |
| `threadSleep` | `Thread\.sleep` | [No sleeping in tests](test-conventions.md#no-sleeping-in-tests). It checks production code too. |
| `anyFlatSpec` | `AnyFlatSpec` | [Behavior-driven style](test-conventions.md#behavior-driven-style) |

`noReturns` enforces [No `return`](coding-conventions.md#no-return). Its message is fixed by scalafix ("return should be
avoided, consider using if/else instead"): the convention shows how to use `boundary` instead.

The autofixes need SemanticDB, so scalafix compiles the code first (`ThisBuild / semanticdbEnabled := true`).

### Unused code

scalafix removes unused imports and nothing else. There's deliberately no `RemoveUnused` rule: the compiler reports an
unused method, value, class or parameter, and you decide what to do with it. Delete it when it's dead. When a test value
is unused, check first whether the case forgot to check it: then add the check.

The compiler can't see a call made through reflection, so it reports a private method that only reflection calls as
unused. Guava's `EventBus` calls the `@Subscribe` methods this way. Don't delete such a method: suppress the warning,
as `TrackManager` does.

## Suppressing a finding

Fix the code when you can. Otherwise:

- **A compiler warning:** `@nowarn("msg=<message regex>")` on the narrowest definition that contains it.
  `-Wunused:all` reports an `@nowarn` that no longer suppresses anything.
- **A scalafix finding:** `// scalafix:ok <RuleId>` at the end of the line, e.g.
  `Thread.sleep(1_000) // scalafix:ok DisableSyntax.threadSleep`.

Either way, add a comment giving the reason, and a `// TODO #<issue>` if the suppression is temporary.

## Adding a rule

1. Add a compiler flag to `compilerOptions`, or a rule to `.scalafix.conf` (the built-in ones are listed at
   <https://scalacenter.github.io/scalafix/docs/rules/overview.html>).
2. Check it on a scratch file with a violation, then count the violations in the code: compile, or run
   `sbtn "scalafixAll <Rule>"`.
3. Fix them. An autofix's output across the code goes in a PR of its own, whose squashed commit is then added to
   `.git-blame-ignore-revs`.
4. Document the rule here, and in the convention it enforces.
