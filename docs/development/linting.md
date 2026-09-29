# Linting

How the build checks the code beyond formatting: compiler warning flags and
[scalafix](https://scalacenter.github.io/scalafix/) rules. Formatting is in [`build.md`](build.md#formatting). The
flags are `compilerOptions` in `build.sbt`, and the rules are in `.scalafix.conf`; the
comments there say why. The conventions that these checks enforce are in
[`coding-conventions.md`](coding-conventions.md) and [`test-conventions.md`](test-conventions.md), where each one says
what enforces it.

## Commands

- `sbtn fix` applies the scalafix autofixes, then formats the code with scalafmt. scalafix compiles the code first and
  also runs the `DisableSyntax` checks, so a compile error or a finding such as a bare TODO stops `sbtn fix` before it
  formats anything. Fix it, or format only: `sbtn "scalafmtAll; scalafmtSbt"`. An unused import is only a warning (see
  [Warnings policy](#warnings-policy)), so `sbtn fix` removes it; any other unused code is a compile error, which you
  fix.
- `sbtn lint` compiles the main and test code of every module, then checks the scalafix rules and the formatting,
  without changing anything.
- `sbt -Dmicrotonalist.build.strictWarnings=true lint` is the same check in strict mode, as CI's `lint` job runs it. It
  also fails on the warnings that the default mode allows, apart from deprecations (see
  [Warnings policy](#warnings-policy)). It's the last command of the agents' Lint step.

`fix` and `lint` are command aliases in `build.sbt`.

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

## Warnings policy

Every compiler warning is a compile error, except those that `warningsPolicy` in `build.sbt` keeps as warnings:

| Mode | Used by | Warnings that stay warnings |
| --- | --- | --- |
| Default | Local builds (Metals, `sbtn`, IntelliJ IDEA), CI's `build` job (`coverageCheck`) | Deprecations, unused constructor parameters, unused imports |
| Strict (`-Dmicrotonalist.build.strictWarnings=true`) | CI's `lint` job, the last check of the agents' Lint step | Deprecations |

- Deprecations are never errors. Don't add a use of a deprecated API all the same.
- An unused constructor parameter ("unused explicit parameter") is the only warning that a TDD red-phase stub needs, so
  the default mode allows it until green.
- An unused import is a warning in default mode, so that the code still compiles and `sbtn fix` can remove it: scalafix
  only runs on code that compiles. `sbtn lint` fails on it all the same, at the `OrganizeImports` check.
- Strict mode makes both errors, so one left over fails the Lint step and CI. A warning is never noise to ignore: each
  one is either a deprecation or an error in strict mode.
- The long-running `sbtn` server can't take a property for a single command, so run a strict check with plain `sbt`,
  which uses a separate JVM and writes to `target/` rather than the server's `target-bsp/`:

  ```bash
  sbt -Dmicrotonalist.build.strictWarnings=true lint
  ```

**Phase quirk.** An error in an earlier compiler phase, such as an unused private member, stops compilation before later
phases report their warnings, such as deprecations. Those appear on the next compile, once the errors are fixed.

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
4. Say in the convention it enforces that it's enforced, and why next to the flag or rule when that isn't
   obvious.
