---
name: gui-qa
description: |
  Manual end-to-end QA of microtonalist's GUI with the `gui-qa` agent, which drives the real app as a black box
  through the QA MCP server. Use it when a change affects the GUI: to write the change's test plan, after the design is
  approved and before the implementation plan; and to run QA against that plan before opening the change's PR, then
  write the QA report and triage the bugs it finds.
---

# gui-qa

The `gui-qa` agent tests the fat JAR of the app from a test plan, through the QA MCP server (`bin/mtlist-qa-mcp`),
which starts the app with virtual MIDI ports. This file is for you, the builder of a GUI change. The agent reads
[`agent-reference.md`](agent-reference.md); you needn't.

Until #393 ports the GUI to JavaFX, the agent can drive only a JavaFX app, and the app is Swing: still write the test
plan, but run it by hand, as
[`docs/development/gui-qa.md`](../../../docs/development/gui-qa.md#running-a-test-plan-by-hand) says, instead of
dispatching the agent. On the Swing app, `start_app` fails, and `app.log` says that the driver drives
only a JavaFX app.

## Write the test plan

- **When:** for a change that affects the GUI, after its design is approved and before its implementation plan, from
  the design's acceptance criteria. The implementation plan's last task runs QA against it.
- **File:** `issues/<NNNNN>-<slug>/<date>-test-plan.md`, dated and tied to a commit like the other issue documents.
  Start from [`test-plan-template.md`](test-plan-template.md).
- **Audience:** the agent and human testers. Write the steps in user terms, and expected results that both can observe:
  what the window shows, what the instrument receives in cents per pitch class, a log without errors. No tool commands.
- **Contents:** the scope and what is out of it; the fixtures; scenarios in Given/When/Then, each with an ID and a
  priority, `must` or `should`; `human` checks, which the agent reports as `not run`; a time-boxed list of areas to
  explore.
- **Fixtures:** the skill's [`fixtures/`](fixtures/), or feature-specific ones next to the plan.
  - `qa.mtlist`: three tunings on C, C Just Major, C Romanian Minor and C Septimal, with inline scales.
  - `qa.mtlist.tracks`: one track, from the QA device `keys` through the pedal tuning changer (soft pedal: previous
    tuning; sostenuto: next) to an MTS octave 1-byte non-real-time tuner, into the QA device `synth`.

  A feature-specific rig names only QA devices, `{ "name": "qa:<device>" }`, `<device>` matching `[A-Za-z0-9_-]{1,32}`,
  and only as a track's `input` or `output` `midiDeviceId`, or as its MTS tuner's `altTuningOutput`; the server
  refuses any other device. The rig is
  `<composition>.tracks`, or the file that a relative `tracksUrl` names, so that both stay in the run directory once
  the agent copies them there. No `$ref` in the rig or at the composition's root, and file names of letters, digits,
  `.`, `_` and `-` only.
- Keep the plan unchanged between runs.

## Run QA

1. **Build** the app, driver and server JARs that the launcher runs: `sbtn assembly`.
2. **Choose the platform:** `headless`, the default, shows no window. Use `desktop` only when the plan needs it, e.g.
   for OS screenshots, and ask the user first, since the app's windows show on screen. OS screenshots also need the
   Screen Recording permission for the app that runs Claude Code.
3. **Record git's state:** save `git status --porcelain` and `git diff` to your scratchpad.
4. **Dispatch the `gui-qa` agent** with the test plan's text and the platform, plus the paths of the plan's own
   fixtures, if any. Never give it the diff.
5. **Compare git's state** with the recorded one. The agent may write only in its run directory: report any
   difference to the user, and commit none of it.
6. **Write the QA report:** `issues/<NNNNN>-<slug>/<date>-qa-report.md`, one per run, with the commit tested, whether
   the working tree was dirty, and the platform. It holds a result per scenario, `pass`, `fail`, `flaky` (failed, then
   passed when retried) or `not run`; each bug with minimal steps to reproduce it; what the exploration found; and the
   MIDI, log and dump lines quoted as evidence. Snapshots stay in the run directory. The PR links the latest report.
7. **Triage** each bug: one in view-model logic gets a failing test first, then its fix; a wiring bug in a view gets
   fixed, and its scenario rerun.

## Troubleshooting

- **The agent reports that its `qa` tools are missing.** Claude Code loads the agent's inline MCP server only in a
  trusted folder, and may read agent definitions only at startup: trust the folder, restart Claude Code, and check
  `/mcp`. Never let the agent start the app another way.
- **The server doesn't start:** `bin/mtlist-qa-mcp` exits with `… not found; run sbtn assembly` when a JAR is missing.
- **`sendmidi` or `receivemidi` isn't installed:** see
  [`docs/development/gui-qa.md`](../../../docs/development/gui-qa.md).
- **`MTLIST_QA_APP_JAR`**, set in the environment that Claude Code starts in, makes the launcher run that app JAR
  instead of the one `sbtn assembly` builds. Use it only to test the QA tooling itself on a stand-in, never for a
  change's QA run.
