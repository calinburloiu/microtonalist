# Claude Code Setup

[Claude Code](https://claude.com/claude-code) is the AI coding assistant used in this project. The repository includes a
`CLAUDE.md` with project-specific instructions that Claude Code reads automatically.

Two integrations enhance Claude Code's capabilities in this project:

- **Metals MCP** -- gives Claude Code Scala-aware intelligence (symbol inspection, compilation, find usages, etc.)
- **GitHub MCP plugin** -- gives Claude Code access to GitHub (issues, PRs, labels, etc.)

## Metals MCP

Metals MCP provides Claude Code with Scala code intelligence through the
[Model Context Protocol](https://modelcontextprotocol.io/). For the full background and detailed instructions, see
[metals-mcp-claude-code.md](metals-mcp-claude-code-setup.md).

### Quick start

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
   bin/microtonalist-dev-stack start
   ```

   This launches SBT and Metals as background processes (the default) and returns immediately. Once ready,
   it generates a `.mcp.json` file at the repo root (gitignored) that Claude Code picks up automatically.
   Pass `--foreground` to attach to the current terminal instead. See
   [`bin/README.md`](../../bin/README.md#microtonalist-dev-stack) for details, including `stop` and `status`.

3. In another terminal, launch Claude Code:

   ```bash
   claude
   ```

4. Verify the connection with `/mcp` -- `metals` and `scoverage-inspector` should appear in the server list.

> **Note:** The `.mcp.json` file is generated dynamically with a random port and is gitignored. It cannot be checked
> into the repository since it is machine- and session-specific. `bin/microtonalist-dev-stack start` merges the
> project's `scoverage-inspector` server into it after Metals writes the file (see below).

## scoverage-inspector MCP

The `scoverage-inspector` MCP server gives Claude Code cheap, structured access to scoverage reports — per-class
statement/branch percentages and uncovered source lines — without loading the ~3000-line `scoverage.xml` files into
context. It is an in-process [FastMCP](https://modelcontextprotocol.io/) stdio server living under
[`.claude/mcp/scoverage_inspector/`](../../.claude/mcp/scoverage_inspector/), backed by a pure, unit-tested
`scoverage_core.py` module. Each query tool freshness-checks the requested modules and transparently rebuilds stale or
missing reports via a single batched `sbt` run (isolated under `target-scoverage/`, logged to
`logs/mcp/scoverage-inspector/sbt-run.log`).

It is launched via `uvx --from mcp`, which fetches and pins the `mcp` package on demand without adding it to the repo
toolchain — so **[`uv`](https://docs.astral.sh/uv/) must be installed** (see
[`README.md`](README.md#prerequisites)). Because Metals rewrites `.mcp.json` from scratch on every start (its port and
transport are dynamic), `bin/microtonalist-dev-stack start` merges the `scoverage-inspector` entry back in right after
Metals writes the file. If `uv` is missing, the dev-stack prints a warning and skips registration.

The same logic is runnable from the command line via `python3 .claude/mcp/scoverage_inspector/cli.py` (subcommands
`freshness`, `class-summary`, `class-uncovered`, `module-summary`, `run-coverage`) for humans and CI. The Python test
suite runs on stdlib `unittest`:

```bash
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests -p "test_*.py"
```

## GitHub MCP Plugin

The GitHub MCP plugin is a built-in Claude Code plugin (not an MCP server) that gives Claude Code access to GitHub for
managing issues, pull requests, labels, and more.

Since this is a **user-level plugin** that requires personal authentication, it cannot be configured at the repository
level. Each developer must enable it once in their own Claude Code settings.

### Setup

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

## Hooks

The repository ships project-level [Claude Code hooks](https://code.claude.com/docs/en/hooks) so their behavior is
shared by everyone who works in the repo. They are committed in:

- [`.claude/settings.json`](../../.claude/settings.json) — registers the hooks (`PreToolUse` hooks on the `Bash` and
  `Read` tools).
- [`.claude/hooks/sbt-test-filter.sh`](../../.claude/hooks/sbt-test-filter.sh) and
  [`.claude/hooks/license-header-read-skip.sh`](../../.claude/hooks/license-header-read-skip.sh) — the hook scripts.

> Unlike [`.claude/settings.local.json`](#authorizing-mcp-servers-and-plugins) (gitignored, per-developer),
> `.claude/settings.json` is committed and applies to everyone. Hooks load at Claude Code startup, so changes take
> effect in the next session.

### `sbt-test-filter` — quiet test runs

A full test run prints a lot of noise the ScalaTest reporter flags cannot suppress (sbt's "no tests" lines for empty
modules, SLF4J warnings, and the per-module green summaries). The [`bin/agents-test-filter`](../../bin/agents-test-filter)
script is a stdin filter that drops exactly those lines while letting every failure and abort signal through, and it
exits non-zero when the run reports a problem.

The hook wires this in automatically: when the agent runs an sbt/sbtn **test** command, the `PreToolUse` hook rewrites
the command (via the hook's `updatedInput` field) to append `2>&1 | bin/agents-test-filter` before it executes. So the
agent sees only the meaningful output, and the pipeline's exit code still reflects pass/fail.

The hook is deliberately conservative — it only rewrites a *plain*, single `sbt`/`sbtn` test invocation that is not
already filtered, and skips anything containing shell metacharacters (pipes, lists, redirections) so appending a pipe
can never change operator precedence. On any non-match, missing `jq`, or unexpected input it exits 0 without output, so
it never blocks or breaks a command. To bypass it for a one-off, run the test command through another pipe or redirection
(e.g. append `| cat`), or invoke sbt by a form the matcher ignores.

### `license-header-read-skip` — hide license headers at read time

Every source file opens with a ~15-line Apache 2.0 license header that costs tokens on every read but tells an agent
nothing. This `PreToolUse` hook on the `Read` tool detects the header and rewrites the read's `offset` so the file
appears to start at the first line of code (~line 17 for Scala). **Real line numbers are preserved** — the header lines
are omitted, not renumbered — so `file:line` references stay correct.

Like `sbt-test-filter`, it is deliberately conservative and **fails open**: it passes the read through unchanged on any
non-match, missing `jq`, or unexpected input, and never touches a file whose first line is a shebang (a single offset
can't keep the shebang while skipping the header). To see the full file including the header, `Read` it with an explicit
**`offset: 1`**. See [`license-headers.md`](license-headers.md) for the full reference (and the `addlicense` tooling that
keeps the header set complete).

## Skills

[Skills](https://code.claude.com/docs/en/skills) are reusable, on-demand instruction sets that Claude Code loads only
when a task matches them, keeping them out of the always-loaded context. The repository ships project-level skills under
[`.claude/skills/`](../../.claude/skills/) (one directory per skill, each with a `SKILL.md`), so they are shared by
everyone who works in the repo and are version-controlled alongside the code.

### `contributing` skill

[`.claude/skills/contributing/SKILL.md`](../../.claude/skills/contributing/SKILL.md) collects the repository's GitHub
workflow conventions so Claude applies them consistently when it creates an issue, starts a branch, or opens a pull
request. It documents the label set (`feature`, `bugfix`, `refactoring`, `doc`, `poc`), the
`<label>/<kebab-case-description>` branch-naming format, the `[#<issue>] <description>` PR title format, the
draft-by-default rule, and the requirement to add new issues and PRs to the **microtonalist** GitHub project (Projects
v2). Because the GitHub MCP plugin cannot manage Projects v2, the skill tells Claude to fall back to the `gh` CLI for
that one step.

### `scoverage-inspector` skill

[`.claude/skills/scoverage-inspector/SKILL.md`](../../.claude/skills/scoverage-inspector/SKILL.md) carries the project's
coverage **policy** (the 80% statement/branch target, the per-module `build.sbt` thresholds that act as never-lowered
floors with a 3% buffer, the rule that new files must hit 80% on their own). When triggered, the skill resolves the
named classes to their sbt module IDs (via Metals) and calls the [`scoverage-inspector` MCP server](#scoverage-inspector-mcp),
which performs all the mechanical work — freshness check, rebuild if stale, and XML parsing — in-process and returns
small structured results instead of loading the full report into context.

## Authorizing MCP Servers and Plugins

On first launch in a workspace, Claude Code may prompt you to authorize the Metals MCP server. You can save this choice
for the workspace so it persists across sessions. The project-level settings file `.claude/settings.local.json`
(gitignored) stores these authorizations along with other local preferences like `enableAllProjectMcpServers`.
