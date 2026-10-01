# `bin/`

Executable entry points for Microtonalist. Their names start with `mtlist`, so
that they don't clash with other commands on your `PATH`:

| Script | Purpose |
| ------ | ------- |
| `mtlist` | Runs the application's fat JAR (`app`). |
| `mtlist-tool` | Runs the utility tool's fat JAR (`cli`), e.g. to list MIDI devices. |
| [`mtlist-dev-stack`](#mtlist-dev-stack) | Manages the development stack: the sbt server and Metals MCP. |
| [`mtlist-coverage-modules`, `mtlist-coverage-all`, `mtlist-coverage-check`](#mtlist-coverage-) | Measure the code coverage. |
| [`mtlist-agents-test-filter`](#mtlist-agents-test-filter) | Shrinks the output of an sbt test run. |
| `mtlist-scoverage-inspector` | Runs the `scoverage-inspector` command-line tool; see [`docs/development/claude-code-setup.md`](../docs/development/claude-code-setup.md#scoverage-inspector-mcp). |

With [direnv](https://direnv.net/) set up (see
[`docs/development/README.md`](../docs/development/README.md#direnv)), the
repository's `.envrc` puts this directory on your `PATH` while you are inside
the repository, so you can run a script by its name, e.g. `mtlist-dev-stack
status`, from any subdirectory. Without direnv, run it as `bin/<script>` from
the repository root, as the examples below do.

## `mtlist` and `mtlist-tool`

Run the fat JARs built by `sbt assembly` (see
[`docs/development/build.md`](../docs/development/build.md)) from
`app/target/scala-<version>/` and `cli/target/scala-<version>/`. The Scala
version in those paths comes from `project/scala-version`, which `build.sbt`
reads too, so the scripts follow a Scala upgrade.

## `mtlist-dev-stack`

Manages the local development stack: a long-lived `sbt` JVM (which hosts both
the BSP server that [Scala Metals](https://scalameta.org/metals/) connects to
and the sbt server that the thin client `sbtn` connects to), plus a headless
Metals instance that exposes its MCP (Model Context Protocol) tools to
[Claude Code](https://claude.com/claude-code). See
[`docs/development/metals-mcp-claude-code-setup.md`](../docs/development/metals-mcp-claude-code-setup.md)
for background and prerequisites (Metals, Coursier, `metals-standalone-client`).

Four subcommands:

```bash
bin/mtlist-dev-stack start    # launch (background by default) and wait until ready
bin/mtlist-dev-stack stop     # stop the running stack
bin/mtlist-dev-stack restart  # stop then start (after a build.sbt change)
bin/mtlist-dev-stack status   # exit 0 if running, 1 if not
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
   drives Metals as a headless LSP client and makes Metals start its MCP
   server, recorded in `.mcp.json` at the repo root for Claude Code to pick
   up. It runs in a process group of
   its own, which the Metals server and the BSP client that Metals starts
   join, so that the script can stop that whole process tree.

Once Metals reports that its MCP server has started, the script merges the
project's `scoverage-inspector` MCP server into `.mcp.json` (this requires
`uv`/`uvx` on `PATH` — if it is missing, the script warns and skips
registration). Metals writes its entry only into a `.mcp.json` that lacks one,
keeping the file's other entries. Otherwise it reuses the port recorded there,
so that a Claude Code session keeps reaching it across a restart: keep
`.mcp.json` rather than deleting it. Once sbt reports that its
server has started, the script warms up the build by sending `compile` to the
running SBT shell (SBT and Metals share the same BSP state, so this also warms
what Metals' MCP tools later see), and the stack is ready.

To run further sbt commands against the same server (the recommended pattern,
to avoid spawning a second sbt JVM that races the BSP server), use the sbt
thin client `sbtn` from another terminal — for example, `sbtn "tuner/test"`.

Before it launches, the script stops the processes that a stack killed before
its shutdown finished left running (see [`stop`](#stop)). It refuses to launch
when it detects that another sbt server is already running for this project
(typically left behind by a prior `sbtn` invocation). It prints the orphan PID
and the command to stop it, and says to stop any other Metals for this project
(such as an IDE's) first: Metals' BSP client starts a new sbt server when the
one it uses goes away. Pass
`--force` (`-f`) to launch anyway — but note that `sbtn` will route to the
orphan, not to the BSP server we are about to start, so this is rarely what
you want.

Output goes to three log files under `logs/` at the repo root:

- `logs/sbt.log`
- `logs/metals-standalone-client.log`
- `logs/mtlist-dev-stack.log` (only when run in the background)

Older logs are discarded on each `start`.

#### Background (default)

```bash
bin/mtlist-dev-stack start
```

The script handles `nohup`, log redirection, PID-file recording, and `disown`
internally, then waits until the stack is ready: sbt's server and Metals' MCP
server have started. That usually takes seconds, but can take
minutes on a first start that downloads dependencies; the script gives up
waiting after 10 minutes, leaving the stack starting. It exits with 0 once
the stack is ready, and non-zero if the stack shuts down meanwhile (for
example, because sbt can't start its server), printing the end of its log. The
PID is written to `logs/mtlist-dev-stack.pid`; the wrapper's stdout/stderr go
to `logs/mtlist-dev-stack.log`. A second `start` while one is already running
is refused; run `stop` first.

Tail the logs to follow progress:

```bash
tail -f logs/mtlist-dev-stack.log
tail -f logs/metals-standalone-client.log
tail -f logs/sbt.log
```

#### Foreground

Pass `--foreground` to attach in the current terminal:

```bash
bin/mtlist-dev-stack start --foreground
```

The script blocks until you stop it. Press **Ctrl-C** to shut it down — the
trap will stop both background processes and remove the FIFO it uses for
SBT's stdin.

### Shutdown

The stack shuts down when it is stopped, or when one of its two processes
exits by itself; then it exits with a non-zero status. Either way, it:

1. Stops Metals' process group — `metals-standalone-client`, the Metals server
   and its BSP client — and waits for it to exit. It must go first: a BSP
   client that outlives sbt starts a new sbt server, without the stack's
   options, which then blocks the next `start`
   ([#348](https://github.com/calinburloiu/microtonalist/issues/348)).
2. Sends `exit` to SBT via the FIFO it uses as SBT's stdin, waits up to 10
   seconds, and stops SBT itself if it hasn't exited.
3. Removes the FIFO, the PID file, the `logs/mtlist-dev-stack.ready` file
   that marks the stack as ready, and the files in which it records the
   process group of Metals and the PID of sbt (see [`stop`](#stop)).
4. Warns if an sbt server is still running for this project, with the
   command to stop it.

### `stop`

Stops a running `start`. Reads the PID from `logs/mtlist-dev-stack.pid`,
sends SIGTERM, which shuts the stack down as described above, waits up to 60
seconds, escalates to SIGKILL if needed, then removes the PID file.

A stack killed before its shutdown finished, by that SIGKILL or by a
`kill -9`, leaves its processes running. So `stop` then stops those that the
stack recorded, Metals' process group (`logs/mtlist-dev-stack.metals-pgid`)
first, then sbt (`logs/mtlist-dev-stack.sbt-pid`), checking that each is
still the stack's. Like the stack, it warns if an sbt server is still running
for this project afterwards. Idempotent: a missing PID file or a stale PID is
a success.

```bash
bin/mtlist-dev-stack stop
```

**Do not use `kill -INT`** to stop a backgrounded run. When bash backgrounds
a job with `&`, it pre-sets SIGINT to `SIG_IGN` for the child, and POSIX
says a signal ignored on entry to a shell cannot be re-trapped — so the
script's `trap … INT` is silently a no-op for backgrounded invocations and
`kill -INT` does nothing. SIGTERM (which `stop` sends by default) is
unaffected and triggers the trap normally.

**Avoid `kill -9` / `kill -KILL`** — it bypasses the trap, leaving SBT,
`metals-standalone-client` and the FIFO behind. Only
use it as a last resort, and then run `stop`, which stops the processes the
stack left running. To clean up by hand instead, stop Metals' process group
first, then the sbt server that owns this build's socket: stopping only sbt
lets Metals start another one.

```bash
kill -- -"$(cat logs/mtlist-dev-stack.metals-pgid)"
kill "$(lsof -t "$(grep -oE 'local://[^"]+' project/target/active.json | sed 's|^local://||')")"
rm -f logs/.sbt-stdin.fifo logs/mtlist-dev-stack.{pid,ready,metals-pgid,sbt-pid}
```

### `restart`

Stops the stack (if running) and starts it again, forwarding any `start`
options (`--foreground`, `--force` / `-f`). Equivalent to a `stop` followed by
a `start`, so in the background it waits until the new stack is ready, and
exits non-zero if it shuts down instead.

```bash
bin/mtlist-dev-stack restart
```

Use it after editing `build.sbt` (or the `project/` build files): the
long-lived sbt JVM and Metals read the build definition only at startup, so a
structural change (new modules, changed dependencies, source generators) takes
effect only once the stack re-imports it. A bare `sbtn reload` re-reads the
build into the sbt server — enough for `sbtn` to see changed *settings* such as
coverage thresholds — but does not re-import it into Metals. Restarting relaunches
Metals, but Metals reuses the HTTP MCP port recorded in `.mcp.json` (or else in
`.metals/mcp.json`), so an
active Claude Code session's Metals MCP keeps working across the restart without a
`/mcp` reconnect — see
[`../docs/agents/dev-stack.md`](../docs/agents/dev-stack.md) for the caveats (port
not guaranteed stable; `/mcp` or a session restart as fallback).

### `status`

Verifies the stack is alive via the PID file. Exit code 0 if running, 1 if
not (no PID file, empty PID file, or stale PID). A running stack is reported
as either ready or still starting.

```bash
bin/mtlist-dev-stack status
```

Useful both for human spot-checks and for scripted / agent session-start
detection.

## `mtlist-coverage-*`

Run the coverage workflow of
[`docs/development/coverage.md`](../docs/development/coverage.md):

```bash
bin/mtlist-coverage-modules tuner intonation  # the given modules, with their own tests only
bin/mtlist-coverage-all                       # every module, plus the aggregate
bin/mtlist-coverage-check                     # CI's check against the thresholds
```

They run sbt's `coverageModules`, `coverageAll` and `coverageCheck` commands in
a fresh sbt JVM with `-Dmicrotonalist.build.targetSuffix=-scoverage`, which
builds into `<project>/target-scoverage/`. So a coverage run doesn't race the
development stack's sbt (`target-bsp/`) or an IDE (`target/`) on the same
classes, and the stack can keep running meanwhile. With
`-Dsbt.server.autostart=false`, that sbt starts no sbt server: run while the
stack is down, it would take the build's server socket, which `sbtn` would then
connect to and which would keep the stack from starting. Coverage commands
don't work through `sbtn`: the stack's sbt server builds into `target-bsp/`,
whatever options `sbtn` gets.

## `mtlist-agents-test-filter`

A stdin→stdout filter that shrinks a full sbt test run's output. It drops the
noise the ScalaTest reporter flags cannot suppress (sbt's "no tests" lines for
empty modules, SLF4J warnings, and the per-module green summaries) while letting
every failure and abort signal through, and it **exits non-zero** when the run
reports a problem — so a pipeline can gate on the result:

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS" 2>&1 | bin/mtlist-agents-test-filter
```

It is Claude-Code-agnostic — usable by hand or in CI — but under Claude Code a
committed `PreToolUse` hook applies it to test commands automatically. See the
"Hooks" section of
[`docs/development/claude-code-setup.md`](../docs/development/claude-code-setup.md)
for that wiring and the full keep/drop rules.

## Tests

The scripts' tests, under `tests/`, run them against fake `sbt`, `java` and
`metals-standalone-client` commands:

```bash
python3 -m unittest discover -s bin/tests -p "test_*.py"
```
