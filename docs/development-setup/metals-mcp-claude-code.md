# Metals MCP with Claude Code (headless, SBT as BSP)

This guide explains how to run [Scala Metals](https://scalameta.org/metals/) **headlessly**
(without a graphical editor) so that its built-in MCP (Model Context Protocol) server is
available to [Claude Code](https://claude.com/claude-code) inside this repository. Metals is
configured to use **SBT as its BSP server**, so Metals' compilation state stays consistent
with what `sbt` produces on the command line — important for a multi-module project like
Microtonalist.

The Metals language server itself does not (yet) ship a first-class "just start the MCP
server on this folder" command — it expects an LSP client (an editor) to drive it. To avoid
needing VS Code or any other editor, this guide uses the community
[`metals-standalone-client`](https://github.com/jpablo/metals-standalone-client), which acts
as a tiny headless LSP host whose only job is to start Metals, enable its MCP server, and
keep the session alive. This is the path explicitly endorsed by the Metals maintainers in
the [v1.6.1 Osmium release notes](https://scalameta.org/metals/blog/2025/07/31/osmium/).

## 1. Prerequisites

- JDK 23 (the same JDK used to build the project — see `CLAUDE.md`).
- [Coursier](https://get-coursier.io/docs/cli-installation) (`cs`) — used to fetch Metals.
- SBT 1.x (already used by this project).
- Claude Code CLI (`claude`) on `PATH`.
- macOS (the commands below use the macOS pre-built binary; Linux equivalents exist).

Verify:

```bash
java -version
cs --version
sbt --version
claude --version
```

## 2. Install Metals

Install the Metals language server via Coursier. Metals 1.6.x or later is recommended for
the standalone-MCP path.

```bash
cs install metals
metals --version
```

If you already have an older Metals (e.g. via an editor extension), update it the same way.

## 3. Install `metals-standalone-client`

Install the macOS pre-built binary into a directory on your `PATH`:

```bash
mkdir -p ~/.local/bin
curl -L -o ~/.local/bin/metals-standalone-client \
  https://github.com/jpablo/metals-standalone-client/releases/latest/download/metals-standalone-client-macos-executable
chmod +x ~/.local/bin/metals-standalone-client
```

(Alternative: clone the repo and run `scala-cli run .` from inside it. That requires
`scala-cli` on `PATH` and is mostly useful if you want to track `main`.)

You can pin a specific Metals version through an env var, e.g.
`METALS_VERSION=1.6.5 metals-standalone-client …`.

## 4. Make sure SBT is the BSP server for this workspace

Microtonalist already contains `.bsp/sbt.json`, so SBT is the registered BSP server for the
workspace and Metals will pick it up automatically. If that file is ever missing, regenerate
it from the project root with:

```bash
sbt bspConfig
```

Using SBT (instead of Bloop) as the BSP server matters because — as the Metals docs note —
it guarantees that whether you compile from the editor, from the terminal, or via an MCP
tool call from Claude, everything sees the same compilation state.

## 5. Warm up SBT (optional but recommended)

In a dedicated terminal, start an SBT shell from the repo root and leave it open for the
duration of your Claude Code session:

```bash
cd ~/Development/microtonalist
sbt
```

When Metals connects via BSP it will reuse this server, which makes the initial workspace
import noticeably faster and keeps incremental compiles snappy.

## 6. Start Metals headlessly with the standalone client

In a second terminal, from the repo root, launch the standalone client. Keep this process
running for the entire Claude Code session — when you Ctrl-C it, the MCP server goes away.

```bash
cd ~/Development/microtonalist
metals-standalone-client --verbose .
```

What happens behind the scenes:

1. The client discovers / launches Metals as a subprocess via Coursier.
2. It performs the LSP `initialize` / `initialized` handshake over stdin/stdout.
3. It pushes the user settings `startMcpServer: true` and `mcpClient: claude` into Metals.
4. Metals imports the build through BSP — using SBT, because of `.bsp/sbt.json` — and
   starts its built-in MCP server on a chosen local port.
5. Metals writes a `.mcp.json` file at the repo root containing the URL Claude should use.
6. The standalone client keeps the LSP session alive and health-checks the MCP endpoint.

Watch the console for the URL and for any BSP import errors.

## 7. Verify the generated `.mcp.json`

After Metals has imported the build, a `.mcp.json` will appear at the repo root, roughly:

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

The port is chosen dynamically and changes between runs — don't hard-code it. Add `.mcp.json`
to your local / global gitignore since it's machine- and session-specific.

## 8. Connect Claude Code

In a third terminal, launch Claude Code from the repo root:

```bash
cd ~/Development/microtonalist
claude
```

On first launch Claude Code detects `.mcp.json` and prompts you to authorize the newly
discovered `metals` server. Approve it (and optionally save the choice for the workspace).
Confirm the connection inside Claude Code with:

```
/mcp
```

`metals` should appear in the list with its tools available.

## 9. What you get

Once connected, Claude Code can call Metals' MCP tools, including (names may vary slightly
across Metals versions):

- `compile-file` / `compile-module` — compile a chosen file or build target via BSP/SBT and
  return diagnostics.
- `test` — run a Scala test suite (single class identified by its fully-qualified name; the
  MCP equivalent of `sbt "intonation/testOnly org.calinburloiu.music.intonation.RatioIntervalTest"`).
- `inspect` / `get-docs` / `get-usages` — Scala-aware symbol inspection, documentation, and
  reference search.
- `glob-search` / `typed-glob-search` — symbol search across the workspace.
- `import-build` — re-import the build after changes to `build.sbt` or `project/*.sbt`.
- `find-dep` — search for dependencies via Coursier.

Because BSP is backed by SBT, a `compile-file` after a previous successful compile returns
almost instantly with success or precise diagnostics, which Claude can then act on.

## 10. Recommended workflow with this repo

1. Terminal A — `sbt` shell, kept warm.
2. Terminal B — `metals-standalone-client --verbose .`, kept running.
3. Terminal C — `claude`, your interactive session.
4. Let Claude prefer the Metals MCP tools (`compile-file`, `compile-module`, `test`,
   `inspect`, `get-usages`) over shelling out to `sbt`. Fall back to the `sbt` commands in
   `CLAUDE.md` for full builds (`sbt assembly`), running the `app`/`cli` executables, or
   anything sensitive to JVM startup (e.g. options in `.sbtopts`).
5. After editing `build.sbt` or `project/*.sbt`, ask Claude to call `import-build` (or
   restart the standalone client) so Metals re-imports.

## 11. Troubleshooting

- **Claude Code doesn't see the server.** Make sure `.mcp.json` exists at the workspace root
  and that you launched `claude` from that directory. Run `/mcp` to inspect the connection.
  If the standalone client was killed, the URL in `.mcp.json` is stale.
- **`metals-standalone-client` exits immediately.** Re-run with `--verbose` and check that
  Coursier can reach the network and that the configured `METALS_VERSION` exists.
- **BSP imports Bloop instead of SBT.** Delete the `.bloop/` directory, ensure `.bsp/sbt.json`
  exists (`sbt bspConfig` to regenerate), and restart the standalone client.
- **Compile errors that don't match `sbt compile`.** Almost always means BSP isn't actually
  talking to SBT. Re-run `sbt bspConfig` and restart the standalone client.
- **Stale state after large refactors or `build.sbt` edits.** Stop the standalone client,
  stop the warm `sbt` shell, and start both again. Then ask Claude to call `import-build`.
- **Port already in use.** Kill any leftover Metals JVMs (`jps` / `pkill -f metals`) and
  re-run the standalone client.

## 12. Alternative: editor-driven setup

If you ever do want to drive Metals from an editor instead of headlessly, the only changes
are:

- Skip steps 3 and 6 (no `metals-standalone-client`).
- In your editor's Metals user settings, set:
  ```json
  {
    "metals.defaultBspToBuildTool": true,
    "metals.startMcpServer": true,
    "metals.mcpClient": "claude"
  }
  ```
- Open the workspace in the editor; Metals will write the same `.mcp.json` as in step 7.

Everything from step 8 onward is identical.

## References

- [Metals user configuration](https://scalameta.org/metals/docs/editors/user-configuration/)
- [Metals v1.5.3 — Strontium release notes (MCP server introduction)](https://scalameta.org/metals/blog/2025/05/13/strontium/)
- [Metals v1.6.1 — Osmium release notes (standalone MCP support)](https://scalameta.org/metals/blog/2025/07/31/osmium/)
- [Metals blog](https://scalameta.org/metals/blog/)
- [sbt BSP support in Metals](https://scalameta.org/metals/blog/2020/11/06/sbt-BSP-support/)
- [`metals-standalone-client` on GitHub](https://github.com/jpablo/metals-standalone-client)
- [Claude Code, Metals, and NVIM — Chris Kipp](https://www.chris-kipp.io/blog/claude-code-metals-and-nvim)
- [A Beginner's Guide to Using Scala Metals With its MCP Server — SoftwareMill](https://softwaremill.com/a-beginners-guide-to-using-scala-metals-with-its-model-context-protocol-server/)
- [Adding an MCP server to Claude Code — MCPcat](https://mcpcat.io/guides/adding-an-mcp-server-to-claude-code/)
