# Plan: Isolate Metals BSP-server sbt from CLI sbt

Resolves [#186](https://github.com/calinburloiu/microtonalist/issues/186).

## Context

When `sbt` runs as Metals' BSP server (started by `scripts/development/start-sbt-metals.sh`)
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

Plus tooling polish: a `--background` flag and stop script for `start-sbt-metals.sh`
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
  sys.props.get("microtonalist.build.targetSuffix").filter(_.nonEmpty) match {
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

When the BSP-server sbt is started with `-Dmicrotonalist.build.targetSuffix=-bsp`, every
project compiles to `<project>/target-bsp/` instead of `<project>/target/`. CLI sbt
runs without the property and uses the standard `target/`. The two trees never
collide. `sbt clean` removes the *current* `target` setting's directory, so each
tree is cleaned independently.

`.gitignore`: add `target-bsp/`. `coverageDataDir` already lives outside `target/`
at `coverage-reports/<project-id>/` and is unaffected.

### 3. `start-sbt-metals.sh` changes

a. **Pass the system property to sbt:**
   ```bash
   sbt -Dmicrotonalist.build.targetSuffix=-bsp <"$sbt_fifo" >"$sbt_log" 2>&1 &
   ```

b. **Add `--background` / `-d` flag.** When passed, the script `nohup`-re-execs
   itself, prints the child PID, and exits 0. The re-execed child runs the
   foreground path. Without the flag the script behaves exactly as today.

   **Implementation note:** the *child* writes the PID file (with `$$`), not the
   parent. If the parent wrote it before re-execing, the child's own
   double-start guard would see its own PID as "already running" and exit 1.

c. **Refuse to start a second instance.** If `logs/start-sbt-metals.pid` exists
   and names a live process, exit 1 with a clear message pointing to the stop
   script.

d. **Detect orphan sbt server.** Inspect `project/target/active.json`; if it
   names a live socket owner that is not us, refuse to start (because `sbtn`
   would route to that orphan, not to our BSP server) unless `--force` is
   passed. Print the orphan PID and the kill command in the error message.

### 4. New `stop-sbt-metals.sh`

Reads `logs/start-sbt-metals.pid`, sends SIGTERM, waits up to 10s, escalates to
SIGKILL if needed, removes the PID file. Idempotent: missing PID file or stale
PID is a no-op success.

### 5. CLAUDE.md command updates

Add a new "sbt invocations: prefer the BSP server via `sbtn`" subsection at the
top of the `# Build` section describing the **once-per-session** auto-start
procedure (parallel to the Metals MCP warm-up subsection — agents should run
this check at session start, not before every sbt invocation):

1. **Detect** — check whether `logs/start-sbt-metals.pid` exists and names a live process.
2. **Auto-start if absent** — run `./scripts/development/start-sbt-metals.sh --background`
   and wait until `.mcp.json` appears at the repo root (timeout ~3 min). The
   script refuses to launch when an orphan sbt server already owns the build's
   socket; in that case follow the printed instructions or pass `--force`.
3. **Confirm `sbtn` routes correctly** — run one sbt command and confirm
   `logs/sbt.log` grew. If not, `sbtn` connected to a different sbt server —
   investigate before continuing.
4. **Fall back to `sbt`** — only if step 2 fails to produce `.mcp.json` within
   the timeout. Note in the response why the stack could not be started.

Update each command snippet in the Compiling, Test, and Coverage sections to
use `sbtn` instead of `sbt`.

### 6. `scripts/development/README.md` and `docs/development-setup/metals-mcp-claude-code.md`

- Replace the manual nohup/disown/PID-file recipe in the README with
  `start-sbt-metals.sh --background` and `stop-sbt-metals.sh`. Add a section
  for `stop-sbt-metals.sh`.
- In `metals-mcp-claude-code.md` step 5, document `--background`/`-d` and the
  `-Dmicrotonalist.build.targetSuffix=-bsp` isolation.
- In step 10 (recommended workflow), tell users to run sbt commands via
  `sbtn` when the server is up.

## Files changed

| File                                               | Change                                                                                                                       |
|----------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| `build.sbt`                                        | Add `targetSuffixOverride` system-property reader, append to `commonSettings`.                                               |
| `.gitignore`                                       | Add `target-bsp/`.                                                                                                           |
| `scripts/development/start-sbt-metals.sh`          | Pass `-Dmicrotonalist.build.targetSuffix=-bsp` to sbt; add `--background`/`-d` flag; refuse double-start; PID-file handling. |
| `scripts/development/stop-sbt-metals.sh`           | New.                                                                                                                         |
| `scripts/development/README.md`                    | Replace manual nohup recipe; document stop script.                                                                           |
| `docs/development-setup/metals-mcp-claude-code.md` | Document new flag, isolation, and `sbtn` workflow.                                                                           |
| `CLAUDE.md`                                        | New "sbt invocations" subsection; switch Compile/Test/Coverage examples to `sbtn`.                                           |

## Verification (executed during implementation)

1. **Build override smoke test (no Metals running):** ✅
    - `sbt -Dmicrotonalist.build.targetSuffix=-bsp 'show tuner/target' …` →
     `…/tuner/target-bsp` (and same for `businessync`, `app`).
   - Without the property → `…/tuner/target` (default).

2. **Background lifecycle:** ✅
   - `start-sbt-metals.sh --background` → returns immediately, PID file written,
     worker process alive.
   - Second `--background` while running → refused with clear message.
   - `stop-sbt-metals.sh` → SIGTERM, trap fires, processes exit, PID file removed,
     FIFO removed.
   - Second `stop-sbt-metals.sh` → idempotent no-op.

3. **`sbtn` routing into the BSP server:** ✅
   - With `start-sbt-metals.sh --background` running and no orphan sbt daemons:
     `sbtn 'show businessync/target'` → `…/businessync/target-bsp`,
     `logs/sbt.log` grew (39 bytes on first run, 578 bytes after the post-review
     re-test). Both confirm the command was executed by the BSP server (carrying
     the `targetSuffix` system property), not by a newly spawned sbt JVM.

4. **Orphan-server detection (`--force` guard):** ✅
   - With a leftover sbt JVM owning the deterministic socket dir,
     `start-sbt-metals.sh --background` refused to launch and printed the
     orphan PID and the kill command (exit code 1). After `kill <pid>`, the
     same command succeeded.

5. **Skipped (heavy):** the full re-run of the original failing
   `coverageModule tuner` scenario via `sbtn`. Worth doing in a manual smoke
   test before merge, but the `sbtn`-routing test above is the load-bearing
   evidence.

## Follow-up

[#188](https://github.com/calinburloiu/microtonalist/issues/188) tracks
switching the BSP-server launch from interactive `sbt` + FIFO to `sbt -bsp`,
which simplifies the script's IPC model. Deferred from this PR because it
changes the script's communication model and warrants its own review.
