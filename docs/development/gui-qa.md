# GUI QA

How to set up a Mac for QA of the GUI, and how to run a GUI test plan by hand. The `gui-qa` agent runs the same plans
through the QA MCP server, but only on a JavaFX app: until #393 ports the GUI to JavaFX, run them by hand. The
[`gui-qa` skill](../../.claude/skills/gui-qa/SKILL.md) says when a change needs a test plan, how to write one and how
to run the agent.

QA runs on macOS only.

## One-time setup

- **JDK 25**, as for the build (see [`README.md`](README.md#prerequisites)).
- **SendMIDI and ReceiveMIDI**, which open the virtual MIDI ports: install the `.pkg` of
  [SendMIDI](https://github.com/gbevin/SendMIDI/releases) and of
  [ReceiveMIDI](https://github.com/gbevin/ReceiveMIDI/releases), which put the binaries in `/usr/local/bin`. SendMIDI
  1.4.4 and ReceiveMIDI 1.6.2 are known to work. A Homebrew copy, in `/opt/homebrew/bin`, shadows them; uninstall it:

  ```bash
  brew uninstall sendmidi receivemidi
  which -a sendmidi receivemidi   # only /usr/local/bin
  sendmidi --version; receivemidi --version
  ```

  Both are GPLv3: they run as external programs, and the repository neither links nor ships them.
- **Python 3**, for the MIDI decoder.

## Running a test plan by hand

You run the app on the desktop platform, with the ports that the QA server would open opened by hand.

1. **Build** the app's fat JAR, from the repository's root:

   ```bash
   sbtn assembly
   REPO="$PWD"
   JAR="$REPO/app/app/target-bsp/scala-$(cat project/scala-version)/microtonalist-app.jar"
   ```

2. **Make a scratch directory** outside the repository, and copy the plan's fixtures into it, the skill's or the plan's
   own:

   ```bash
   QA="$(mktemp -d)/qa"; mkdir -p "$QA"
   cp .claude/skills/gui-qa/fixtures/qa.mtlist .claude/skills/gui-qa/fixtures/qa.mtlist.tracks "$QA/"
   cd "$QA"
   ```

3. **Name the ports in the rig.** In `qa.mtlist.tracks`, replace each `{ "name": "qa:<device>" }` with
   `{ "name": "CoreMIDI4J - QA <device>", "vendor": "<Unknown Manufacturer>" }`, e.g. `qa:keys` with
   `CoreMIDI4J - QA keys`. The app sees a virtual port under that name and vendor.

4. **Open the ports** before you start the app, each in a terminal of its own in the scratch directory: the input,
   to which you type SendMIDI command lines, and the output, which records what the app sends:

   ```bash
   sendmidi virt "QA keys" --
   ```

   ```bash
   receivemidi virt "QA synth" ts nn | tee -a midi-out-synth.txt
   ```

   Stopping a tool with Ctrl-C and starting it again unplugs and replugs its device; `tee -a` keeps the output
   recorded before. The app notices it about half a second later.

5. **Write `qa.conf`**, which points the app's library at the scratch directory, so that the app neither reads nor
   writes `~/.microtonalist/`:

   ```bash
   printf 'core {\n  libraryBaseUrl = "file://%s/"\n}\n' "$PWD" > qa.conf
   ```

6. **Start the app** in the background, its output going to `app.log`; stop it at the end with `kill %1`:

   ```bash
   java -jar "$JAR" qa.mtlist qa.conf > app.log 2>&1 &
   ```

7. **Run each scenario**: act in the window or type the MIDI the plan describes into `sendmidi`, e.g. `ch 1 cc 66 127`
   then `ch 1 cc 66 0` for the sostenuto pedal, then check the window, the output and the log. Read the output with the
   decoder, which annotates each tuning message with the cents of each pitch class:

   ```bash
   python3 "$REPO/.claude/skills/gui-qa/scripts/decode_midi.py" midi-out-synth.txt
   grep -E 'ERROR|Exception' app.log
   ```

   [`agent-reference.md`](../../.claude/skills/gui-qa/agent-reference.md) has the SendMIDI conventions, the pedal tuning
   changer's defaults, the decoder's options and what each tuner sends.

8. **Write the QA report** as the skill's *Run QA* section says, noting that you ran it by hand.
