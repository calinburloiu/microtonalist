# `bin/`

Executable entry points for Microtonalist. The Scala application launchers
(`microtonalist`, `microtonalist-tool`) speak for themselves; the
`microtonalist-dev-stack` development helper is documented below.

## `microtonalist-dev-stack`

Manages the local development stack: a long-lived `sbt` JVM (which hosts both
the BSP server that [Scala Metals](https://scalameta.org/metals/) connects to
and the sbt server that the thin client `sbtn` connects to), plus a headless
Metals instance that exposes its MCP (Model Context Protocol) tools to
[Claude Code](https://claude.com/claude-code). See
[`docs/development/metals-mcp-claude-code-setup.md`](../docs/development/metals-mcp-claude-code-setup.md)
for background and prerequisites (Metals, Coursier, `metals-standalone-client`).

Three subcommands:

```bash
bin/microtonalist-dev-stack start    # launch (background by default)
bin/microtonalist-dev-stack stop     # stop the running stack
bin/microtonalist-dev-stack status   # exit 0 if running, 1 if not
```

### `start`

Launches two background processes (managed by this script):

1. `sbt -Dmicrotonalist.build.targetSuffix=-bsp` — a single sbt JVM that hosts
   the BSP server (used by Metals) and the sbt server (used by `sbtn`). Both
   human developers and Claude Code should issue sbt commands as `sbtn …` so
   they are dispatched into this JVM rather than spawning a second one. The
   `-Dmicrotonalist.build.targetSuffix=-bsp` system property routes every
   project's `target` directory to `<project>/target-bsp/` (see
   `targetSuffixOverride` in `build.sbt`), so this BSP-server sbt does not
   share `classes/` directories with any ad-hoc CLI `sbt` invocations issued
   without that property. See
   [issue #186](https://github.com/calinburloiu/microtonalist/issues/186) for
   the failure mode that motivated this isolation.
2. `metals-standalone-client --verbose . -- -Dmetals.mcpClient=claude` —
   drives Metals as a headless LSP client and makes Metals write `.mcp.json`
   at the repo root for Claude Code to pick up.

Once `.mcp.json` is written, the script warms up the build by sending
`compile` to the running SBT shell (SBT and Metals share the same BSP state,
so this also warms what Metals' MCP tools later see).

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
- `logs/microtonalist-dev-stack.log` (only when run in the background)

Older logs are discarded on each `start`.

#### Background (default)

```bash
bin/microtonalist-dev-stack start
```

The script handles `nohup`, log redirection, PID-file recording, and `disown`
internally, then returns immediately. The PID is written to
`logs/microtonalist-dev-stack.pid`; the wrapper's stdout/stderr go to
`logs/microtonalist-dev-stack.log`. A second `start` while one is already
running is refused; run `stop` first.

Tail the logs to follow progress:

```bash
tail -f logs/microtonalist-dev-stack.log
tail -f logs/metals-standalone-client.log
tail -f logs/sbt.log
```

#### Foreground

Pass `--foreground` to attach in the current terminal:

```bash
bin/microtonalist-dev-stack start --foreground
```

The script blocks until you stop it. Press **Ctrl-C** to shut it down — the
trap will stop both background processes and remove the FIFO it uses for
SBT's stdin.

### `stop`

Stops a running `start`. Reads the PID from
`logs/microtonalist-dev-stack.pid`, sends SIGTERM, waits up to 10 seconds,
escalates to SIGKILL if needed, then removes the PID file. Idempotent: a
missing PID file or a stale PID is a no-op success.

```bash
bin/microtonalist-dev-stack stop
```

The `start` trap (triggered by SIGTERM) will:

1. Kill `metals-standalone-client`.
2. Send `exit` to SBT via the FIFO it uses as SBT's stdin, wait a few
   seconds, and force-kill SBT if it hasn't stopped.
3. Remove the FIFO and PID file under `logs/`.

**Do not use `kill -INT`** to stop a backgrounded run. When bash backgrounds
a job with `&`, it pre-sets SIGINT to `SIG_IGN` for the child, and POSIX
says a signal ignored on entry to a shell cannot be re-trapped — so the
script's `trap … INT` is silently a no-op for backgrounded invocations and
`kill -INT` does nothing. SIGTERM (which `stop` sends by default) is
unaffected and triggers the trap normally.

**Avoid `kill -9` / `kill -KILL`** — it bypasses the trap, leaving SBT,
`metals-standalone-client`, the FIFO, and a stale `.mcp.json` behind. Only
use it as a last resort, and then clean up manually:

```bash
pkill -f metals-standalone-client
pkill -f 'sbt$'
rm -f .mcp.json logs/.sbt-stdin.fifo logs/microtonalist-dev-stack.pid
```

### `status`

Verifies the stack is alive via the PID file. Exit code 0 if running, 1 if
not (no PID file, empty PID file, or stale PID).

```bash
bin/microtonalist-dev-stack status
```

Useful both for human spot-checks and for scripted / agent session-start
detection — replaces the older manual
`cat logs/start-sbt-metals.pid && kill -0 …` recipe.
