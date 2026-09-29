# Linting

How the build checks the code beyond formatting: compiler warning flags and
[scalafix](https://scalacenter.github.io/scalafix/) rules. Formatting is in [`build.md`](build.md#formatting). The
flags are `compilerOptions` and `mainOnlyCompilerOptions` in `build.sbt`, and the rules are in `.scalafix.conf`; the
comments there say why. The conventions that these checks enforce are in
[`coding-conventions.md`](coding-conventions.md) and [`test-conventions.md`](test-conventions.md), where each one says
what enforces it.

## Commands

- `sbtn fix` applies the scalafix autofixes, then formats the code with scalafmt. scalafix compiles the code first and
  also runs the `DisableSyntax` checks, so a compile error or a finding such as a bare TODO stops `sbtn fix` before it
  formats anything. Fix it, or format only: `sbtn "scalafmtAll; scalafmtSbt"`.
- `sbtn lint` checks the formatting.

Both are command aliases in `build.sbt`.

scalafix is incremental: it skips a file whose content it has already processed, even when that run's changes were
reverted since. To run it on every file, add `--no-cache`:

```bash
sbtn "scalafixAll --no-cache"
```

## Unused code

scalafix removes unused imports and nothing else. There's deliberately no `RemoveUnused` rule: the compiler reports an
unused method, value, class or parameter, and you decide what to do with it. Delete it when it's dead. When a test value
is unused, check first whether the case forgot to check it: then add the check.

The compiler can't see a call made through reflection, so it reports a private method that only reflection calls as
unused. Guava's `EventBus` calls the `@Subscribe` methods this way. Don't delete such a method: suppress the warning,
as `TrackManager` does.

The compiler can't see a name used only inside a string that is type-checked at compile time, such as the code passed
to `scala.compiletime.testing.typeChecks` or ScalaTest's `assertCompiles`, `assertDoesNotCompile` and
`assertTypeError`. It reports the import unused, and `sbtn fix` removes it: a positive check then fails, and a
negative one passes for the wrong reason. Name such types fully inside the string, as `MidiMsgTest` does.

A value discarded on purpose is assigned to `_`, as [No unused code](coding-conventions.md#no-unused-code) shows. On
Scala 3.6.3, ascribing `: Unit` to the expression doesn't silence `-Wvalue-discard`, and `-Wnonunit-statement` doesn't
report the discarded result of a Java method in the middle of a block, such as `javaMap.remove(key)` or
`buffer.put(byte)`.

## Suppressing a finding

Fix the code when you can. Otherwise:

- **A compiler warning:** `@nowarn("msg=<message regex>")` on the narrowest definition that contains it.
  `-Wunused:all` reports an `@nowarn` that no longer suppresses anything.
- **A scalafix finding:** `// scalafix:ok <RuleId>` at the end of the line, e.g.
  `Thread.sleep(1_000) // scalafix:ok DisableSyntax.threadSleep`.

Either way, add a comment giving the reason, and a `// TODO #<issue>` if the suppression is temporary.

## Adding a rule

1. Add a compiler flag to `compilerOptions` (`mainOnlyCompilerOptions` for production code only), or a rule to
   `.scalafix.conf` (the built-in ones are listed at <https://scalacenter.github.io/scalafix/docs/rules/overview.html>).
2. Check it on a scratch file with a violation, then count the violations in the code: compile, or run
   `sbtn "scalafixAll <Rule>"`.
3. Fix them. An autofix's output across the code goes in a PR of its own, whose squashed commit is then added to
   `.git-blame-ignore-revs`.
4. Say in the convention it enforces that it's enforced, and why next to the flag or rule when that isn't
   obvious.
