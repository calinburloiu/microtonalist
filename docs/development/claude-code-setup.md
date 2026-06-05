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

4. Verify the connection with `/mcp` -- `metals` should appear in the server list.

> **Note:** The `.mcp.json` file is generated dynamically with a random port and is gitignored. It cannot be checked
> into the repository since it is machine- and session-specific.

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

The repository ships a project-level [Claude Code hook](https://code.claude.com/docs/en/hooks) so its behavior is shared
by everyone who works in the repo. It is committed in two files:

- [`.claude/settings.json`](../../.claude/settings.json) — registers the hook (a `PreToolUse` hook on the `Bash` tool).
- [`.claude/hooks/sbt-test-filter.sh`](../../.claude/hooks/sbt-test-filter.sh) — the hook script.

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
floors with a 3% buffer, the rule that new files must hit 80% on their own, and the stop-and-wait response to the known
sbt-scoverage + Scala 3 TASTy concurrency issue). When triggered, the skill does not inspect reports itself — it
delegates the mechanical XML reading to the [`scoverage-inspector` subagent](#scoverage-inspector-agent). The skill's
[`scripts/`](../../.claude/skills/scoverage-inspector/scripts/) directory holds the Python stdlib readers and sbt
wrapper scripts that the subagent runs; they stream the large `scoverage.xml` reports and emit small summaries instead
of loading the full XML into context.

## Agents (Subagents)

[Subagents](https://code.claude.com/docs/en/sub-agents) are specialized assistants that run with their own system prompt
and a restricted tool set, in a separate context from the main conversation. The repository ships project-level
subagents under [`.claude/agents/`](../../.claude/agents/) (one Markdown file per agent), committed so they are shared
across the team.

### `scoverage-inspector` agent

[`.claude/agents/scoverage-inspector.md`](../../.claude/agents/scoverage-inspector.md) is the worker spawned by the
`scoverage-inspector` skill; it is not meant to be invoked directly. It runs on a small, cheap and fast model (Haiku)
and is limited to the `Bash` and `mcp__metals__inspect` tools, so it is strictly read-only apart from the log files its
wrapper scripts write. Its system prompt holds the full inspection workflow: resolve class names to sbt modules, check
whether the existing coverage report is still fresh, run a coverage build in isolation (under `target-scoverage/`) only
when needed, and parse the report down to per-class percentages and uncovered `file:line` locations.

Keeping this work in a subagent serves two purposes: the verbose tool calls stay out of the main agent's (more
expensive) context, and the subagent deliberately runs **without** the project `CLAUDE.md` — that file tells agents to
use `sbtn` against the running BSP server, which would conflict with this task's need to run `sbt` in isolation.

## Authorizing MCP Servers and Plugins

On first launch in a workspace, Claude Code may prompt you to authorize the Metals MCP server. You can save this choice
for the workspace so it persists across sessions. The project-level settings file `.claude/settings.local.json`
(gitignored) stores these authorizations along with other local preferences like `enableAllProjectMcpServers`.
