# GUI QA agent: research

- **Date:** 2026-10-22
- **Commit:** `89b5f06d`
- **Issue:** #397. Related: #99, #383.
- **Trimmed version:** [`2026-10-22-gui-qa-in-process.md`](2026-10-22-gui-qa-in-process.md), in-process mode only,
  without OS screenshots.

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
   Third-party tools: [Peekaboo](https://github.com/openclaw/Peekaboo), from Homebrew, in Peekaboo mode; optionally,
   `sendmidi` and `receivemidi` ([gbevin](https://github.com/gbevin/SendMIDI)), from their releases' `.pkg`.
4. macOS only, like the rest of the agent setup.
5. Two modes, each with a guide of its own, and the user picks one per run, weighing risk against benefit; see
   [Modes](#modes).
6. A QA MCP server in Scala, which reuses `sc-midi`, `intonation`, `tuning` and the rest, serves both modes.
7. MIDI goes through virtual ports from `sendmidi` and `receivemidi` when they are installed, and otherwise through two
   IAC buses, which each developer sets up once; see [MIDI backends](#midi-backends).
8. Our own MCP library, on play-json, in a new `mcp` module group, covering only the MCP features we need.
9. `sc-midi` learns the SendMIDI text format, for interoperability with those tools, and the QA server's MIDI tools
   speak it; see [MIDI text format](#midi-text-format).

## Modes

| | In-process mode | Peekaboo mode |
| - | --------------- | ------------- |
| Drives the GUI through | A driver inside the app's JVM | Peekaboo, through macOS |
| Reads the state from | The scene graph | The accessibility tree |
| macOS grants | None; Screen Recording only for optional OS screenshots | Accessibility, Screen Recording, Event Synthesizing |
| Agent's tools | The QA server's only | The QA server's, and Bash |
| Can reach beyond the app | No | Yes; see [Security](#security) |
| Window | None (headless), or on screen for OS screenshots | On screen, in front |
| The user's desktop during a run | Free | Taken over |

## Shared setup

Each run builds and isolates what it uses. The builder builds the JARs, and the QA MCP server starts and stops the
rest.

| Piece | How |
| ----- | --- |
| App | The builder runs `sbtn assembly` first, so that the JAR matches the working tree. The server runs `java -jar app/app/target-bsp/scala-<version>/microtonalist-app.jar <composition> <config>` and keeps its output in a log file. `bin/mtlist` isn't used: it runs the `target/` JAR, which only a plain `sbt assembly` builds. |
| Config | A scratch HOCON file passed as the second argument, so `~/.microtonalist/microtonalist.conf` is never used. |
| Composition and rig | A scratch `.mtlist` with inline scales, and its `.mtlist.tracks` next to it, which the server writes for the MIDI backend in use. |
| MIDI | Virtual ports or IAC buses; see [MIDI backends](#midi-backends). |

The skill tells the agent the pedal tuning changer's defaults: CC 67 (soft pedal) selects the previous tuning, CC 66
(sostenuto) the next one.

### MIDI backends

The server picks the backend when it starts, preferring virtual ports, and the report names it. If neither is
available, it stops with a pointer to the setup.

| | Virtual ports | IAC buses |
| - | ------------- | --------- |
| When | `sendmidi` and `receivemidi` are installed | Otherwise, if both buses are online |
| Ports | `<run>-in` and `<run>-out`, unique to the run | `IAC 1` into the app, `IAC 2` out of it |
| The server | Runs `sendmidi virt <run>-in --` and `receivemidi virt <run>-out nn`, writing and reading the text format through their standard streams | Opens the buses through `sc-midi-javamidi`, like the app |
| Vendor in the rig file | `<Unknown Manufacturer>`: their JUCE library sets none | `Apple Inc.` |
| Unplugging a device | Restart a tool | Not possible, so hot-plug scenarios are `human` checks |
| Parallel runs, e.g. in the `microtonalist2` clone | Yes | No |
| Other apps hear the run's MIDI | No | Yes, any listening to the buses, such as a DAW |
| One-time setup | Install the `.pkg` of each tool's latest GitHub release, not the Homebrew formula, which builds with Xcode | In Audio MIDI Setup: open the MIDI Studio, double-click IAC Driver, check "Device is online", and name two buses `IAC 1` and `IAC 2` |
| Verified with the app | Yes, in the spike of [Peekaboo mode](#peekaboo-mode) | Not yet; CoreMIDI4J listed both buses on this machine |

The `.pkg` of SendMIDI 1.4.4 and ReceiveMIDI 1.6.2 are signed with a Developer ID, notarized, and install only the
binary in `/usr/local/bin`, with no install scripts. A Homebrew copy in `/opt/homebrew/bin` would shadow it.

The rig file names the ports as CoreMIDI4J reports them, `CoreMIDI4J - <port>`. An IAC bus is both an input and an
output under the same name, and `sc-midi-javamidi` keeps the two directions apart; two buses keep the app's output
from looping back into its input. With the IAC buses:

```json
{
  "tracks": [
    {
      "id": "qa",
      "name": "QA",
      "input": { "midiDeviceId": { "name": "CoreMIDI4J - IAC 1", "vendor": "Apple Inc." } },
      "tuningChangers": ["pedal"],
      "tuner": "mtsOctave1ByteNonRealTime",
      "output": { "midiDeviceId": { "name": "CoreMIDI4J - IAC 2", "vendor": "Apple Inc." } }
    }
  ]
}
```

## QA MCP server

- **Tools of both modes:** start and stop the app; send MIDI, written in the [text format](#midi-text-format), e.g.
  `ch 1 cc 66 127`; read the MIDI output since a mark, in the same format, with MTS decoded into cents per pitch class;
  unplug and replug the app's input or output, with virtual ports only; read the log.
- **Tools of in-process mode:** read the scene graph; take a snapshot of a window or a node, with the open popups pasted
  in, returned inline; click; press keys; type; wait until the Application Thread is idle; and, on the desktop
  platform, take an OS screenshot of the app's windows.
- **Confinement:** the server fixes the JAR, the fixtures and the JVM options. No tool takes a path, a command line, a
  PID or code to evaluate. The MIDI it sends goes through `sc-midi`'s parser, which accepts message commands only, so
  it can't name a port or a file.
- **MIDI:** messages are `sc-midi` types, whichever the backend: the server parses the agent's text into messages and
  formats them back for `sendmidi`, and parses `receivemidi`'s output. MTS is decoded with the project's code, under
  TDD.
- **Lifecycle:** defined inline in each agent definition, it starts with the agent and stops the app when the agent
  finishes. The builder builds its JAR with the app's.

## MIDI text format

Proposed design. `sc-midi` reads and writes the text format of SendMIDI, which ReceiveMIDI writes and RouteMIDI also
speaks, so that its messages interoperate with those tools beyond QA.

- **Writing:** one self-contained line per message, in the long command names and note numbers that `receivemidi nn`
  prints and `sendmidi` reads back, e.g. `channel 1 control-change 66 127`, or `system-exclusive hex 7E 7F … dec` for
  SysEx. Channels are 1-based in the text and 0-based in `sc-midi`.
- **Reading:** stateful, like the tools: `ch`, `hex`, `dec` and `omc` carry over to the commands and lines that follow.
  It accepts the short and long command names, note names and numbers, the `M` and `H` suffixes, and `#` comments.
  `cc14`, `rpn` and `nrpn` each yield several messages, so reading returns a sequence.
- **Out of scope, at first:** commands that name a port or a file (`dev`, `virt`, `list`, `pass`, `file`, `syf`),
  macros (`panic`, `clock`, `mpe`, `mpp`, `mpetest`) and timestamps. Reading rejects them.
- **Where:** a codec object in `sc-midi`'s `message` package, with a function that writes a message and one that reads
  text. MIDI 2.0 and meta messages have no counterpart in the format.
- **License:** written from the tools' documentation, without their GPLv3 code, so `sc-midi`'s license is unaffected.

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

## In-process mode

Researched on 2026-10-22, after the user ruled out giving an agent control of the whole desktop. In-process mode
confines the agent to one microtonalist process that it can't change.

- **Driver.** A Java agent JAR, added with `-javaagent` to the QA command line only, so the production JAR holds no
  test code. On the JavaFX Application Thread, it reads the scene graph of every window (`Window.getWindows()`), takes
  snapshots with `Scene.snapshot` and `Node.snapshot`, and injects input.
- **Platform.** Headless by default: JavaFX 26, which #99 targets, ships a headless platform,
  `-Dglass.platform=Headless`. The app shows no window, and the JavaFX `Robot` injects events into the JVM, not the OS.
  Snapshots render the scene graph and never read the screen, so no macOS grant is needed. The desktop platform is the
  alternative when OS screenshots are wanted; see [OS screenshots](#os-screenshots).
- **Agent definition.** `tools:` lists only the QA server's tools, and `mcpServers:` defines the server inline, so the
  main session doesn't get them. An inline server loads only once the folder is trusted; until then the agent has no
  tools at all, so it fails closed. Peekaboo mode needs Bash, hence a second definition: one agent holding both tool
  sets would lose the confinement.
- **Permissions.** The user allows Read and Write if needed, but neither is: the builder passes the test plan in the
  prompt and writes the report the agent returns. Write would let the agent change code, and restricting its path takes
  a hook, which Claude Code skips in an untrusted folder. Read would let a hijacked agent copy a secret into the report,
  which gets committed.

What the agent can still reach: whatever microtonalist's GUI does, including the MIDI devices it lets the user pick.

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
  `UnsupportedOperationException`, and on the desktop they are native, outside the scene graph. Opening and saving
  files stays a `human` check.
- Snapshots are at scale 1, even on Retina, unless the driver passes a scale transform.
- Headless layout is close to the desktop's, not identical: list rows were 23 px high against 24 px, and a quarter of
  the pixels differed, though text was truncated at the same place. How it looks on a real screen stays a `human` check.
- A snapshot catches a running animation midway, so the driver waits for the Application Thread to drain first.
- The headless platform is experimental, selected by an undocumented property (`Headless` works; a blog writes
  `headless`), and its screen size isn't configurable yet. Pin the OpenJFX version.
- Its clipboard is in-memory, never the macOS pasteboard.
- Untested: the system menu bar on the headless platform, and the real app (Pekko, CoreMIDI4J) on it.

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
  `launch_app` runs any command line and environment, `attach_to_app` drives the UI of any JVM by PID, IntelliJ's
  included, and its clipboard tools can reach the macOS pasteboard. Fencing them needs subagent hooks, which Claude Code
  skips until the folder is trusted, so a fresh clone fails open.
- [fxgraph-mcp-server](https://github.com/k7t3/fxgraph-mcp-server) attaches to any JVM by PID and sets any property.
- [Marathon](https://github.com/jalian-systems/marathonv5) was last pushed in 2022.

## Peekaboo mode

The Peekaboo commands:

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
- Every JavaFX control without visible text sets `accessibleText`, which names it in the tree. This rule joins the view
  conventions of #99; it also serves screen-reader users.

Once per machine:

- `brew install openclaw/tap/peekaboo`
- Grant the terminal app that runs Claude Code Accessibility, Screen Recording and Event Synthesizing (for key presses),
  in System Settings → Privacy & Security. `peekaboo permissions status` checks them; the skill runs it first and stops
  with a clear message if one is missing. The grants reach far beyond the app; see [Security](#security).

### Verification

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

The same spike checked the app's MIDI with SendMIDI 1.3.1 and ReceiveMIDI 1.4.4, on virtual ports. MTS 1-byte and
2-byte messages, and SysEx of 4,096 and 70,000 bytes, arrived complete. The app's startup reset and first tuning, and
the tunings selected by pedal CCs, matched the cents in its log. Restarting the output tool made the app reopen the
device and send the current tuning again.

### Security

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
| [In-process mode](#in-process-mode) instead | Yes | None per run |

Keep the grants off outside Peekaboo runs.

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
  was dirty, and the mode. The test plan stays unchanged between runs, and the PR links the latest report.
- **Contents:** a result per scenario (`pass`, `fail`, `flaky` or `not run`), each bug with minimal steps to reproduce
  it, what the exploration found, and the relevant MIDI and log lines quoted as evidence. Screenshots stay in the
  scratchpad.

## QA agent

- **Definitions:** `.claude/agents/gui-qa.md` for in-process mode, with the QA server's tools only, and
  `.claude/agents/gui-qa-peekaboo.md`, which adds Bash. Both on `opus` at `high` effort.
- **Input:** the test plan's text. Not the diff, so that it tests what a user would see instead of confirming what the
  builder meant.
- **Procedure,** in the `gui-qa` skill:
  1. The builder asks the user for the mode, builds the JARs and dispatches the mode's agent.
  2. Check the tools and, in Peekaboo mode, the permissions.
  3. Start the app through the server.
  4. Run each scenario: act, then check the GUI state, the MIDI output and the log. Retry a failure once, to tell a
     flake from a bug.
  5. Explore the listed areas.
  6. Stop the app; the server also stops it when the agent finishes.
  7. Return the report, which the builder writes.
- **Limits:** it changes no code. In in-process mode it can't: no tool of its writes a file or runs a command.
- **Triage:** the builder fixes the bugs. A bug in view-model logic gets a failing test first; a wiring bug in a view
  gets fixed, and its scenario rerun.

## Workflow changes

- **`CLAUDE.md`, coding workflow:** "For a change that affects the GUI, write a test plan; see the `gui-qa` skill."
- **`CLAUDE.md`, final checks:** "Manual E2E, for GUI changes only: ask the user for a mode, then dispatch that mode's
  `gui-qa` agent with the test plan."
- **Human testers:** `docs/development/gui-qa.md` covers the one-time setup, either MIDI backend's included, and how to
  run a test plan by hand.

## Deliverables

- `.claude/skills/gui-qa/`: `SKILL.md`, with the shared procedure and how to pick a mode; one guide per mode,
  `in-process.md` and `peekaboo.md`; the test plan template; the shared fixtures (composition, rig file, config); and
  Peekaboo's state filter.
- `.claude/agents/gui-qa.md` and `.claude/agents/gui-qa-peekaboo.md`.
- The `mcp` module group, with `mcp-core`.
- The text format in `sc-midi`.
- The QA MCP server, with both MIDI backends, and the driver.
- The `CLAUDE.md` changes above.
- `docs/development/gui-qa.md`.
- The `accessibleText` rule, in the view conventions of #99.

## Risks

- Peekaboo changes fast, at 4.9.x with frequent protocol changes. Pin a version known to work with `brew pin peekaboo`,
  and record it in the skill.
- The headless platform is experimental, so an OpenJFX upgrade may break in-process mode.
- The MCP specification moves, and `mcp-core` must follow the parts we use.
- Only stand-ins were driven in process. The real app's JavaFX GUI doesn't exist yet: #99 builds it.
- `sendmidi` and `receivemidi` are GPLv3. They run as external programs, neither linked nor shipped, so they don't
  affect the project's license.

## Open questions

- Which module group holds the QA MCP server and the driver, e.g. a `qa` group with `qa-mcp` and `qa-driver`.
- How the server finds the IDs of the app's windows for OS screenshots: a short Python script on `pyobjc`, or
  `peekaboo window list --pid`.
- Whether the `toString` of the MIDI 1.0 messages writes the text format. Logs and test failures would read like
  `receivemidi` output and paste into `sendmidi`, but would show 1-based channels for 0-based fields, and drop the class
  name.

## Alternatives considered

- **Custom Swift tools** for MIDI and accessibility: build setup and maintenance in a language the repo doesn't use.
- **Virtual ports created by the QA server** through the FFM API (`MIDISourceCreate`, `MIDIDestinationCreate` with an
  upcall, `MIDIReceived`, `MIDIEndpointDispose`): a spike on 2026-10-22, in 150 lines of Java with CoreMIDI4J on the
  other end, sent and received a CC, a 4,096-byte SysEx and an MTS message, and unplugged a port by disposing it. It
  needs no external tool, but it is native code to own: a wrong layout crashes the JVM instead of throwing, and it sits
  on CoreMIDI's packet-list API, deprecated since macOS 11, whose successor takes Objective-C blocks as callbacks, which
  FFM can't create.
- **Other tools for virtual ports:** [midicat](https://gitlab.com/gomidi/tools/-/tree/main/midicat) opens existing
  ports only, and has no macOS binary; [mot](https://github.com/JorenSix/mot) creates virtual ports only in its OSC
  bridges, and installs with `cargo` only; RtMidi's wrappers, such as `python-rtmidi`, are libraries, not tools.
- **A QA server in Python,** like `scoverage-inspector`: the official Python SDK and no library to write. But the server
  isn't thin scripting, and Python would duplicate the driver's protocol and the MTS decoding in a second language,
  outside the sbt build's TDD and coverage, and leave the library for the app's MCP server still to write.
- **axcli:** simpler, with Playwright-style selectors, but young and needs a Rust toolchain.
- **`osascript` with System Events:** nothing to install, but it addresses elements by index and needs the Automation
  permission too.
- **Claude's computer use:** only in the Claude desktop app, and slow and costly per step.
- **A debug port in the app** that dumps the scene graph and fires events: more deterministic, but a test backdoor in
  production code. The driver does the same from a Java agent, loaded only by QA.
- **Appium's Mac2 driver:** needs Xcode and an Appium server.
- **TestFX smoke tests:** #99 keeps them as a fallback if wiring bugs slip through.
