# Development Setup

This guide covers everything needed to build, test, and develop Microtonalist on a new machine. It also explains how to
set up AI-assisted development with [Claude Code](https://claude.com/claude-code).

## Documents in this directory

- [`build.md`](build.md) — compiling and building the fat JAR with sbt.
- [`test.md`](test.md) — running the test suite.
- [`coding-conventions.md`](coding-conventions.md) — general / production Scala coding conventions.
- [`test-conventions.md`](test-conventions.md) — conventions for writing tests.
- [`doc-conventions.md`](doc-conventions.md) — conventions for ScalaDoc, comments and Markdown docs.
- [`linting.md`](linting.md) — compiler warnings and scalafix rules: the commands, where they're configured, unused
  code, and how to suppress a finding.
- [`coverage.md`](coverage.md) — manual coverage workflow (`coverageAll` / `coverageModules`) and CI's `coverageCheck`.
- [`scoverage-issue.md`](scoverage-issue.md) — the known sbt-scoverage + Scala 3 TASTy concurrency issue and how to
  handle it.
- [`license-headers.md`](license-headers.md) — the Apache 2.0 header format, the Claude Code
  read-skip hook, and `addlicense`.
- [`claude-code-setup.md`](claude-code-setup.md) — setting up Claude Code with Metals MCP and the GitHub MCP plugin.
- [`metals-mcp-claude-code-setup.md`](metals-mcp-claude-code-setup.md) — full background with details on the Metals MCP
  integration.

> Coding agents: most of the above are for humans. The agent-facing equivalents (loaded automatically) live in
> [`../agents/`](../agents/) and in the root [`CLAUDE.md`](../../CLAUDE.md).

## Prerequisites

* JDK 25
* Scala 3
* SBT 1
* [direnv](https://direnv.net/)
    - Recommended: puts the project's scripts from [`bin/`](../../bin/README.md) on your `PATH`. See
      [direnv](#direnv) below.
* Python 3
    - Optional: for AI-assisted coverage tooling.
* [`uv`](https://docs.astral.sh/uv/) (provides `uvx`)
    - Optional: for the `scoverage-inspector` MCP server, which is launched via `uvx --from mcp`. Install with
      `curl -LsSf https://astral.sh/uv/install.sh | sh`.
* Coursier
    - Optional: for Metals MCP.
* [`addlicense`](https://github.com/google/addlicense) + Go
    - Optional: only needed to run the license-header commit hook or CI check locally. Install with
      `go install github.com/google/addlicense@latest`. See [`license-headers.md`](license-headers.md).
* [scalafmt](https://scalameta.org/scalafmt/) command-line tool
    - Optional: for the formatting step of the pre-commit hook. Install with `cs install scalafmt`. See
      [`build.md`](build.md#pre-commit-hook).

## direnv

The project's scripts live in [`bin/`](../../bin/README.md): the development stack (`mtlist-dev-stack`), the coverage
runs (`mtlist-coverage-*`), and the application launchers (`mtlist`, `mtlist-tool`). The repository's `.envrc` file
tells [direnv](https://direnv.net/) to put `bin/` on your `PATH` whenever your shell is inside the repository, so you can
run them by name from any subdirectory. Set it up once:

1. Install direnv, e.g. with `brew install direnv` on macOS, or your Linux distribution's package manager.
2. Install direnv's hook into your shell, which loads `.envrc` files as you change directories. Add the line for your
   shell at the end of its startup file, then open a new terminal:

   ```bash
   # ~/.zshrc
   eval "$(direnv hook zsh)"
   ```

   ```bash
   # ~/.bashrc
   eval "$(direnv hook bash)"
   ```

   See [direnv's hook documentation](https://direnv.net/docs/hook.html) for other shells.
3. Approve the repository's `.envrc` from the repository root. direnv doesn't load an `.envrc` that you haven't
   approved, and it asks you again whenever the file changes, after reviewing it (`cat .envrc`):

   ```bash
   direnv allow
   ```

Check it with `which mtlist-dev-stack`, which should print the path of `bin/mtlist-dev-stack`. Without direnv, run the
scripts as `bin/<script>` from the repository root, as the documentation does.

> Claude Code's shell commands don't necessarily see the `PATH` that direnv sets, so the agent instructions call the
> scripts as `bin/<script>`, which works either way.

## Building

Compile all modules with `sbt compile`, a single module with `sbt "tuner/compile"`, and the fat JAR with `sbt assembly`.
See [`build.md`](build.md) for the full reference.

## Testing

Tests are written with [ScalaTest](https://www.scalatest.org/) 3. Run all tests with `sbt test`, a single module with
`sbt "tuner/test"`, and a single class with `sbt "intonation/testOnly <FQN>"`. See [`test.md`](test.md) for the full
reference and [`test-conventions.md`](test-conventions.md) for how tests are written.

## Claude Code Setup

[Claude Code](https://claude.com/claude-code) is the AI coding assistant used in this project. The repository includes a
`CLAUDE.md` with project-specific instructions that Claude Code reads automatically. Two integrations enhance Claude
Code's capabilities: **Metals MCP** for Scala-aware intelligence and the **GitHub MCP plugin** for GitHub access.

See [`claude-code-setup.md`](claude-code-setup.md) for full setup instructions.
