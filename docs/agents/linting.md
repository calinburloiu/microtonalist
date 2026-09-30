# Linting (Agents)

What to do about a compiler warning or a lint finding. The human guide,
[`../development/linting.md`](../development/linting.md), explains the configuration and the reasons behind it; read it
only when this document isn't enough.

## Commands

- `sbtn fixLint` is the Lint step: `fix`, then `lint`.
- `sbtn fix` applies the scalafix autofixes (it removes unused imports, orders the imports and removes redundant
  syntax), then formats the code. scalafix compiles first, so a compile error or a scalafix finding, such as a TODO
  without an issue number, stops it before it formats anything: fix that by hand first.
- `sbtn lint` is CI's check. It compiles the main and test code and fails on any warning except a deprecation, then
  checks the scalafix rules and the formatting without changing anything.
- The pre-push hook runs `sbtn lint` before a push, when the working tree is what's pushed and the sbt server is up. If
  it fails, fix the code and commit; don't bypass it with `--no-verify` unless the user asks.

## Unused code

The compiler reports unused imports, private members, local definitions and parameters. Only unused imports are
removed automatically; handle the rest by hand:

- Delete dead code, except a private method that only reflection calls, such as a Guava `@Subscribe` method: suppress
  its warning instead, as `TrackManager` does.
- For an unused test value, first check whether the case forgot to check it; if so, add the check.
- A type used only inside a string that is type-checked at compile time (`typeChecks`, `assertCompiles`,
  `assertDoesNotCompile`, `assertTypeError`) leaves its import unused, and `sbtn fix` removes it. Name the type fully
  inside the string instead, as `MidiMsgTest` does.

## Suppressing a finding

Only when the code can't be fixed:

- A compiler warning: `@nowarn("msg=<message regex>")` on the narrowest definition that contains it. The compiler
  reports an `@nowarn` that suppresses nothing.
- A scalafix finding: `// scalafix:ok <RuleId>` at the end of the line, e.g.
  `Thread.sleep(1_000) // scalafix:ok DisableSyntax.threadSleep`.

Either way, add a comment giving the reason, and a `// TODO #<issue>` if the suppression is temporary.

## Gotchas

- An error in an earlier compiler phase, such as an unused private member, hides the warnings of later phases, such as
  deprecations, until it's fixed. Compile again after fixing it.
- scalafix skips a file whose content it has already processed. To run it on every file:
  `sbtn "scalafixAll --no-cache"`.
