# `scripts/development/`

Helper scripts for local development of Microtonalist. Each script is documented
in its own section below.

## `start-sbt-metals.sh`

Launches the local development stack: a long-lived `sbt` JVM (which hosts both
the BSP server that [Scala Metals](https://scalameta.org/metals/) connects to
and the sbt server that the thin client `sbtn` connects to), plus a headless
[Scala Metals](https://scalameta.org/metals/) instance that exposes its MCP
(Model Context Protocol) tools to [Claude Code](https://claude.com/claude-code).
See
[`docs/development-setup/metals-mcp-claude-code.md`](../../docs/development-setup/metals-mcp-claude-code.md)
for background and prerequisites (Metals, Coursier, `metals-standalone-client`).

The script starts two background processes and then blocks:

1. `sbt -Dmicrotonalist.build.targetSuffix=-bsp` — a single sbt JVM that hosts the
   BSP server (used by Metals) and the sbt server (used by `sbtn`). Both human
   developers and Claude Code should issue sbt commands as `sbtn …` so they are
   dispatched into this JVM rather than spawning a second one.
   The `-Dmicrotonalist.build.targetSuffix=-bsp` system property routes every project's
   `target` directory to `<project>/target-bsp/` (see `targetSuffixOverride` in
   `build.sbt`), so this BSP-server sbt does not share `classes/` directories
   with any ad-hoc CLI `sbt` invocations issued without that property. See
   [issue #186](https://github.com/calinburloiu/microtonalist/issues/186) for
   the failure mode that motivated this isolation.
2. `metals-standalone-client --verbose . -- -Dmetals.mcpClient=claude` — drives
   Metals as a headless LSP client and makes Metals write `.mcp.json` at the
   repo root for Claude Code to pick up.

Once `.mcp.json` is written, the script warms up the build by sending `compile`
to the running SBT shell (SBT and Metals share the same BSP state, so this
also warms what Metals' MCP tools later see).

To run further sbt commands against the same server (the recommended pattern,
to avoid spawning a second sbt JVM that races the BSP server), use the sbt
thin client `sbtn` from another terminal — for example, `sbtn "tuner/test"`.

The script refuses to launch when it detects that another sbt server is
already running for this project (typically left behind by a prior `sbtn`
invocation). It prints the orphan PID and the command to stop it. Pass
`--force` (`-f`) to launch anyway — but note that `sbtn` will route to the
orphan, not to the BSP server we are about to start, so this is rarely what
you want.

Output goes to three log files under `logs/` at the repo root:

- `logs/sbt.log`
- `logs/metals-standalone-client.log`
- `logs/start-sbt-metals.log` (only when run in the background, see below)

Older logs are discarded on each run.

### Run in the foreground

From the repo root:

```bash
./scripts/development/start-sbt-metals.sh
```

The script blocks until you stop it. Press **Ctrl-C** to shut it down — the
trap will stop both background processes and remove the FIFO it uses for SBT's
stdin.

### Run in the background

Pass `--background` (or the short alias `-d`):

```bash
./scripts/development/start-sbt-metals.sh --background
```

The script handles `nohup`, log redirection, PID-file recording, and `disown`
internally. The PID is written to `logs/start-sbt-metals.pid`; the wrapper's
stdout/stderr go to `logs/start-sbt-metals.log`. The two per-process logs are
still written to `logs/sbt.log` and `logs/metals-standalone-client.log`.

A second `--background` invocation while one is already running is refused; use
the stop script first.

Tail the logs to follow progress:

```bash
tail -f logs/start-sbt-metals.log
tail -f logs/metals-standalone-client.log
tail -f logs/sbt.log
```

## `stop-sbt-metals.sh`

Stops a running `start-sbt-metals.sh`. Reads the PID from
`logs/start-sbt-metals.pid`, sends SIGTERM, waits up to 10 seconds, escalates
to SIGKILL if needed, then removes the PID file. Idempotent: a missing PID
file or a stale PID is a no-op success.

```bash
./scripts/development/stop-sbt-metals.sh
```

The start script's trap (triggered by SIGTERM) will:

1. Kill `metals-standalone-client`.
2. Send `exit` to SBT via the FIFO it uses as SBT's stdin, wait a few seconds,
   and force-kill SBT if it hasn't stopped.
3. Remove the FIFO and PID file under `logs/`.

**Do not use `kill -INT`** to stop a backgrounded run. When bash backgrounds a
job with `&`, it pre-sets SIGINT to `SIG_IGN` for the child, and POSIX says a
signal ignored on entry to a shell cannot be re-trapped — so the script's
`trap … INT` is silently a no-op for backgrounded invocations and `kill -INT`
does nothing. SIGTERM (which the stop script sends by default) is unaffected
and triggers the trap normally.

**Avoid `kill -9` / `kill -KILL`** — it bypasses the trap, leaving SBT,
`metals-standalone-client`, the FIFO, and a stale `.mcp.json` behind. Only use
it as a last resort, and then clean up manually:

```bash
pkill -f metals-standalone-client
pkill -f 'sbt$'
rm -f .mcp.json logs/.sbt-stdin.fifo logs/start-sbt-metals.pid
```
