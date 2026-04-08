# `scripts/development/`

Helper scripts for local development of Microtonalist. Each script is documented
in its own section below.

## `start-metals-mcp.sh`

Launches [Scala Metals](https://scalameta.org/metals/) headlessly for this
workspace so that [Claude Code](https://claude.com/claude-code) can use its MCP
(Model Context Protocol) tools. See
[`docs/development-setup/metals-mcp-claude-code.md`](../../docs/development-setup/metals-mcp-claude-code.md)
for background and prerequisites (Metals, Coursier, `metals-standalone-client`).

The script starts two background processes and then blocks:

1. `sbt` — an interactive SBT shell that also hosts SBT's BSP server, so Metals
   can reuse a single warm SBT process.
2. `metals-standalone-client --verbose . -- -Dmetals.mcpClient=claude` — drives
   Metals as a headless LSP client and makes Metals write `.mcp.json` at the
   repo root for Claude Code to pick up.

Once `.mcp.json` is written, the script warms up the build by sending `compile`
to the running SBT shell (SBT and Metals share the same BSP state, so this
also warms what Metals' MCP tools later see).

Output goes to three log files under `logs/` at the repo root:

- `logs/sbt.log`
- `logs/metals-standalone-client.log`
- `logs/start-metals-mcp.log` (only when run in the background, see below)

Older logs are discarded on each run.

### Run in the foreground

From the repo root:

```bash
./scripts/development/start-metals-mcp.sh
```

The script blocks until you stop it. Press **Ctrl-C** to shut it down — the
trap will stop both background processes and remove the FIFO it uses for SBT's
stdin.

### Run in the background

```bash
nohup ./scripts/development/start-metals-mcp.sh > logs/start-metals-mcp.log 2>&1 &
echo $! > logs/start-metals-mcp.pid
disown
```

- `nohup` prevents SIGHUP from killing the script when the terminal closes.
- `> logs/start-metals-mcp.log 2>&1` captures the wrapper's stdout/stderr.
  (The two per-process logs are still written to `logs/sbt.log` and
  `logs/metals-standalone-client.log`.)
- `echo $! > logs/start-metals-mcp.pid` records the PID so you can stop it
  later.
- `disown` removes the job from the shell's job table so closing the shell
  won't affect it.

Tail the logs to follow progress:

```bash
tail -f logs/start-metals-mcp.log
tail -f logs/metals-standalone-client.log
tail -f logs/sbt.log
```

### Stopping a background run

Use default `kill` (which sends **SIGTERM**):

```bash
kill "$(cat logs/start-metals-mcp.pid)" && rm logs/start-metals-mcp.pid
```

The script's trap will then:

1. Kill `metals-standalone-client`.
2. Send `exit` to SBT via the FIFO it uses as SBT's stdin, wait a few seconds,
   and force-kill SBT if it hasn't stopped.
3. Remove the FIFO under `logs/`.

**Do not use `kill -INT`** to stop a backgrounded run. When bash backgrounds a
job with `&`, it pre-sets SIGINT to `SIG_IGN` for the child, and POSIX says a
signal ignored on entry to a shell cannot be re-trapped — so the script's
`trap … INT` is silently a no-op for backgrounded invocations and `kill -INT`
does nothing. SIGTERM (the default) is unaffected and triggers the trap
normally.

**Avoid `kill -9` / `kill -KILL`** — it bypasses the trap, leaving SBT,
`metals-standalone-client`, the FIFO, and a stale `.mcp.json` behind. Only use
it as a last resort, and then clean up manually:

```bash
pkill -f metals-standalone-client
pkill -f 'sbt$'
rm -f .mcp.json logs/.sbt-stdin.fifo logs/start-metals-mcp.pid
```
