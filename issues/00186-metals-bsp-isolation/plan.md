# Plan: Isolate Metals BSP-server sbt from CLI sbt

Resolves [#186](https://github.com/calinburloiu/microtonalist/issues/186).

## Context

When `sbt` runs as Metals' BSP server (started by `scripts/development/start-metals-mcp.sh`)
and a developer (or coding agent) also runs a separate `sbt …` from the CLI, both
processes write to the same per-project `target/scala-3.6.3/classes` tree. This was hit
concretely on 2026-04-30: a `sbt "coverageModule tuner"` invocation died with
`error while loading TrackSpecs … TrackSpecs.tasty`, and `logs/metals-standalone-client.log`
showed Metals running its own `compiling app` BSP build at the exact same wall-clock
second — two compilers racing on one output dir.

The marketed sbt-BSP design only avoids this race when *all* compile work is funneled
through one sbt server. So this change has two complementary parts:

1. **Primary fix** — route CLI sbt commands through the running BSP server via the
   sbt thin client (`sbtn`), so there is one writer.
2. **Belt-and-braces** — give the BSP server a separate `target` directory so that
   if a user *does* spawn a second sbt JVM for any reason, they no longer collide.

Plus tooling polish: a `--background` flag and stop script for `start-metals-mcp.sh`
(today the README documents a manual `nohup` + PID-file dance).

## Approach

### 1. Issue, branch, and PR

a. **GitHub issue** [#186](https://github.com/calinburloiu/microtonalist/issues/186) —
   *"Isolate Metals BSP-server sbt from CLI sbt to prevent compile races"*. Label:
   `refactoring`. Add to the *microtonalist* GitHub project (Projects v2, via `gh`
   CLI — the GitHub MCP plugin does not support v2). No milestone (closest existing
   one is *Architecture* but this is developer tooling, not architecture; left for
   the maintainer to assign).

b. **Branch** `refactoring/metals-bsp-isolation` off `main`.

c. **Draft PR** titled `[#186] Isolate Metals BSP-server sbt from CLI sbt`. Body
   includes `Resolves #186` so the issue auto-closes on merge. Single PR for all
   parts so the CLAUDE.md and script changes ship together with the build.sbt knob
   and the new stop script.

### 2. Per-project `target` override controlled by a system property

Add to `build.sbt` (next to existing `commonSettings`):

```scala
lazy val targetSuffixOverride: Seq[Setting[?]] =
  sys.props.get("microtonalist.targetSuffix").filter(_.nonEmpty) match {
    case Some(suffix) => Seq(target := baseDirectory.value / s"target$suffix")
    case None         => Seq.empty
  }

lazy val commonSettings = Seq(
  // … existing settings …
) ++ targetSuffixOverride
```

`commonSettings` is applied to every project, so the override propagates to all
subprojects. `target` is a per-project key in sbt 1.x, so `ThisBuild / target` does
not propagate — verified in practice.

When the BSP-server sbt is started with `-Dmicrotonalist.targetSuffix=-bsp`, every
project compiles to `<project>/target-bsp/` instead of `<project>/target/`. CLI sbt
runs without the property and uses the standard `target/`. The two trees never
collide. `sbt clean` removes the *current* `target` setting's directory, so each
tree is cleaned independently.

`.gitignore`: add `target-bsp/`. `coverageDataDir` already lives outside `target/`
at `coverage-reports/<project-id>/` and is unaffected.

### 3. `start-metals-mcp.sh` changes

a. **Pass the system property to sbt:**
   ```bash
   sbt -Dmicrotonalist.targetSuffix=-bsp <"$sbt_fifo" >"$sbt_log" 2>&1 &
   ```

b. **Add `--background` / `-d` flag.** When passed, the script `nohup`-re-execs
   itself, prints the child PID, and exits 0. The re-execed child runs the
   foreground path. Without the flag the script behaves exactly as today.

   **Implementation note:** the *child* writes the PID file (with `$$`), not the
   parent. If the parent wrote it before re-execing, the child's own
   double-start guard would see its own PID as "already running" and exit 1.

c. **Refuse to start a second instance.** If `logs/start-metals-mcp.pid` exists
   and names a live process, exit 1 with a clear message pointing to the stop
   script.

### 4. New `stop-metals-mcp.sh`

Reads `logs/start-metals-mcp.pid`, sends SIGTERM, waits up to 10s, escalates to
SIGKILL if needed, removes the PID file. Idempotent: missing PID file or stale
PID is a no-op success.

### 5. CLAUDE.md command updates

Add a new "sbt invocations: prefer the BSP server via `sbtn`" subsection at the
top of the `# Build` section describing the auto-start-then-fallback procedure
that agents and developers must follow before any sbt invocation:

1. **Detect** — check whether `logs/start-metals-mcp.pid` exists and names a live process.
2. **Auto-start if absent** — run `./scripts/development/start-metals-mcp.sh --background`
   and wait until `.mcp.json` appears at the repo root (timeout ~3 min).
3. **Use `sbtn`** — once the server is up, run all sbt commands through `sbtn …`.
   Confirm the first command was relayed by tailing `logs/sbt.log` and checking
   that the command and its output appear there. If `logs/sbt.log` does not grow,
   `sbtn` started its own JVM — investigate before continuing.
4. **Fall back to `sbt`** — only if step 2 fails to produce `.mcp.json` within
   the timeout. Note in the response why the BSP server could not be started.

Update each command snippet in the Compiling, Test, and Coverage sections to
use `sbtn` instead of `sbt`.

Add a one-line caveat: known issue
[sbt/sbt#6096](https://github.com/sbt/sbt/issues/6096) — `sbtn` can hang
against a server started via `sbt -bsp`. Our server is started as a normal
`sbt` shell with BSP enabled, so we should not be affected.

### 6. `scripts/development/README.md` and `docs/development-setup/metals-mcp-claude-code.md`

- Replace the manual nohup/disown/PID-file recipe in the README with
  `start-metals-mcp.sh --background` and `stop-metals-mcp.sh`. Add a section
  for `stop-metals-mcp.sh`.
- In `metals-mcp-claude-code.md` step 5, document `--background`/`-d` and the
  `-Dmicrotonalist.targetSuffix=-bsp` isolation.
- In step 10 (recommended workflow), tell users to run sbt commands via
  `sbtn` when the server is up.

## Files changed

| File | Change |
|---|---|
| `build.sbt` | Add `targetSuffixOverride` system-property reader, append to `commonSettings`. |
| `.gitignore` | Add `target-bsp/`. |
| `scripts/development/start-metals-mcp.sh` | Pass `-Dmicrotonalist.targetSuffix=-bsp` to sbt; add `--background`/`-d` flag; refuse double-start; PID-file handling. |
| `scripts/development/stop-metals-mcp.sh` | New. |
| `scripts/development/README.md` | Replace manual nohup recipe; document stop script. |
| `docs/development-setup/metals-mcp-claude-code.md` | Document new flag, isolation, and `sbtn` workflow. |
| `CLAUDE.md` | New "sbt invocations" subsection; switch Compile/Test/Coverage examples to `sbtn`. |

## Verification (executed during implementation)

1. **Build override smoke test (no Metals running):** ✅
   - `sbt -Dmicrotonalist.targetSuffix=-bsp 'show tuner/target' …` →
     `…/tuner/target-bsp` (and same for `businessync`, `app`).
   - Without the property → `…/tuner/target` (default).

2. **Background lifecycle:** ✅
   - `start-metals-mcp.sh --background` → returns immediately, PID file written,
     worker process alive.
   - Second `--background` while running → refused with clear message.
   - `stop-metals-mcp.sh` → SIGTERM, trap fires, processes exit, PID file removed,
     FIFO removed.
   - Second `stop-metals-mcp.sh` → idempotent no-op.

3. **`sbtn` routing into the BSP server:** ✅
   - With `start-metals-mcp.sh --background` running and no orphan sbt daemons:
     `sbtn 'show businessync/target'` → `…/businessync/target-bsp`,
     `logs/sbt.log` grew by 39 bytes. Both confirm the command was executed by
     the BSP server (carrying the `targetSuffix` system property), not by a
     newly spawned sbt JVM.

4. **Skipped (heavy):** the full re-run of the original failing
   `coverageModule tuner` scenario via `sbtn`. Worth doing in a manual smoke
   test before merge, but the `sbtn`-routing test above is the load-bearing
   evidence.

## Operational note (caveat for users)

`sbtn` connects to whichever sbt server owns the build's deterministic server
directory under `~/.sbt/1.0/server/<hash>/`. If a stray sbt daemon (e.g. one
left behind by an earlier `sbtn` invocation made *before* `start-metals-mcp.sh`
was started) holds that directory, `sbtn` will route to it instead of to the
BSP-server sbt. Symptom: `sbtn 'show <project>/target'` returns `target` rather
than `target-bsp`, and `logs/sbt.log` does not grow.

Mitigation: ensure `start-metals-mcp.sh` is the first sbt invocation in a session.
If a stray daemon exists, kill it (`pgrep -f 'sbt-launch.*--detach-stdio'` finds
sbtn-spawned daemons specifically) and re-run `sbtn`.
