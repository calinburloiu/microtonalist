# Development Setup

This guide covers everything needed to build, test, and develop Microtonalist on a new machine. It also explains how to
set up AI-assisted development with [Claude Code](https://claude.com/claude-code).

## Documents in this directory

- [`build.md`](build.md) — compiling and building the fat JAR with sbt.
- [`test.md`](test.md) — running the test suite.
- [`test-conventions.md`](test-conventions.md) — conventions for writing tests.
- [`coding-conventions.md`](coding-conventions.md) — general / production Scala coding conventions.
- [`coverage.md`](coverage.md) — manual coverage workflow (`coverageAll` / `coverageModules`) and CI's `coverageCheck`.
- [`scoverage-issue.md`](scoverage-issue.md) — the known sbt-scoverage + Scala 3 TASTy concurrency issue and how to
  handle it.
- [`claude-code-setup.md`](claude-code-setup.md) — setting up Claude Code with Metals MCP and the GitHub MCP plugin.
- [`metals-mcp-claude-code-setup.md`](metals-mcp-claude-code-setup.md) — full background with details on the Metals MCP
  integration.

> Coding agents: most of the above are for humans. The agent-facing equivalents (loaded automatically) live in
> [`../agents/`](../agents/) and in the root [`CLAUDE.md`](../../CLAUDE.md).

## Prerequisites

### JDK 23

The project targets Java 23. Install it via [SDKMAN!](https://sdkman.io/),
[Homebrew](https://formulae.brew.sh/formula/openjdk), or any other method:

```bash
# SDKMAN!
sdk install java 23-open

# Homebrew (macOS)
brew install openjdk@23
```

Verify:

```bash
java -version
```

### Scala 3

Scala 3.6.x is used (see `build.sbt`). You do **not** need to install Scala separately -- SBT manages the Scala
compiler automatically.

### SBT

[SBT](https://www.scala-sbt.org/) 1.x is the build tool. Install it via
[Coursier](https://get-coursier.io/docs/cli-installation) (recommended), Homebrew, or SDKMAN!:

```bash
# Coursier (recommended)
cs install sbt

# Homebrew (macOS)
brew install sbt

# SDKMAN!
sdk install sbt
```

Verify:

```bash
sbt --version
```

### Python 3 (optional, for AI-assisted coverage tooling)

Python ≥ 3.10 is needed only by the `scoverage-inspector` Claude Code skill, which ships small helper scripts that
parse `coverage-reports/*/scoverage-report/scoverage.xml` cheaply. The scripts use the standard library only — no
`pip install` or virtualenv is required.

macOS 14+ ships Python 3 by default. Otherwise:

```bash
# Homebrew (macOS)
brew install python

# SDKMAN!
sdk install java  # for reference; for Python prefer Homebrew/pyenv
```

Verify:

```bash
python3 --version
```

You can skip this entirely if you don't use the skill.

### Coursier (optional, for Metals)

[Coursier](https://get-coursier.io/docs/cli-installation) is needed to install Metals for Claude Code integration. If
you only need to build and test, you can skip it.

```bash
# macOS
brew install coursier/formulas/coursier

# Or the universal installer
curl -fL https://github.com/coursier/coursier/releases/latest/download/cs-x86_64-pc-linux.gz | gzip -d > cs \
  && chmod +x cs && ./cs setup
```

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
