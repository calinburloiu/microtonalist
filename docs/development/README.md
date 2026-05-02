# Development Setup

This guide covers everything needed to build, test, and develop Microtonalist on a new machine. It also explains how to
set up AI-assisted development with [Claude Code](https://claude.com/claude-code).

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

Compile all modules:

```bash
sbt compile
```

Compile a single module (e.g. `tuner`):

```bash
sbt "tuner/compile"
```

Build the fat JAR for the main application:

```bash
sbt assembly
```

## Testing

Tests are written with [ScalaTest](https://www.scalatest.org/) 3. Run all tests:

```bash
sbt test
```

Test a single module:

```bash
sbt "tuner/test"
```

Test a single class:

```bash
sbt "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"
```

## Claude Code Setup

[Claude Code](https://claude.com/claude-code) is the AI coding assistant used in this project. The repository includes a
`CLAUDE.md` with project-specific instructions that Claude Code reads automatically.

Two integrations enhance Claude Code's capabilities in this project:

- **Metals MCP** -- gives Claude Code Scala-aware intelligence (symbol inspection, compilation, find usages, etc.)
- **GitHub MCP plugin** -- gives Claude Code access to GitHub (issues, PRs, labels, etc.)

### Metals MCP

Metals MCP provides Claude Code with Scala code intelligence through the
[Model Context Protocol](https://modelcontextprotocol.io/). For the full background and detailed instructions, see
[metals-mcp-claude-code.md](metals-mcp-claude-code.md).

#### Quick start

1. Install Metals and `metals-standalone-client`:

   ```bash
   cs install metals
   ```

   ```bash
   mkdir -p ~/.local/bin
   curl -L -o ~/.local/bin/metals-standalone-client \
     https://github.com/jpablo/metals-standalone-client/releases/latest/download/metals-standalone-client-macos-executable
   chmod +x ~/.local/bin/metals-standalone-client
   ```

2. From the repo root, start the development stack (sbt + BSP server + Metals MCP):

   ```bash
   ./scripts/development/start-sbt-metals.sh
   ```

   This starts SBT and Metals as background processes. Once ready, it generates a `.mcp.json` file at the repo root
   (gitignored) that Claude Code picks up automatically. The script can also be
   [run in the background](../../scripts/development/README.md#start-sbt-metalssh).

3. In another terminal, launch Claude Code:

   ```bash
   claude
   ```

4. Verify the connection with `/mcp` -- `metals` should appear in the server list.

> **Note:** The `.mcp.json` file is generated dynamically with a random port and is gitignored. It cannot be checked
> into the repository since it is machine- and session-specific.

### GitHub MCP Plugin

The GitHub MCP plugin is a built-in Claude Code plugin (not an MCP server) that gives Claude Code access to GitHub for
managing issues, pull requests, labels, and more.

Since this is a **user-level plugin** that requires personal authentication, it cannot be configured at the repository
level. Each developer must enable it once in their own Claude Code settings.

#### Setup

1. Create a [GitHub personal access token](https://github.com/settings/tokens) with the scopes needed for your workflow
   (e.g. `repo`, `project`).

2. Add the token and enable the plugin in your user-level Claude Code settings at `~/.claude/settings.json`:

   ```json
   {
     "env": {
       "GITHUB_PERSONAL_ACCESS_TOKEN": "<your-token>"
     },
     "enabledPlugins": {
       "github@claude-plugins-official": true
     }
   }
   ```

3. Launch Claude Code and verify the plugin is active -- GitHub tools (e.g. `mcp__plugin_github_github__issue_read`)
   should be available.

### Authorizing MCP Servers and Plugins

On first launch in a workspace, Claude Code may prompt you to authorize the Metals MCP server. You can save this choice
for the workspace so it persists across sessions. The project-level settings file `.claude/settings.local.json`
(gitignored) stores these authorizations along with other local preferences like `enableAllProjectMcpServers`.
