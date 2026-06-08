# Development Setup

This guide covers everything needed to build, test, and develop Microtonalist on a new machine. It also explains how to
set up AI-assisted development with [Claude Code](https://claude.com/claude-code).

## Documents in this directory

- [`build.md`](build.md) — compiling and building the fat JAR with sbt.
- [`test.md`](test.md) — running the test suite.
- [`coding-conventions.md`](coding-conventions.md) — general / production Scala coding conventions.
- [`test-conventions.md`](test-conventions.md) — conventions for writing tests.
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

* JDK 23
* Scala 3
* SBT 1
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
