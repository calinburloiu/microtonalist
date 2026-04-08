# Metals MCP with Claude Code (SBT as BSP)

This guide explains how to install [Scala Metals](https://scalameta.org/metals/) and configure
its built-in MCP (Model Context Protocol) server so that [Claude Code](https://claude.com/claude-code)
can use Metals' Scala language intelligence (compile, find references, inspect symbols, etc.)
while working in this repository. Metals is configured to use **SBT as its BSP server**, so the
build state stays consistent with what `sbt` produces on the command line — important for a
multi-module project like Microtonalist.

## 1. Prerequisites

- JDK 23 (the same JDK used to build the project — see `CLAUDE.md`).
- [Coursier](https://get-coursier.io/docs/cli-installation) (`cs`) — used to install Metals.
- SBT 1.x (already used by this project).
- Claude Code CLI (`claude`) installed and on `PATH`.

Verify:

```bash
java -version
cs --version
sbt --version
claude --version
```

## 2. Install Metals

Install the Metals language server via Coursier. Metals 1.5.x or later is required for the
MCP server feature (introduced in the Strontium release).

```bash
cs install metals
```

This puts a `metals` launcher on `PATH`. Check the version:

```bash
metals --version
```

If you already have an older Metals installed (e.g. via an editor extension), update it the
same way; the MCP server only ships in recent versions.

## 3. Make SBT the BSP server

Microtonalist already contains `.bsp/sbt.json`, so SBT is the registered BSP server for the
workspace. If that file ever goes missing, you can regenerate it from the project root with:

```bash
sbt bspConfig
```

To make sure Metals always picks SBT (instead of Bloop) for any newly opened workspace, set
the following in your **global** Metals user settings (see
[Metals user configuration](https://scalameta.org/metals/docs/editors/user-configuration/)):

```json
{
  "metals.defaultBspToBuildTool": true
}
```

In VS Code this goes into `settings.json`. For other editors, the same key is exposed by the
Metals language server via `workspace/didChangeConfiguration`. Keeping the build tool as the
BSP server matters because — as the Metals docs note — it guarantees that whether you compile
from the editor, the terminal, or via an MCP tool call from Claude, everything sees the same
compilation state.

## 4. Enable the Metals MCP server

In the same Metals user settings, enable the MCP server and tell Metals which client will
connect to it:

```json
{
  "metals.defaultBspToBuildTool": true,
  "metals.startMcpServer": true,
  "metals.mcpClient": "claude"
}
```

- `startMcpServer` makes Metals start an HTTP/SSE MCP endpoint when it opens a workspace.
- `mcpClient: "claude"` tells Metals to write a Claude-compatible config file
  (`.mcp.json`) into the workspace root so Claude Code can auto-discover the server.

After saving the settings, **reload / restart Metals** in your editor so it picks up the new
configuration and imports the SBT build via BSP. Watch the Metals log — it will print the
local URL the MCP server is listening on (something like `http://localhost:54640/sse`).

## 5. Verify the generated `.mcp.json`

Once Metals has started inside the Microtonalist workspace, it will create a `.mcp.json` file
at the repository root that looks roughly like this:

```json
{
  "mcpServers": {
    "metals": {
      "url": "http://localhost:54640/sse",
      "type": "sse"
    }
  }
}
```

The port is chosen dynamically — don't hard-code it. You may want to add `.mcp.json` to
`.gitignore` (or to your global gitignore) since the URL is local and machine-specific.

## 6. Connect Claude Code

From the Microtonalist project root, launch Claude Code:

```bash
cd ~/Development/microtonalist
claude
```

On first launch in this workspace, Claude Code detects `.mcp.json` and prompts you to
authorize the newly discovered `metals` MCP server. Approve it and choose to remember the
choice for the workspace. You can confirm the server is connected with:

```
/mcp
```

inside Claude Code — `metals` should appear in the list with its tools enabled.

## 7. What you get

The Metals MCP server exposes a small set of Scala-aware tools that Claude Code can call,
including (names may vary across Metals versions):

- `compile-file` / `compile-module` — compile a single file or a whole module via BSP/SBT and
  return diagnostics.
- `find-usages` / `find-references` — locate where a symbol is used.
- `inspect` — get type / signature information for a Scala symbol.
- `test` — run tests for a target.

Because BSP is backed by SBT, a `compile-file` call after a previous successful `sbt compile`
returns almost instantly with either success or precise error diagnostics, which Claude can
then act on.

## 8. Recommended workflow with this repo

1. Run `sbt` once in a separate terminal so the SBT server is warm — Metals will reuse it via
   BSP and incremental compiles stay fast.
2. Open Claude Code in the project root.
3. Ask Claude to make changes; it will compile via Metals MCP rather than spawning fresh
   `sbt compile` shells.
4. For tests, you can still invoke them the way `CLAUDE.md` recommends, e.g.
   `sbt "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"`, or let
   Claude use Metals' `test` tool when appropriate.

## 9. Troubleshooting

- **Claude Code doesn't see the server.** Make sure `.mcp.json` exists at the workspace root
  and that you launched `claude` from that directory. Run `/mcp` to inspect the connection.
- **Metals didn't write `.mcp.json`.** Confirm `metals.startMcpServer` is `true` and
  `metals.mcpClient` is `"claude"`, then restart the Metals server. Check the Metals output
  log for the chosen port.
- **BSP imports Bloop instead of SBT.** Delete the `.bloop/` directory, ensure
  `.bsp/sbt.json` exists, set `metals.defaultBspToBuildTool: true`, and re-import the build
  from your editor's Metals command palette.
- **Compile errors that don't match `sbt compile`.** Almost always means BSP is not actually
  talking to SBT. Re-run `sbt bspConfig` and re-import.
- **Stale state after large refactors.** From an editor command palette run
  *Metals: Restart build server*, or just stop/start the SBT shell and re-import.

## References

- [Metals user configuration](https://scalameta.org/metals/docs/editors/user-configuration/)
- [Metals v1.5.3 — Strontium release notes (MCP server)](https://scalameta.org/metals/blog/2025/05/13/strontium/)
- [Metals blog](https://scalameta.org/metals/blog/)
- [sbt BSP support in Metals](https://scalameta.org/metals/blog/2020/11/06/sbt-BSP-support/)
- [Claude Code, Metals, and NVIM — Chris Kipp](https://www.chris-kipp.io/blog/claude-code-metals-and-nvim)
- [A Beginner's Guide to Using Scala Metals With its MCP Server — SoftwareMill](https://softwaremill.com/a-beginners-guide-to-using-scala-metals-with-its-model-context-protocol-server/)
- [Adding an MCP server to Claude Code — MCPcat](https://mcpcat.io/guides/adding-an-mcp-server-to-claude-code/)
- [`metals-standalone-client` (alternative headless runner)](https://github.com/jpablo/metals-standalone-client)
