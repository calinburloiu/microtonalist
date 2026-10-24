# GUI QA agent: research

- **Date:** 2026-10-22; MIDI design, permissions and modes revised on 2026-10-24, at commit `c0bd0df4`
- **Commit:** `89b5f06d`
- **Issue:** #397. Related: #99, #383.
- **Guide:** [`2026-10-22-gui-qa-in-process-guide.md`](2026-10-22-gui-qa-in-process-guide.md), for testing the
  initial GUI of #99 by hand in process, headless, before any of the tooling below exists.

## Problem

When grooming #99, we decided that JavaFX views get no unit tests and that there are no automated end-to-end (E2E) GUI
tests: views hold no logic, and E2E tests are flaky. View models are written with TDD. That leaves untested:

- the wiring between each view and its view model: bindings, handlers, shortcuts;
- the application wiring in `MicrotonalistApp.run`, and the hops between the UI and business threads;
- layout: truncated labels, ordering, resizing;
- the full loop: a GUI action produces the right MIDI output, and MIDI input, such as a pedal, updates the GUI.

So a feature that changes the GUI needs a manual E2E test before its PR. This document proposes how a QA subagent
runs it, and how a human runs the same test plan.

## Agreed so far

1. A QA subagent tests the fat JAR as a black box, from a written test plan. It doesn't gate CI: it can retry and judge
   whether a failure is a flake, so the flakiness that rules out an E2E suite is acceptable here.
