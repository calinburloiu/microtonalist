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

## Authorizing MCP Servers and Plugins

On first launch in a workspace, Claude Code may prompt you to authorize the Metals MCP server. You can save this choice
for the workspace so it persists across sessions. The project-level settings file `.claude/settings.local.json`
(gitignored) stores these authorizations along with other local preferences like `enableAllProjectMcpServers`.
