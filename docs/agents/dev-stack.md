# Development Stack: starting it and routing `sbtn`

The root `CLAUDE.md` covers detecting whether the development stack is running (`bin/microtonalist-dev-stack status`).
This file holds what to do **when it is not running** — auto-starting it, confirming `sbtn` routing, falling back to
`sbt`, and stopping the stack.

Background: the development stack started by `bin/microtonalist-dev-stack start` runs a single long-lived sbt JVM that
serves two clients at once: Metals (via BSP) and the `sbtn` thin client (via the sbt server protocol). Run all sbt
commands through `sbtn` so they execute in that one JVM rather than spawning a fresh `sbt` JVM each time — spawning
duplicates compilation work and runs the second JVM with no awareness of the BSP server's incremental state. The
per-project `target` isolation (see [Build output directories](../development/build.md)) is belt-and-braces protection:
it keeps a stray second `sbt` from racing the BSP server on the same `classes/` tree (which is what produced the TASTy
load errors in issue #186), but routing through `sbtn` is the primary fix.

## When the stack is not running

Work through these steps (if `status` reported the stack already running, you are done — see "After the check" below):

1. **Auto-start the stack.** Start it in the background (the default):
   ```bash
   bin/microtonalist-dev-stack start
   ```
   Then wait until `.mcp.json` appears at the repo root (timeout ~3 minutes). The script refuses to launch when
   it detects another sbt server already running for this project (e.g. an orphan left by a prior `sbtn`
   invocation); in that case follow the instructions it prints to stop the orphan, or pass `--force` (`-f`) if
   you have reason to override.
2. **Confirm `sbtn` routes correctly** by running one sbt command (anything: `sbtn 'show tuner/target'`) and
   confirming `logs/sbt.log` grew. If `logs/sbt.log` did not grow, `sbtn` connected to a different sbt server —
   investigate before continuing.
3. **Fall back to `sbt`** only if step 1 fails to produce `.mcp.json` within the timeout. In that case note in
   your response why the stack could not be started so the user can investigate.

## After the check

Every subsequent sbt command in the conversation should just use `sbtn …` — trust that the stack is up unless a command
unexpectedly fails (e.g. with a connect error), in which case re-run the check.

## Restarting after a `build.sbt` change

The long-lived sbt JVM and Metals read the build definition (`build.sbt`,
`project/*.sbt`, `project/*.scala`, `project/plugins.sbt`) **only at startup**. After editing any of them, restart
the stack so both clients re-import the changed build:

```bash
bin/microtonalist-dev-stack restart
```

`restart` is just `stop` followed by `start` (it forwards the same options, e.g. `--foreground`, `--force`). A bare
`sbtn reload` re-reads the build into the sbt server, which is enough for subsequent `sbtn` commands to see changed
*settings* (e.g. coverage thresholds — this is why `sbtn reload` suffices for a coverage-threshold edit), but it does
**not** re-import the build into Metals. Structural changes (new modules, changed dependencies, source generators) need
the full restart above.

### Consequence for the Metals MCP and the Claude session

Metals exposes its MCP tools over an **HTTP** endpoint (`http://localhost:<port>/mcp`, recorded both in `.mcp.json` and
in `.metals/mcp.json`). Metals persists that URL in `.metals/mcp.json` and **reuses the same port across restarts**, so
a
`restart` is normally transparent to an active Claude Code session: the next `mcp__metals__*` call simply reaches the
new
Metals process on the unchanged URL. This is verified empirically — after a `restart`, `mcp__metals__list-modules`
worked
with **no `/mcp` reconnect and no session restart**.

Caveats and fallbacks:

- The agent cannot *initiate* an MCP reconnect — MCP connections are owned by the Claude Code harness, not the agent —
  but for Metals it usually does not need to, thanks to the stable HTTP port.
- The port is not *guaranteed* stable: if `.metals/` is cleared, or the persisted port is already taken at startup,
  Metals selects a new one and rewrites both JSON files. Then, the harness's cached HTTP target is stale and
  `mcp__metals__*` calls fail with a connection error; recover by running `/mcp` (which re-reads `.mcp.json`) or, as a
  last resort, restarting the Claude session. `sbtn` keeps working across the restart regardless, so fall back to it (
  and
  to textual tools) if the Metals MCP is ever unreachable.
- The `scoverage-inspector` MCP is a **stdio** server spawned by Claude Code itself (not by the dev-stack), so a
  dev-stack restart does not touch it.

After a restart the dev-stack already re-sends a warm-up `compile`; re-run `mcp__metals__compile-full` (see the root
`CLAUDE.md` warm-up step) only if you need to be sure the SemanticDB index is fresh.

## Stopping the stack

To stop the background stack:

```bash
bin/microtonalist-dev-stack stop
```