2. Planning a GUI feature produces a test plan file, for the QA agent and for human testers.
3. Custom Scala code in the repo is fine, and so is thin scripting: Bash, or Python for anything more complex.
   Third-party tools: `sendmidi` and `receivemidi` ([gbevin](https://github.com/gbevin/SendMIDI)), required, from
   their releases' `.pkg`.
4. macOS only, like the rest of the agent setup.
5. One mode, in process: a driver inside the app's JVM, headless by default, or on the desktop platform for OS
   screenshots; see [In-process driver](#in-process-driver). Peekaboo mode was dropped; see
   [Peekaboo mode](#peekaboo-mode).
6. A QA MCP server in Scala starts the app with the driver and the MIDI ports, and serves the driver's tools.
7. MIDI goes through virtual ports that the server opens with `sendmidi` and `receivemidi`. The IAC buses were dropped;
   see [MIDI](#midi).
8. Our own MCP library, on play-json, in a new `mcp` module group, covering only the MCP features we need.
9. The agent sends SendMIDI commands and reads ReceiveMIDI's text. It decodes the tuning output with a script from the
   skill, written from the MIDI specification; see [Reading the output](#reading-the-output).
10. The agent has Bash, Read and Write besides the QA server's tools, under rules in its prompt; see
    [Permissions](#permissions).

## Run setup

Each run builds and isolates what it uses. The builder builds the JARs, and the QA MCP server starts and stops the
rest.

| Piece | How |
| ----- | --- |
| App | The builder runs `sbtn assembly` first, so that the JAR matches the working tree. The server runs `java -jar app/app/target-bsp/scala-<version>/microtonalist-app.jar <composition> <config>` and keeps its output in a log file. `bin/mtlist` isn't used: it runs the `target/` JAR, which only a plain `sbt assembly` builds. |
| Config | A scratch HOCON file passed as the second argument, so `~/.microtonalist/microtonalist.conf` is never used. |
| Composition and rig | The skill's fixtures, a `.mtlist` with inline scales and its `.mtlist.tracks` next to it, or ones the agent writes for a scenario. |
| MIDI | Virtual ports, unique to the run; see [MIDI](#midi). |

The skill tells the agent the pedal tuning changer's defaults: CC 67 (soft pedal) selects the previous tuning, CC 66
(sostenuto) the next one.

## MIDI

The QA server opens the run's virtual ports before it starts the app, and closes them when it stops it. The agent never
runs `sendmidi` or `receivemidi` itself:

- A port must outlive the agent's tool calls: a one-shot `sendmidi virt` unplugs the app's input when it exits.
- The ports must exist before the app starts, and go away with it.
- The agent never names a port, so its MIDI can't reach a real instrument.

The two tools are the only way into and out of their virtual ports: in CoreMIDI, only the process that owns a virtual
source can send through it, and only the one that owns a virtual destination receives what arrives.

| Port | What the server runs | The agent |
| ---- | -------------------- | --------- |
| Into the app | `sendmidi virt <run>-<device> --`, which sends each line written to its standard input | Sends with `send_midi` |
| Out of the app | `receivemidi virt <run>-<device> ts nn`, its standard output appended to `<run dir>/midi-out-<device>.txt` | Reads the file |

- **Vendor in the rig file:** `<Unknown Manufacturer>`, since their JUCE library sets none. The rig file names a port
  as CoreMIDI4J reports it, `CoreMIDI4J - <port>`.
- **Unplugging:** `unplug` and `replug` stop and restart a device's tools. The output keeps going to the same file.
- **Parallel runs,** e.g. in the `microtonalist2` clone, don't collide, since the port names are unique to the run.
- **One-time setup:** install the `.pkg` of each tool's latest GitHub release, not the Homebrew formula, which builds
  with Xcode.

The `.pkg` of SendMIDI 1.4.4 and ReceiveMIDI 1.6.2 are signed with a Developer ID, notarized, and install only the
binary in `/usr/local/bin`, with no install scripts. A Homebrew copy in `/opt/homebrew/bin` would shadow it.

### Sending

`send_midi` takes a device and SendMIDI command lines, and writes them to that device's `sendmidi`:

```json
{ "device": "keys", "lines": ["ch 1 cc 66 127", "ch 1 cc 66 0"] }
```

- The commands are the stimuli, not an oracle, so readable commands beat hex bytes, and the report quotes them.
- A relative timestamp, such as `+00.500`, delays the next command, for timed sequences.
- The process lives for the whole run, so a command's state carries over to later calls. The server rejects the
  commands that change the port, read a file or switch the number base: `dev`, `virt`, `file`, `syf`, `mpp`, `hex` and
  `dec`, and their long forms. The skill makes every line start with `ch <n>`, and writes a hex number with an `H`
  suffix, e.g. `raw 90H 3CH 00H`.
- The server appends each line, with the time it sent it, to `<run dir>/midi-in-<device>.txt`.

### Reading the output

The agent reads ReceiveMIDI's text, not the raw bytes of its `dump`:

```
22:59:36.996   channel  1   control-change    66   127
22:59:36.996   channel  2   pitch-bend          8192
22:59:36.996   system-exclusive hex 7F 7F 08 08 03 7F 7F 40 44 50 2F 42 46 3C 40 40 40 40 40 dec
```

- One message per line, split and timestamped, and written as it arrives.
- ReceiveMIDI decodes the channel messages. A decoder built on the project's code would hide a bug that it shares with
  the encoder; ReceiveMIDI's, from JUCE, shares none.
- A pitch bend is one 14-bit number, so MPE and monophonic pitch bend need no MSB and LSB math.
- SysEx, which carries all the MTS output, stays hex bytes.
- A mark is a line count: `wc -l` before acting, `tail -n +<N>` after.

The decoder script, in the skill, annotates each line: an MTS message with the cents of each pitch class, a pitch bend
with its cents for a given range. With the arithmetic in Bash, the agent would still slice the bytes itself, such as
the device ID and the channel mask, and a consistent slip reads as a `fail` that a retry doesn't reveal. The script is
written from the MIDI specification, not ported from the tuners' encoders, so that a bug there can't cancel out, and it
has tests like the other skill scripts.

The skill gives the agent the layouts of what the tuners send: MTS octave 1-byte and 2-byte, non-real-time and
real-time; MPE, with its pitch bend ranges; and monophonic pitch bend. RPNs, such as MPE's pitch bend sensitivity, are
read from their CC lines.

### Ports of a rig

Proposed, not agreed yet. The app matches a device by its exact name and vendor, and the port names hold the run's ID,
so the agent's rig can't name the ports directly:

- The agent's rig names a QA device `qa:<device>`, e.g. `{ "name": "qa:mts" }`. Before it starts the app, the server
  turns each into `{ "name": "CoreMIDI4J - <run>-<device>", "vendor": "<Unknown Manufacturer>" }`.
- A device used as a track's input gets a `sendmidi`, one used as an output a `receivemidi`, and one used as both gets
  both, under one name, like a real instrument whose pedals feed one track while it receives another's tuning.
- The server rewrites the JSON tree with play-json, not through `rig-format`, so that it depends on no plugin format and
  passes the rest through unchanged, an invalid rig included.
- It rewrites the rig in place, and only in the run directory. The composition finds its rig next to it, or through its
  `tracksUrl`, and resolves relative scale paths against its own location, so moving either file would break the
  other. The skill's fixtures are copied into the run directory first, so that the repo's copies stay unchanged.
- It refuses a rig that names a non-QA device present on the machine, so that the app can't tune a real instrument.
- `unplug` takes a device and removes both its ports, like pulling its cable.

The skill's fixture rig, with one track:

```json
{
  "tracks": [
    {
      "id": "qa",
      "name": "QA",
      "input": { "midiDeviceId": { "name": "qa:keys" } },
      "tuningChangers": ["pedal"],
      "tuner": "mtsOctave1ByteNonRealTime",
      "output": { "midiDeviceId": { "name": "qa:synth" } }
    }
  ]
}
```

### MIDI verification

- A spike on 2026-10-22, with SendMIDI 1.3.1 and ReceiveMIDI 1.4.4 from Homebrew on virtual ports, ran an existing fat
  JAR of the app. MTS 1-byte and 2-byte messages, and SysEx of 4,096 and 70,000 bytes, arrived complete. The app's
  startup reset and first tuning, and the tunings selected by pedal CCs, matched the cents in its log. Restarting the
  output tool made the app reopen the device and send the current tuning again.
- Spikes on 2026-10-24, with the same versions and without the app, carried a note on, a CC and a SysEx byte for byte,
  and checked the tools as long-lived processes. A `sendmidi virt <port> --` sent each line as it arrived on its
  standard input, and a relative timestamp of `+00.500` delayed the next command by 499 ms. A
  `receivemidi virt <port> ts nn`, appending to a file, wrote each message to it as the message arrived.

The `.pkg` versions weren't part of either. These versions have quirks:

- In ReceiveMIDI's `hex` mode, a SysEx line has no newline, so it merges with the next message, and the last one is lost
  when the tool exits. The server uses the default mode.
- ReceiveMIDI's `rpn` filter showed RPN 0 set to 2, with the selectors sent LSB first as the tuners send them, as
  `rpn 0 0`.
- A note on with velocity 0 shows as a `note-off`, which tuning checks don't need to tell apart.
- In SendMIDI, a `dec` after `hex raw` on the same line makes `raw` read its bytes as decimal:
  `hex raw 90 3C 00 dec` sent `5A 03`.

## QA MCP server

- **App and MIDI:** start the app, headless or on the desktop platform, with its ports, and stop it; send MIDI; unplug
  and replug a device.
- **GUI:** read the scene graph; take a snapshot of a window or a node, with the open popups pasted in, returned inline;
  click; press keys; type; wait until the Application Thread is idle; read and write the app's clipboard; and, on the
  desktop platform, take an OS screenshot of the app's windows.
- **Files, not tools:** the app's log, and the MIDI going in and out, land in the run directory, where the agent reads
  them with its own tools.
- **Boundary:** its GUI tools reach only the app it started, with the driver, the JAR and the JVM options it fixes. No
  tool attaches to another process or runs a command line. Tools may take paths, such as the composition the agent
  wrote.
- **Lifecycle:** defined inline in the agent definition, it starts with the agent, and stops the app and the MIDI tools
  when the agent finishes. The builder builds its JAR with the app's.

## MCP library

A new module group, `mcp`, holds our own MCP code on play-json, starting with `mcp-core`. It covers only what the QA
server needs:

- JSON-RPC 2.0 messages and errors;
- `initialize`, with protocol version negotiation, and `notifications/initialized`;
- `ping`;
- `tools/list`, with each tool's input JSON Schema, and `tools/call`, returning text and image content or a tool error;
- the stdio transport.

It depends on play-json, not on Play Framework, since stdio has no HTTP. A transport on Play comes later, in a module
of its own, with the app's MCP server for users. The MCP specification gets two or three revisions a year; tests that
drive our server with an official client, the Java SDK's or chimp's as a test-only dependency, catch the drift.

No MCP library for Play exists. The JVM ones:

| Library | Basis | Transports | Status |
| ------- | ----- | ---------- | ------ |
| [chimp](https://github.com/softwaremill/chimp) (SoftwareMill) | Scala 3, Tapir, sttp, circe | stdio, Streamable HTTP | Apache 2.0, 103 stars, active, 0.6.0; a Pekko module and a client |
| [fast-mcp-scala](https://github.com/TJC-LP/fast-mcp-scala) | Scala 3, ZIO, the Java SDK | stdio, HTTP | MIT, 24 stars |
| [Java SDK](https://github.com/modelcontextprotocol/java-sdk) (official) | Java, Reactor, Jackson by default | stdio, Streamable HTTP | MIT, 3.7k stars, maintained with Spring AI |

## In-process driver

Researched on 2026-10-22, after the user ruled out giving an agent control of the whole desktop. The driver confines
the agent's GUI access to one microtonalist process that it can't change.

- **Driver.** A Java agent JAR, added with `-javaagent` to the QA command line only, so the production JAR holds no
  test code. On the JavaFX Application Thread, it reads the scene graph of every window (`Window.getWindows()`), takes
  snapshots with `Scene.snapshot` and `Node.snapshot`, and injects input.
- **Platform.** Headless by default: JavaFX 26, which #99 targets, ships a headless platform,
  `-Dglass.platform=Headless`. The app shows no window, and the JavaFX `Robot` injects events into the JVM, not the OS.
  Snapshots render the scene graph and never read the screen, so no macOS grant is needed. The desktop platform is the
  alternative when OS screenshots are wanted; see [OS screenshots](#os-screenshots).

What the agent can still reach through the GUI: whatever microtonalist's GUI does, including the MIDI devices it lets
the user pick.

### Verification

A spike on 2026-10-22 with OpenJFX 26.0.2 (`mac-aarch64`) and Temurin 25.0.4.1 ran a 480×640 stand-in: a `ListView`
of tunings, a button, digit keys, a `Shortcut+N` accelerator, a context menu, a `Canvas` and an `Alert`.

| Check | Headless | macOS desktop |
| ----- | -------- | ------------- |
| `Scene.snapshot`, `Node.snapshot` | Yes, 23–102 ms | Yes, 14 ms, with no Screen Recording grant |
| Rendered: selection, focus, ellipsis, `Canvas` | Yes | Yes |
| `Robot` click on a row and a button, digit key, accelerator | Yes, in-process | Not run: OS-level there, so it needs the grants |
| `Event.fireEvent` click, key, accelerator; `Button.fire()` | Yes | Yes |
| Context menu, dialog | Windows of their own, each snapshotted; `showAndWait` works | Dialog works |
| `Robot.getScreenCapture` | Blank unless `-Dprism.order=sw`; then shows the stage, not the open context menu | Not run |
| Screen | 1000×1000 at scale 1, fixed | 1512×982 at scale 2 |

### Limitations

- A snapshot covers one window. Menus, tooltips and dialogs are windows of their own, which the driver lists and
  snapshots one by one.
- No title bar, system menu bar or native dialog. Headless `FileChooser` and `DirectoryChooser` throw
  `UnsupportedOperationException`, and on the desktop they are native, outside the scene graph. The driver could
  replace their `show…Dialog` methods through the Java agent's `Instrumentation`, returning a path the agent chose,
  which covers the app's side of opening and saving files; unspiked. Until then, opening and saving files is a `human`
  check.
- Snapshots are at scale 1, even on Retina, unless the driver passes a scale transform.
- Headless layout is close to the desktop's, not identical: list rows were 23 px high against 24 px, and a quarter of
  the pixels differed, though text was truncated at the same place. How it looks on a real screen stays a `human` check.
- A snapshot catches a running animation midway, so the driver waits for the Application Thread to drain first.
- The headless platform is experimental, selected by an undocumented property (`Headless` works; a blog writes
  `headless`), and its screen size isn't configurable yet. Pin the OpenJFX version.
- The headless clipboard is in-memory, never the macOS pasteboard; see [Clipboard](#clipboard).
- Drag and drop can't be driven on the desktop platform (see [OS screenshots](#os-screenshots)), and is untested on the
  headless one.
- Untested: the system menu bar on the headless platform, and the real app (Pekko, CoreMIDI4J) on it.

### Clipboard

For Copy and Paste, once the GUI has them:

- The driver's clipboard tool reads and writes the JavaFX clipboard on either platform: in memory when headless, the
  macOS pasteboard on the desktop.
- On the desktop platform, `pbcopy` and `pbpaste` check the exchange with other apps.
- A desktop run overwrites the user's clipboard, and the agent reads the pasteboard only after the app has written it.

### OS screenshots

The user wants OS screenshots on demand, for context menus, dialogs and the look. They need only the Screen Recording
grant, which reads the screen but controls nothing, unlike Accessibility and Event Synthesizing.

- The app must run on the desktop platform, with a window on screen: a headless app has nothing to capture.
- Input still goes through the server and the driver, which fires JavaFX events with `Event.fireEvent` on the
  Application Thread, since the `Robot` is OS-level there. macOS never sees them, so the window needn't be in front and
  the user's mouse and keyboard stay untouched. Unlike the headless `Robot`, which takes Glass's input path, JavaFX
  doesn't pick the node under the pointer, so the driver does; and drag and drop, which needs the platform, can't be
  driven.
- A server tool captures only the app's windows: `screencapture -x -o -l <window-id>`, a macOS built-in, with the IDs of
  the windows that the app's PID owns. Untested; Peekaboo's capture of a covered Swing window came out clean.
- The grant still lets any process started from the terminal capture the whole screen.
- Menus and dialogs don't need it: on either platform, the driver can paste each popup's snapshot over the stage's at
  its screen offset. A snapshot at scale 2 on the desktop platform comes from the same renderer as the window, so it
  should match its content (unverified). Only the OS shows the title bar, the system menu bar and native file dialogs.

### Existing tools

None fits as it is:

- [swing-mcp](https://github.com/crosstech-solutions-bv/swing-mcp) (MIT, active since July 2026) has the same
  architecture: a Java agent and a Spring Boot MCP server, synthetic JavaFX events and in-process screenshots. But
  `launch_app` runs any command line and environment, and `attach_to_app` drives the UI of any JVM by PID, IntelliJ's
  included, which breaks the QA server's boundary. Fencing them needs subagent hooks, which Claude Code skips until the
  folder is trusted.
- [fxgraph-mcp-server](https://github.com/k7t3/fxgraph-mcp-server) attaches to any JVM by PID and sets any property.
- [Marathon](https://github.com/jalian-systems/marathonv5) was last pushed in 2022.

## Permissions

The agent has Bash, Read and Write, besides the QA server's tools. The danger of Peekaboo mode was the macOS grants,
not Bash: Accessibility and Event Synthesizing let any process act outside Claude Code's permission system (see
[Security](#security)), and auto mode's classifier, which judges a command by its text, can't tell what a click by
element ID will hit. Without the grants, Bash, Read and Write stay inside the permission system, as in the main
session, and the agent reads less untrusted content than the main session does: the test plan, the fixtures and the
app's output. The QA server keeps the boundary that matters: its GUI tools reach only the app it started.

The agent needs them to:

- write input files, such as a composition for a scenario;
- read output files, once the GUI edits scales, compositions and rigs;
- read and write the macOS pasteboard, once the GUI has Copy and Paste;
- read the log and the MIDI output, and decode the output with the skill's script.

Rules in its prompt:

- Change no code, and write only in the run directory. Auto mode's classifier lets writes inside the repo through, as
  ordinary coding, so this rule rests on the prompt; the builder compares `git status --porcelain` and `git diff`
  before and after the run.
- Read nothing outside the repo and the temp directories. In the repo, read no source, diff or git history: the input
  is the test plan so that the agent tests what a user sees, not what the builder meant.
- Never kill a process by name: `pkill java` takes down the dev stack's BSP server and Metals, the other clone's
  included. Stop the app through the server.
- Send MIDI through the server only, never with `sendmidi dev`: a stray MTS message retunes a real instrument.
- If the server's tools are missing, stop and report, rather than start the app another way. An inline server loads
  only once the folder is trusted.

## Test plan

- **File:** `issues/<NNNNN>-<slug>/<date>-test-plan.md`, next to the design and the implementation plan, dated and tied
  to a commit like the other issue documents.
- **When:** after the design is approved and before the implementation plan, from the design's acceptance criteria. The
  implementation plan's last task runs QA against it.
- **Audience:** the QA agent and human testers. Steps are in user terms, and expected results are observable by both:
  what the window shows, what the instrument receives in cents per pitch class, and a log without errors. Tool commands
  stay out of the plan, in the skill.
- **Contents:**
  - the scope, and what is out of scope;
  - the fixtures: the skill's shared ones, or feature-specific ones next to the plan;
  - scenarios in Given/When/Then, each with an ID and a priority, `must` or `should`;
  - checks that only a human can do, such as "sounds in tune on the piano", tagged `human`, which the agent reports as
    not run;
  - a time-boxed list of areas to explore beyond the scenarios.
- **Template:** `.claude/skills/gui-qa/test-plan-template.md`.

Example scenario:

```markdown
### TP-03 The pedal changes the selected tuning (must)

Given the QA composition is open with "C Just Major" selected
When the input receives Sostenuto pressed, then released
Then the tuning list selects "C Romanian Minor"
And the output receives one MTS message: C 0, D +4, E♭ +16, F♯ −17, G +2, A +6, B♭ −4 cents, other keys 0
And the log has no ERROR
```

## QA report

- **File:** `issues/<NNNNN>-<slug>/<date>-qa-report.md`, one per run, with the commit tested, whether the working tree
  was dirty, and the platform, headless or desktop. The test plan stays unchanged between runs, and the PR links the
  latest report.
- **Contents:** a result per scenario (`pass`, `fail`, `flaky` or `not run`), each bug with minimal steps to reproduce
  it, what the exploration found, and the relevant MIDI and log lines quoted as evidence. Screenshots stay in the
  scratchpad.

## QA agent

- **Definition:** `.claude/agents/gui-qa.md`, on `opus` at `high` effort. `tools:` lists Bash, Read, Write and the QA
  server's tools, and `mcpServers:` defines the server inline, so the main session doesn't get them.
- **Input:** the test plan's text. Not the diff, so that it tests what a user would see instead of confirming what the
  builder meant.
- **Procedure,** in the `gui-qa` skill:
  1. The builder builds the JARs and dispatches the agent. If the plan needs OS screenshots, it asks the user first,
     since a window then shows on screen.
  2. Check that the QA server's tools are there.
  3. Start the app through the server.
  4. Run each scenario: act, then check the GUI state, the MIDI output and the log. Retry a failure once, to tell a
     flake from a bug.
  5. Explore the listed areas.
  6. Stop the app; the server also stops it when the agent finishes.
  7. Return the report, which the builder writes.
- **Limits:** the rules in [Permissions](#permissions).
- **Triage:** the builder fixes the bugs. A bug in view-model logic gets a failing test first; a wiring bug in a view
  gets fixed, and its scenario rerun.

## Workflow changes

- **`CLAUDE.md`, coding workflow:** "For a change that affects the GUI, write a test plan; see the `gui-qa` skill."
- **`CLAUDE.md`, final checks:** "Manual E2E, for GUI changes only: dispatch the `gui-qa` agent with the test plan."
- **Human testers:** `docs/development/gui-qa.md` covers the one-time setup, the MIDI tools included, and how to run a
  test plan by hand.

## Deliverables

- `.claude/skills/gui-qa/`: `SKILL.md`, with the procedure, the agent's rules and the MIDI layouts; the test plan
  template; the shared fixtures (composition, rig file, config); and the decoder script, with its tests.
- `.claude/agents/gui-qa.md`.
- The `mcp` module group, with `mcp-core`.
- The QA MCP server, with the MIDI ports, and the driver.
- The `CLAUDE.md` changes above.
- `docs/development/gui-qa.md`.

## Risks

- The headless platform is experimental, so an OpenJFX upgrade may break the driver.
- The MCP specification moves, and `mcp-core` must follow the parts we use.
- Only stand-ins were driven in process. The real app's JavaFX GUI doesn't exist yet: #99 builds it.
- SendMIDI and ReceiveMIDI have quirks in the versions tested, and the `.pkg` versions are untested; see
  [MIDI verification](#midi-verification). The skill records the versions known to work.
- `sendmidi` and `receivemidi` are GPLv3. They run as external programs, neither linked nor shipped, so they don't
  affect the project's license.

## Open questions

- Which module group holds the QA MCP server and the driver, e.g. a `qa` group with `qa-mcp` and `qa-driver`.
- How the server finds the IDs of the app's windows for OS screenshots: a short Python script on `pyobjc`, or JXA
  (`osascript -l JavaScript`) calling `CGWindowListCopyWindowInfo`, which needs nothing installed (untested).
- How the agent's rig names the run's ports; see the proposal in [Ports of a rig](#ports-of-a-rig).
- Whether the `accessibleText` rule still joins the view conventions of #99: it served Peekaboo mode, and now serves
  only screen-reader users.
- Spikes still to run: replacing `FileChooser` through `Instrumentation`; drag and drop on the headless platform, if
  #99's GUI uses it.

## Alternatives considered

- **Custom Swift tools** for MIDI and accessibility: build setup and maintenance in a language the repo doesn't use.
- **IAC buses** as a second MIDI backend, opened through `sc-midi-javamidi`: no tool to install, but no unplugging, so
  hot-plug scenarios become `human` checks; no parallel runs; any app listening to the buses, such as a DAW, hears the
  run's MIDI; and each developer sets up two buses in Audio MIDI Setup.
- **Virtual ports created by the QA server** through the FFM API (`MIDISourceCreate`, `MIDIDestinationCreate` with an
  upcall, `MIDIReceived`, `MIDIEndpointDispose`): a spike on 2026-10-22, in 150 lines of Java with CoreMIDI4J on the
  other end, sent and received a CC, a 4,096-byte SysEx and an MTS message, and unplugged a port by disposing it. It
  needs no external tool, but it is native code to own: a wrong layout crashes the JVM instead of throwing, and it sits
  on CoreMIDI's packet-list API, deprecated since macOS 11, whose successor takes Objective-C blocks as callbacks, which
  FFM can't create.
- **The agent running `sendmidi` and `receivemidi` itself,** through Bash: each port would end with the tool call that
  opened it, unless the agent started background processes, kept their names unique and cleaned them up; and `dev`
  names any port, the user's instruments included.
- **Raw hex bytes for `send_midi`,** each message sent as a `raw` command: stateless, but the agent encodes status bytes
  and controller numbers in hex, where it slips, and the report quotes bytes instead of commands.
- **ReceiveMIDI's `dump`,** raw bytes: the server would split them into messages, and the agent would decode every
  channel message and combine every pitch bend's 14 bits.
- **Conversion tools in the QA server,** `midi_14bit` and `midi_cents`, for an agent without Bash: the agent still
  sliced the bytes itself, and paid a tool call per conversion.
- **A JSON object per message type:** a schema and a play-json format for each, and the bytes the agent checks hidden.
- **Decoding the output in the server** into cents per pitch class: see [Reading the output](#reading-the-output).
- **Other tools for virtual ports:** [midicat](https://gitlab.com/gomidi/tools/-/tree/main/midicat) opens existing
  ports only, and has no macOS binary; [mot](https://github.com/JorenSix/mot) creates virtual ports only in its OSC
  bridges, and installs with `cargo` only; RtMidi's wrappers, such as `python-rtmidi`, are libraries, not tools.
- **A QA server in Python,** like `scoverage-inspector`: the official Python SDK and no library to write. But the server
  isn't thin scripting, and Python would duplicate the driver's protocol in a second language, outside the sbt build's
  TDD and coverage, and leave the library for the app's MCP server still to write.
- **A debug port in the app** that dumps the scene graph and fires events: more deterministic, but a test backdoor in
  production code. The driver does the same from a Java agent, loaded only by QA.
- **TestFX smoke tests:** #99 keeps them as a fallback if wiring bugs slip through.

### Peekaboo mode

Driving the GUI through macOS with [Peekaboo](https://github.com/openclaw/Peekaboo), from Homebrew, and reading the
state from the accessibility tree. Dropped on 2026-10-24: its grants reach far beyond the app (see
[Security](#security)), it takes over the desktop during a run, and what only it can do lies outside the core loop of
bindings, wiring, layout, and GUI to MIDI to GUI:

| Case | Without Peekaboo |
| ---- | ---------------- |
| Native Open and Save dialogs | The driver replaces `FileChooser` (see [Limitations](#limitations)); the panel itself is Apple's. |
| System menu bar | The driver fires each `MenuItem` and accelerator of the `MenuBar`. How macOS draws the menus is a `human` check: an OS screenshot can't open a menu. |
| Real macOS input: activation, focus, dead keys | The headless `Robot` takes Glass's input path, which catches most focus bugs. The rest is a `human` check. |
| Drag and drop | Untested headless. Dragging from Finder is a `human` check. |
| The accessibility tree that VoiceOver reads | The driver reads only the declared `accessibleText`. It matters only if screen readers become a goal. |
| A packaged `.app`: Dock, file associations | `human` checks. |

Now that the agent has Bash, bringing it back takes a section of the skill and the grants, not a second agent.

#### Commands

- Find the window: `peekaboo window list --pid <pid> --json`, which gives its `window_id`.
- Read the state: `peekaboo see --pid <pid> --window-id <id> --no-screenshot --tree --json`. Its
  `.data.ui_elements[]` have `id`, `ax_role`, `label`, `is_selected` and `bounds`, and `.data.snapshot_id` identifies
  the snapshot. The plain `--tree` text drops the role and the selection.
- Act: `peekaboo click --on <element-id> --snapshot <snapshot-id> --pid <pid> --window-id <id> --foreground`, and
  `peekaboo press <key> --pid <pid> --window-id <id> --foreground`.
- Take a screenshot: `peekaboo see --pid <pid> --window-id <id> --path shot.png`. It is clean even when another window
  covers the app's.

A `jq` filter turns the state of a list into one line, e.g. `*C Just Major | C Romanian Minor | C Septimal`:

```jq
[.data.ui_elements[] | select(.ax_role == "AXRow" and .label != "table row")]
  | unique_by(.label) | sort_by(.bounds.y)
  | map((if .is_selected then "*" else "" end) + .label) | join(" | ")
```

Rules:

- Target elements by ID from a fresh snapshot: Peekaboo can't find JavaFX buttons by label.
- Click and press keys with `--foreground`, since JavaFX rows and all key presses need it. A run therefore takes over
  the desktop, so the builder asks the user before dispatching it.
- Read the state from the accessibility tree, and use screenshots for layout only. A control that draws itself, such
  as a `Canvas`, exposes nothing to the tree; check it by screenshot.
- Every JavaFX control without visible text sets `accessibleText`, which names it in the tree.

Once per machine:

- `brew install openclaw/tap/peekaboo`, pinned with `brew pin peekaboo`, since it changes fast, at 4.9.x with frequent
  protocol changes.
- Grant the terminal app that runs Claude Code Accessibility, Screen Recording and Event Synthesizing (for key presses),
  in System Settings → Privacy & Security. `peekaboo permissions status` checks them.

#### Verification of Peekaboo mode

A spike on 2026-10-22, on macOS 26.6 with Peekaboo 4.9.6, ran an existing fat JAR of the app and a minimal JavaFX 25
app with a `ListView` and a button.

| Check | Swing (`TuningListFrame`) | JavaFX |
| ----- | ------------------------- | ------ |
| Rows and selection in the tree | Yes, as `AXList` and `AXRow`; each row listed several times | Yes, as `AXTable` and `AXRow`, plus empty rows labelled "table row" |
| Click a row | Works, even in the background | Only with `--foreground` |
| Click a button | No button | By element ID, even in the background; not by label |
| Key presses | Only with the app in front | Only with the app in front |
| A pedal CC updates the selection | Yes | Not tested |
| Screenshot of a covered window | Clean | Not tested |

#### Security

macOS grants Accessibility, Screen Recording and Event Synthesizing to an app, here the terminal (iTerm2), not per
target app. Every process started from it inherits them: every Claude Code session, its subagents, and any script they
run. Peekaboo's `--pid` only aims a command, nothing enforces it, and any program can call the same macOS APIs: the
spike checked the grants with plain `python3`. Such a process can:

- read the text of any app's windows through its accessibility tree, except the values of password fields;
- capture the whole screen;
- click and type into any app, the terminal included, so it could answer Claude Code's permission prompts or type
  commands into another tab, getting around Claude Code's permission rules.

Peekaboo's daemon, started on demand, holds the same grants until it has been idle for 5 minutes. It listens on a socket
in `~/Library/Application Support/Peekaboo/` that any process of the user can reach; whether it authenticates clients
beyond that is unchecked.

The realistic threat is prompt injection: text in a web page, issue, PR comment or dependency that steers a session into
running commands, which auto mode runs without asking. Daily sessions read far more untrusted content than a QA agent.

Ways to reduce the exposure:

| Option | Confines the agent to the app? | Cost |
| ------ | ------------------------------ | ---- |
| Turn the grants off between runs; the skill's permission check stops a run while they're off | No, but the exposure lasts only during runs | Toggling them in System Settings by hand |
| A terminal app used only for QA sessions holds the grants, and the daily one none | No, but only QA sessions are exposed | The session that dispatches the QA agent must run in that terminal |
| A Claude Code hook that rejects Peekaboo commands aimed at another PID, or at other apps and the system (`app`, `agent`, `clipboard`, `dock`, `menubar`, `space`) | No: it prevents mistakes, not attacks | A hook script |
| Run QA in a macOS VM, e.g. with [Tart](https://github.com/cirruslabs/tart), so that the grants exist only inside it | Yes | A macOS image of tens of GB with the JDK and the tools; copying the JAR in; driving it over SSH. JavaFX rendering and MIDI in a VM are untested. |
| The [in-process driver](#in-process-driver) instead | Yes | None per run |

#### Other OS-level drivers

- **axcli:** simpler, with Playwright-style selectors, but young and needs a Rust toolchain.
- **`osascript` with System Events:** nothing to install, but it addresses elements by index and needs the Automation
  permission too.
- **Claude's computer use:** only in the Claude desktop app, and slow and costly per step.
- **Appium's Mac2 driver:** needs Xcode and an Appium server.
