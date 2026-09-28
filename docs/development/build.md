# Building and Compiling

This document describes how to compile and build the project with sbt. This is primarily for human developers; coding
agents should normally compile via the Metals MCP (`mcp__metals__compile-full` / `mcp__metals__compile-module`) and only
fall back to sbt when the Metals MCP is unavailable or for a final full build / fat JAR assembly.

When the development stack is running, prefer `sbtn` over `sbt` so commands execute on the long-lived BSP server rather
than spawning a fresh `sbt` JVM. See the [Development Setup](README.md) guide and
[`../agents/dev-stack.md`](../agents/dev-stack.md). Using `sbtn` with the dev-stack also helps avoiding concurrency
issues when multiple builds would write in the same target subdirectory.

## Project layout

The repository is built using SBT 1, Scala 3, and Java 23. It is split into multiple SBT projects that act as modules,
libraries, or separate executable applications — we simply call each of those SBT projects modules. Each one is located
in the repository root. Check `build.sbt` for details. The `root` SBT project aggregates all the other projects. The
executable application is in the `app` SBT project.

**Convention — project ID equals base directory name.** Every project sets `.withId(<base-directory-name>)` in
`build.sbt`, so its SBT project ID (used in `sbt "<id>/compile"` / `"<id>/test"`, in `thisProject.value.id`, and thus in
`coverageDataDir`) always equals its directory. IDs may be kebab-case (`sc-midi`, `config`, `common-test-utils`). The
`build.sbt` `lazy val` name follows the `<camelCase-dir-name>Module` convention (e.g. `scMidiModule` for ID `sc-midi`);
it is only used for `.dependsOn`/`.aggregate`. Keep `.withId` and the directory in lockstep when adding or renaming a
module — tooling such as the `scoverage-inspector` MCP relies on the ID being the source directory. See the CONVENTION
comment in `build.sbt`.

## Build output directories

The dev-stack's BSP server is launched with `-Dmicrotonalist.build.targetSuffix=-bsp` (see `targetSuffixOverride` in
`build.sbt`), so its compiled outputs live under `<project>/target-bsp/` rather than `<project>/target/`. Plain CLI
`sbt` invocations (without that property) keep using `<project>/target/`. The two trees never collide, which avoids the
TASTy load concurrency errors that a stray second `sbt` racing the BSP server on the same `classes/` tree once produced
(issue #186). `sbt clean` and `sbtn clean` each clean only the active tree.

## Compiling

Compiling the whole `root` SBT project:

```bash
sbtn compile
```

For small changes, it is recommended to only compile individual modules. Compiling a single module
`${MODULE}`:

```bash
sbtn "${MODULE}/compile"
```

## Formatting

Scala sources and the sbt build definition (`build.sbt`, and the `.sbt` and `.scala` files under `project/`) are
formatted with [scalafmt](https://scalameta.org/scalafmt/). Its configuration, `.scalafmt.conf` at the repository root,
pins the scalafmt version and reproduces IntelliJ IDEA's default Scala style. It sets `newlines.source = keep`: scalafmt
keeps most of the line breaks the author wrote, so two ways of breaking the same expression can both pass the check. It
breaks a code line that would exceed 120 columns, and a few of its rules add or move other breaks, e.g. a multi-line
`if` condition gets its parentheses on their own lines.

ScalaDoc is the exception to keeping line breaks: scalafmt refills each ScalaDoc paragraph up to 120 columns
(`docstrings.wrap = fold`), and indents the continuation lines of a tag like `@param` by 2 instead of aligning them.
Other comments aren't wrapped, so wrap a `//` or `/* */` comment longer than 120 columns by hand: scalafmt can only
refill every multi-line `/* */` comment, which would rewrap each file's license header.

Apply the scalafix autofixes (see [Linting](#linting)), then format everything:

```bash
sbtn fix
```

Check the formatting without changing any file. It fails if a file isn't formatted:

```bash
sbtn lint
```

Both are command aliases defined in `build.sbt`. Besides the modules that `root` aggregates, they cover the build
definition and the `experiments` module, which `root` doesn't aggregate.

CI's `lint` job runs `sbt lint` on every pull request, in parallel with the tests.

### Pre-commit hook

The pre-commit hook in [`.githooks/`](../../.githooks/pre-commit) formats the staged `.scala` and `.sbt` files with the
scalafmt command-line tool and re-stages them, so commits come out formatted even without running `sbtn fix`. Enable the
hooks once per clone with `git config core.hooksPath .githooks`, and install the tool with Coursier:

```bash
cs install scalafmt
```

The tool downloads the scalafmt version that `.scalafmt.conf` pins. If it isn't installed, the hook skips formatting
with a message, and CI's `lint` job catches unformatted code instead. If a staged file doesn't parse, the hook prints
scalafmt's error and aborts the commit, leaving the other staged files formatted in the working tree but not re-staged.

The hook formats the working-tree copy of each staged file, not its staged content, and then re-stages the whole file.
So a partial commit (`git add -p`) also includes the file's unstaged hunks, and is aborted when those hunks make the
file fail to parse. The hook's license-header step already behaves this way.

A commit that concludes a merge leaves untouched the files taken as they are from the merged branch, so that the merge
commit holds no change that neither parent has. It formats only the files that the merge combined or that were
resolved by hand.

`git commit <paths>` and `git commit --only`, which some IDE commit dialogs use, give the hook a temporary index that it
can't re-stage into. When the hook changes a file in such a commit, it stops the commit instead; review the changes and
commit again, which then commits the formatted files.

### Editors

Metals reads `.scalafmt.conf` by itself, so formatting from an editor that uses Metals matches `sbtn fix`. Agents format
with `sbtn fix` rather than the Metals MCP `format-file` tool: with the development stack's standalone Metals client
(see [`dev-stack.md`](../agents/dev-stack.md)), that tool computes the formatting but doesn't write it to the file. For
IntelliJ IDEA, see [`CONTRIBUTING.md`](../../CONTRIBUTING.md#formatting).

## Linting

Before formatting, `sbtn fix` applies the autofixes of [scalafix](https://scalacenter.github.io/scalafix/): it removes
unused imports, orders the imports, and removes redundant syntax. The compiler also warns about unused code and
discarded values. [`linting.md`](linting.md) lists every compiler flag and scalafix rule, and how to suppress a
finding.

## Building the fat JAR

Building the fat JAR for the executable application:

```bash
sbtn assembly
```

It is recommended to compile, build, or test the whole project before committing changes.
