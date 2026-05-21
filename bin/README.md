# `bin/`

Helper scripts for local development of Microtonalist.

## `microtonalist-dev-stack`

Unified script to manage the sbt+BSP and Metals MCP development stack. Exposes three commands: `start`, `stop`, and
`status`.

### Background

The development stack is a long-lived `sbt` JVM (which hosts both the BSP server
that [Scala Metals](https://scalameta.org/metals/) connects to and the sbt server that the thin client `sbtn` connects
to), plus a headless [Scala Metals](https://scalameta.org/metals/) instance that exposes its MCP (Model Context
Protocol) tools to [Claude Code](https://claude.com/claude-code).

See [`docs/development/metals-mcp-claude-code-setup.md`](../docs/development/metals-mcp-claude-code-setup.md) for
background and prerequisites (Metals, Coursier, `metals-standalone-client`).

### `microtonalist-dev-stack start` — Launch the development stack

The script starts two background processes:

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
`--force` to launch anyway — but note that `sbtn` will route to the
orphan, not to the BSP server we are about to start, so this is rarely what
you want.

Output goes to three log files under `logs/` at the repo root:

- `logs/sbt.log`
- `logs/metals-standalone-client.log`
- `logs/microtonalist-dev-stack.log` (when run in the background)

Older logs are discarded on each run.

#### Run in the background (default)

From the repo root:

```bash
bin/microtonalist-dev-stack start
```

The script detaches immediately and prints the PID. The PID is written to
`logs/microtonalist-dev-stack.pid`; the script's stdout/stderr go to
`logs/microtonalist-dev-stack.log`. The two per-process logs are still written
to `logs/sbt.log` and `logs/metals-standalone-client.log`.

A second `start` invocation while one is already running is refused; use
`bin/microtonalist-dev-stack stop` first.

Tail the logs to follow progress:

```bash
tail -f logs/microtonalist-dev-stack.log
tail -f logs/metals-standalone-client.log
tail -f logs/sbt.log
```

#### Run in the foreground

Pass `--foreground` (or the short alias `-f`):

```bash
bin/microtonalist-dev-stack start --foreground
```

The script blocks until you stop it. Press **Ctrl-C** to shut it down — the
trap will stop both background processes and remove the FIFO it uses for SBT's
stdin.

### `microtonalist-dev-stack stop` — Stop the development stack

Stops a running `microtonalist-dev-stack start`. Reads the PID from
`logs/microtonalist-dev-stack.pid`, sends SIGTERM, waits up to 10 seconds,
escalates to SIGKILL if needed, then removes the PID file. Idempotent: a
missing PID file or a stale PID is a no-op success.

```bash
bin/microtonalist-dev-stack stop
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
rm -f .mcp.json logs/.sbt-stdin.fifo logs/microtonalist-dev-stack.pid
```

### `microtonalist-dev-stack status` — Check if the stack is running

Checks whether the development stack is currently running by verifying the
PID file and the liveness of the process.

```bash
bin/microtonalist-dev-stack status
```

Returns exit code 0 if the stack is running, 1 if not. Prints a message to
stdout indicating the status.
