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

## Stopping the stack

To stop the background stack:

```bash
bin/microtonalist-dev-stack stop
```
