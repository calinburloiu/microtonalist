# Grouping Folders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-10-16
- **Based on commit:** `c0f0ba82` (`doc/modularization-proposal`)
- **Issue:** #367, step 1 of #366

**Goal:** Move each of the 14 modules into a grouping folder without changing its code, and update everything that
names a module's path.

**Architecture:** One commit moves the modules with `git mv` and points `build.sbt` at their new folders. Each module's
leaf folder keeps its name, so no sbt project ID changes. Three red/green commits then teach the bundled tools the new
layout (the architecture-doc size script, the scoverage inspector, the launchers), and a last commit fixes the docs.

**Tech Stack:** sbt 1, Scala 3, Python 3 `unittest` (the bundled scripts), Bash (the launchers), Markdown.

**Spec:** [`2026-10-16-design.md`](2026-10-16-design.md): *Module conventions* and step *1. Grouping folders*.

## Global Constraints

- The layout after this step (the design's final layout, without the modules that later steps add):

  ```
  app/     app/  cli/  config/  experiments/
  base/    businessync/  common/  common-test-utils/
  format/  format/
  midi/    sc-midi/
  rig/     rig/
  theory/  composition/  intonation/
  tuner/   tuner/
  ui/      ui/
  ```

- A grouping folder holds the modules of one area and nothing else: no `src`, no `README.md`, `CLAUDE.md` or `docs/`.
  Only `root` stays at the repository root.
- A module's leaf folder name is its ID: no `.withId(…)` value changes.
- `lazy val <camelCaseId>Module = (project in file("<group>/<id>")).withId("<id>")`.
- The modules move with `git mv`, without changing their code. The only source edit is a path in `MpeTuner`'s
  ScalaDoc (Task 6).
- No artifact name (`name := …`) changes: `intonation` and `sc-midi` keep their standalone library names, which the
  design's naming exceptions for `intonation-format` and `sc-midi-javamidi` build on.
- No coverage floor changes: a floor is never lowered.
- Every commit message starts with `[#366/#367]` and ends with the attribution lines that your session's instructions
  give.
- Don't edit `issues/` or `docs/agent-evals/`: their files are dated snapshots tied to a commit, so their old paths
  stay. `outlook.md` is out of scope.
- The changes to the bundled scripts follow red/green. The move itself has the existing suite as its test.
- Docs follow `docs/development/doc-conventions.md`, with lines of at most 120 columns.
- Work in this clone with `git switch`: no git worktree, and never touch the processes of another clone
  (`microtonalist2`).

## Review Focus

1. **Build output in a grouping folder.** A tool still running on the old layout (an orphan sbt, Metals, an IDE such as
   IntelliJ with the old project open) recreates `tuner/target-bsp/` or similar. Every grouping folder must list only
   its modules: checked in Task 2, steps 6 and 10, and again in Task 7.
2. **sbt or Metals on the old build definition.** A stack that wasn't restarted after the `build.sbt` change keeps
   building the old paths. `sbtn 'show tuner/target'` must end in `tuner/tuner/target-bsp`: Task 2, step 8.
3. **This session's scoverage-inspector MCP server runs the old code** until it is reconnected, and the old code calls
   any report of a moved module fresh. Task 7 runs the coverage build before reading any report, and reads them with
   the CLI, which loads the new code.
4. **The launchers and the JAR that a plain `sbt assembly` builds.** The unit test fakes the JAR. Task 7, step 7 builds
   the real one and runs both launchers against a fake `java` that checks the JAR exists.
5. **Old module paths that the link checker doesn't see**, in ScalaDoc, comments, scripts and configuration. Task 6,
   step 9 greps every tracked file for them.

---

### Task 1: Branch and baseline

**Files:**
- Create (untracked): `$SCRATCH/check_md_links.py`, where `$SCRATCH` is your session's scratchpad directory

**Interfaces:**
- Produces: the branch `refactoring/grouping-folders`; the link checker, run as `python3 $SCRATCH/check_md_links.py .`
  from the repository root, which Tasks 6 and 7 use.

- [ ] **Step 1: Check the starting point**

```bash
git switch doc/modularization-proposal
git status --short
git log --oneline -3
```

Expected: no output from `git status`; the log shows this plan's commit on top of `c0f0ba82`.

- [ ] **Step 2: Create the branch**

```bash
git switch -c refactoring/grouping-folders
```

- [ ] **Step 3: Save the link checker**

It checks every relative Markdown link, `#heading` link and `@import` of the tracked docs, outside `issues/` and
`docs/agent-evals/`. Save it as `$SCRATCH/check_md_links.py`; it isn't committed.

````python
#!/usr/bin/env python3
"""Checks that every relative Markdown link, `#heading` link and `@import` of the tracked docs resolves.

Usage: python3 check_md_links.py <repo-root>
Skips issues/ and docs/agent-evals/ (dated snapshots tied to a commit) and fenced code blocks.
Exit status: 0 when every link resolves, 1 otherwise.
"""
import re
import subprocess
import sys
from pathlib import Path

SKIPPED_PREFIXES = ("issues/", "docs/agent-evals/")
# A link's text may wrap onto the next line, so the pattern runs over the whole doc.
LINK = re.compile(r"\[(?:[^\[\]]|\[[^\]]*\])*\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
IMPORT = re.compile(r"(?:^|\s)@([\w./-]+\.md)\b", re.MULTILINE)
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
FENCE = re.compile(r"^\s*(```|~~~)")
INLINE_CODE = re.compile(r"`[^`\n]*`")


def slug(heading: str) -> str:
    """GitHub's anchor for a heading: lowercase, punctuation dropped, spaces to hyphens."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", heading).replace("`", "")
    text = re.sub(r"[^\w\- ]", "", text.lower())
    return text.replace(" ", "-")


def prose(path: Path) -> str:
    """The doc's text with its fenced code blocks blanked, keeping the line numbers."""
    lines, in_fence = [], False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            lines.append("")
        else:
            lines.append("" if in_fence else line)
    return "\n".join(lines)


def anchors(path: Path) -> set:
    seen, result = {}, set()
    for line in prose(path).splitlines():
        match = HEADING.match(line)
        if match:
            base = slug(match.group(2))
            count = seen.get(base, 0)
            seen[base] = count + 1
            result.add(base if count == 0 else f"{base}-{count}")
    return result


def targets(text: str):
    """Yields the line number and target of every link and `@import` of a doc's prose."""
    for match in LINK.finditer(text):
        yield text.count("\n", 0, match.start()) + 1, match.group(1)
    # Blank inline code, same length, so that an `@path` in backticks isn't taken for an import.
    no_code = INLINE_CODE.sub(lambda m: " " * len(m.group(0)), text)
    for match in IMPORT.finditer(no_code):
        yield no_code.count("\n", 0, match.start(1)) + 1, match.group(1)


def main(root: Path) -> int:
    files = subprocess.run(["git", "ls-files", "*.md"], cwd=root, capture_output=True, text=True,
                           check=True).stdout.split()
    broken, checked = [], 0
    for relative in files:
        if relative.startswith(SKIPPED_PREFIXES):
            continue
        doc = root / relative
        if not doc.is_file():
            continue
        for number, target in targets(prose(doc)):
            if re.match(r"^[a-z][a-z0-9+.-]*:", target):
                continue
            checked += 1
            file_part, _, fragment = target.partition("#")
            resolved = (doc.parent / file_part).resolve() if file_part else doc.resolve()
            if not resolved.exists():
                broken.append(f"{relative}:{number}: missing {target}")
            elif fragment and resolved.suffix == ".md" and fragment not in anchors(resolved):
                broken.append(f"{relative}:{number}: no heading #{fragment} in {target}")
    for line in broken:
        print(line)
    print(f"{len(broken)} broken of {checked} relative link(s) and import(s)")
    return 1 if broken else 0


if __name__ == "__main__":
    sys.exit(main(Path(sys.argv[1] if len(sys.argv) > 1 else ".").resolve()))
````

- [ ] **Step 4: Record the baseline**

```bash
python3 $SCRATCH/check_md_links.py .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py | wc -l
python3 -m unittest discover -s .claude/skills/architecture-docs/scripts/tests -p "test_*.py"
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
python3 -m unittest discover -s bin/tests -p "test_*.py"
```

Expected, at the plan's commit: `0 broken of 238 relative link(s) and import(s)`; `20` docs; the three suites pass
(29, 56 and 27 tests; the `bin/` suite takes about two minutes). If the counts differ because `main` gained docs or
tests since, note the new numbers: the later steps compare against them.

---

### Task 2: Move the modules

**Files:**
- Move (`git mv`): the 14 module folders, into `app/`, `base/`, `format/`, `midi/`, `rig/`, `theory/`, `tuner/`, `ui/`
- Modify: `build.sbt` (the CONVENTION comment, the 14 `file(…)` paths, `appConfigModule` → `configModule`)

**Interfaces:**
- Consumes: Task 1's branch.
- Produces: every module at `<group>/<id>/`, with unchanged sbt project IDs; `build.sbt`'s `lazy val configModule`.

- [ ] **Step 1: Stop the development stack**

The stack's sbt and Metals hold the old folders' build output; stop them before deleting it.

```bash
bin/mtlist-dev-stack stop
bin/mtlist-dev-stack status; echo "exit=$?"
```

Expected: `exit=1`. Close any IDE that has the project open, so that it doesn't recreate build output in the old
folders.

- [ ] **Step 2: Delete the modules' build output and count their files**

`app/`, `format/`, `rig/`, `tuner/` and `ui/` become grouping folders, so no build output may stay behind in them.

```bash
for m in app cli config experiments businessync common common-test-utils format sc-midi rig composition intonation \
  tuner ui; do
  rm -rf "$m/target" "$m/target-bsp" "$m/target-scoverage"
done
git status --porcelain --ignored -- app cli config experiments businessync common common-test-utils format sc-midi rig \
  composition intonation tuner ui
git ls-files -- app cli config experiments businessync common common-test-utils format sc-midi rig composition \
  intonation tuner ui | wc -l
```

Expected: `git status` prints nothing. If it lists anything, look at it: delete only build output, and ask the user
about anything else. Note the file count (340 at the plan's commit) as `N`.

- [ ] **Step 3: Move the five modules whose folder becomes their grouping folder**

A folder can't move into itself, so each goes through a temporary name.

```bash
for m in app format rig tuner ui; do git mv "$m" "$m.tmp" && mkdir "$m" && git mv "$m.tmp" "$m/$m"; done
```

- [ ] **Step 4: Move the other nine modules**

```bash
mkdir base midi theory
git mv cli config experiments app/
git mv businessync common common-test-utils base/
git mv sc-midi midi/
git mv composition intonation theory/
```

- [ ] **Step 5: Check that only renames are staged**

```bash
git status --porcelain | grep -c '^R  '
git status --porcelain | grep -vc '^R  '
```

Expected: `N`, then `0`.

- [ ] **Step 6: Check the layout**

```bash
for g in app base format midi rig theory tuner ui; do echo "$g: $(ls -A "$g" | tr '\n' ' ')"; done
find . -mindepth 2 -maxdepth 2 -name src -type d | wc -l
```

Expected exactly (the second command counts the `src/` folders one level below the root: none):

```
app: app cli config experiments
base: businessync common common-test-utils
format: format
midi: sc-midi
rig: rig
theory: composition intonation
tuner: tuner
ui: ui
0
```

- [ ] **Step 7: Point `build.sbt` at the new folders**

Replace the CONVENTION comment, under `// # Projects`:

```text
// CONVENTION: every project sets `.withId(<base-directory-name>)` so the sbt project ID always equals the
// project's base directory name — even where the `lazy val` name already matches (it is added redundantly on
// purpose, to make the rule uniform and self-documenting). This consistency is relied upon by tooling that
// maps a source directory to its sbt module ID without parsing this file — notably the `scoverage-inspector`
// MCP server (`.claude/mcp/scoverage_inspector/`), whose reports live at `coverage-reports/<id>/` while sources
// live at `<dir>/src/`. The `lazy val` name follows the convention `<camelCase-dir-name>Module` (e.g.
// `scMidiModule` for the `sc-midi` directory). The ID — used in `sbt "<id>/test"`, in `thisProject.value.id`,
// and thus in `coverageDataDir` — must equal the directory. The sole special case is `root` (base dir ".",
// ID "root"), which keeps its plain `root` val name. When adding a module, set its `.withId` to its directory
// name and keep the two in lockstep.
```

with:

```text
// CONVENTION: every module lives at `<group>/<id>/`, in a grouping folder that holds the modules of one area and
// nothing else (see docs/architecture/module-overview.md), and sets `.withId(<id>)`, so its sbt project ID always
// equals the name of its own folder — even where the `lazy val` name already matches (it is added redundantly on
// purpose, to make the rule uniform and self-documenting). This consistency is relied upon by tooling that maps a
// source directory to its sbt module ID without parsing this file — notably the `scoverage-inspector` MCP server
// (`.claude/mcp/scoverage_inspector/`), whose reports live at `coverage-reports/<id>/` while sources live at
// `<group>/<id>/src/`. The `lazy val` name follows the convention `<camelCaseId>Module` (e.g. `scMidiModule` for
// `midi/sc-midi`). The ID — used in `sbt "<id>/test"`, in `thisProject.value.id`, and thus in `coverageDataDir` — must
// equal the folder's name. The sole special case is `root` (base dir ".", ID "root"), which keeps its plain `root` val
// name. When adding a module, put it in its area's grouping folder and set its `.withId` to its folder's name.
```

Then change the paths, and rename `appConfigModule` to `configModule`, as the convention asks (its ID is `config`):

```bash
sed -i '' \
  -e 's|(project in file("app"))|(project in file("app/app"))|' \
  -e 's|(project in file("cli"))|(project in file("app/cli"))|' \
  -e 's|lazy val appConfigModule = (project in file("config"))|lazy val configModule = (project in file("app/config"))|' \
  -e 's|(project in file("experiments"))|(project in file("app/experiments"))|' \
  -e 's|(project in file("businessync"))|(project in file("base/businessync"))|' \
  -e 's|(project in file("common"))|(project in file("base/common"))|' \
  -e 's|(project in file("common-test-utils"))|(project in file("base/common-test-utils"))|' \
  -e 's|(project in file("format"))|(project in file("format/format"))|' \
  -e 's|(project in file("sc-midi"))|(project in file("midi/sc-midi"))|' \
  -e 's|(project in file("rig"))|(project in file("rig/rig"))|' \
  -e 's|(project in file("composition"))|(project in file("theory/composition"))|' \
  -e 's|(project in file("intonation"))|(project in file("theory/intonation"))|' \
  -e 's|(project in file("tuner"))|(project in file("tuner/tuner"))|' \
  -e 's|(project in file("ui"))|(project in file("ui/ui"))|' \
  -e 's|^    appConfigModule,$|    configModule,|' \
  build.sbt
grep -nE 'project in file|appConfigModule|configModule' build.sbt
```

Expected: no `appConfigModule`; `configModule` in `root`'s `aggregate`, in `appModule`'s `dependsOn` and in its own
definition; and these paths:

```
lazy val root = (project in file("."))
lazy val appModule = (project in file("app/app"))
lazy val configModule = (project in file("app/config"))
lazy val cliModule = (project in file("app/cli"))
lazy val uiModule = (project in file("ui/ui"))
lazy val commonModule = (project in file("base/common"))
lazy val commonTestUtilsModule = (project in file("base/common-test-utils"))
lazy val businessyncModule = (project in file("base/businessync"))
lazy val compositionModule = (project in file("theory/composition"))
lazy val tunerModule = (project in file("tuner/tuner"))
lazy val rigModule = (project in file("rig/rig"))
lazy val formatModule = (project in file("format/format"))
lazy val intonationModule = (project in file("theory/intonation"))
lazy val scMidiModule = (project in file("midi/sc-midi"))
lazy val experimentsModule = (project in file("app/experiments"))
```

- [ ] **Step 8: Start the stack on the new build and compile**

```bash
bin/mtlist-dev-stack start
sbtn 'show tuner/target'
sbtn projects
```

Expected: the stack becomes ready; `show tuner/target` prints a path ending in `/tuner/tuner/target-bsp`; `projects`
lists the same 15 projects as before (`app`, `businessync`, `cli`, `common`, `common-test-utils`, `composition`,
`config`, `experiments`, `format`, `intonation`, `rig`, `root`, `sc-midi`, `tuner`, `ui`).

Then run `mcp__metals__compile-full`. Expected: success, with only the two deprecation warnings on
`TuningService.tunings` (in `TuningServiceTest` and `TuningListFrame`), now under `tuner/tuner/…` and `ui/ui/…`.
`mcp__metals__list-modules` lists the same 31 build targets. If the Metals MCP tools stopped answering, ask the user to
reconnect them with `/mcp`.

- [ ] **Step 9: Run the full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
```

Expected: all tests pass (1,475 at the plan's commit).

- [ ] **Step 10: Check that the build wrote nothing into a grouping folder**

```bash
for g in app base format midi rig theory tuner ui; do echo "$g: $(ls -A "$g" | tr '\n' ' ')"; done
```

Expected: the same listing as in step 6.

- [ ] **Step 11: Commit**

```bash
git add build.sbt
git commit -m "[#366/#367] Move every module into a grouping folder" -m "<attribution lines>"
git show -M --format= --name-status HEAD | cut -f1 | sort | uniq -c
```

Expected: `N` `R100` lines and `1 M` (`build.sbt`), so `git log --follow` keeps each file's history.

---

### Task 3: The size script finds the docs of a module in a grouping folder

This task changes the architecture-docs skill's bundled script, so reading its source is expected here, although the
skill tells its callers not to.

**Files:**
- Modify: `.claude/skills/architecture-docs/scripts/check_arch_doc_size.py:33-34,142-143`
- Modify: `.claude/skills/architecture-docs/scripts/tests/test_check_arch_doc_size.py:144-190`
- Modify: `.claude/skills/architecture-docs/SKILL.md:68-70`

**Interfaces:**
- Consumes: the layout from Task 2.
- Produces: `MODULE_DOCS_GLOB = "*/*/docs"`; with no path, the script checks `docs/architecture/` and every
  `<group>/<module>/docs/`.

- [ ] **Step 1: Write the failing test**

In `test_check_arch_doc_size.py`, replace `test_checks_every_doc_under_the_docs_of_a_module_by_default` with:

```python
    def test_checks_every_doc_under_the_docs_of_a_module_in_a_grouping_folder_by_default(self):
        # Given
        self.write("tuner/tuner/docs/ARCHITECTURE.md", words(10))
        self.write("tuner/tuner/docs/mpe/paper.md", words(10))
        self.write("tuner/tuner/README.md", words(10))
        self.write("tuner/tuner/src/notes.md", words(10))
        self.write("midi/sc-midi/docs/ARCHITECTURE.md", words(10))
        self.write("docs/architecture/a.md", words(10))

        # When
        code, lines, _ = self.run_main()

        # Then
        self.assertEqual(code, 0)
        self.assertEqual([line.split()[1] for line in lines], [
            "docs/architecture/a.md",
            "midi/sc-midi/docs/ARCHITECTURE.md",
            "tuner/tuner/docs/ARCHITECTURE.md",
            "tuner/tuner/docs/mpe/paper.md",
        ])
```

and, so that the fixtures show the real layout, replace the body of `test_expands_a_directory_given_to_its_docs` with:

```python
        # Given
        self.write("tuner/tuner/docs/ARCHITECTURE.md", words(10))
        self.write("tuner/tuner/docs/mpe.md", words(10))
        self.write("docs/architecture/other.md", words(10))

        # When
        code, lines, _ = self.run_main(str(self.root / "tuner/tuner/docs"))

        # Then
        self.assertEqual(code, 0)
        self.assertEqual([line.split()[1] for line in lines],
                         ["tuner/tuner/docs/ARCHITECTURE.md", "tuner/tuner/docs/mpe.md"])
```

- [ ] **Step 2: Run the tests to verify that the new one fails**

```bash
python3 -m unittest discover -s .claude/skills/architecture-docs/scripts/tests -p "test_*.py"
```

Expected: `FAILED (failures=1)`, in `test_checks_every_doc_under_the_docs_of_a_module_in_a_grouping_folder_by_default`:
`Lists differ: ['docs/architecture/a.md'] != ['docs/architecture/a.md', 'midi/sc-midi/docs/ARCHITECTURE.md', …]`.

- [ ] **Step 3: Write the implementation**

In `check_arch_doc_size.py`, replace:

```python
# Matches the docs/ directory of each module, a top-level directory of the repository.
MODULE_DOCS_GLOB = "*/docs"
```

with:

```python
# Matches the docs/ directory of each module, which sits in a grouping folder at the top of the repository.
MODULE_DOCS_GLOB = "*/*/docs"
```

and, in the `paths` argument's help, replace `"<module>/docs/)")` with `"<group>/<module>/docs/)")`.

- [ ] **Step 4: Run the tests and the script**

```bash
python3 -m unittest discover -s .claude/skills/architecture-docs/scripts/tests -p "test_*.py"
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py --help | grep -A1 default
```

Expected: `OK` (29 tests); the script lists the same 20 docs as Task 1's baseline, at their new paths
(`app/app/docs/ARCHITECTURE.md`, …, `tuner/tuner/docs/mpe-tuner.md`, `ui/ui/docs/ARCHITECTURE.md`), and exits 0; the
help ends with `<group>/<module>/docs/)`.

- [ ] **Step 5: Update the skill's description of the default**

In `.claude/skills/architecture-docs/SKILL.md`, replace:

```text
With no path it checks every doc under `docs/architecture/` and `<module>/docs/`. Up to 1200 words is OK; 1201–1500
warns, compacting recommended; above 1500 is an error that fails CI, compacting required. The script is a black box:
use `--help` for its usage, and don't read its source.
```

with:

```text
With no path it checks every doc under `docs/architecture/` and `<group>/<module>/docs/`. Up to 1200 words is OK;
1201–1500 warns, compacting recommended; above 1500 is an error that fails CI, compacting required. The script is a
black box: use `--help` for its usage, and don't read its source.
```

- [ ] **Step 6: Commit**

```bash
git add .claude/skills/architecture-docs
git commit -m "[#366/#367] Find the module docs in their grouping folders" -m "<attribution lines>"
```

---

### Task 4: The scoverage inspector finds a module's sources in its grouping folder

The inspector's freshness check looks for a module's sources at `<root>/<id>/src/`, which no longer exists, so it
calls every report fresh. The aggregate filter, which matches `/<id>/src/` in a report's source paths, keeps working.

**Files:**
- Modify: `.claude/mcp/scoverage_inspector/scoverage_core.py` (module docstring; `_module_dirs`; `freshness`; the
  `module_summary` docstring)
- Modify: `.claude/mcp/scoverage_inspector/tests/test_scoverage_core.py`
- Modify: `.claude/mcp/scoverage_inspector/tests/test_server.py:49-52`
- Modify: `.claude/mcp/scoverage_inspector/tests/resources/scoverage-sample.xml` (its `source` paths)
- Modify: `.claude/skills/scoverage-inspector/SKILL.md:67-72`

**Interfaces:**
- Consumes: the layout from Task 2.
- Produces: `_module_dirs(root: Path) -> list[str]`, the sorted paths, relative to `root`, of every `<group>/<id>`
  that holds a `src/`; `freshness(root, module_id, aggregate=False)` reads the sources of the module whose folder is
  named `module_id`.

- [ ] **Step 1: Write the failing tests**

In `test_scoverage_core.py`, replace the `ModuleDirsTest` test with:

```python
class ModuleDirsTest(unittest.TestCase):
    def test_lists_only_dirs_with_a_src_subdir_in_a_grouping_folder(self):
        # Given a repo root with two module dirs in grouping folders (each with src/) and one without
        tmp = Path(tempfile.mkdtemp())
        try:
            (tmp / "midi" / "sc-midi" / "src").mkdir(parents=True)
            (tmp / "app" / "config" / "src").mkdir(parents=True)
            (tmp / "coverage-reports" / "sc-midi").mkdir(parents=True)  # not a module — no src/
            # Then only the module dirs are returned, relative to the root and sorted
            self.assertEqual(core._module_dirs(tmp), ["app/config", "midi/sc-midi"])
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
```

In `FreshnessTest.setUp`, replace:

```python
        # The module ID equals its base directory name by build convention, so the
        # report dir, source dir, and queried module ID are all "sc-midi".
        self.tmp = Path(tempfile.mkdtemp())
        self.src = self.tmp / "sc-midi" / "src" / "main" / "scala"
```

with:

```python
        # The module ID equals the name of its directory, in a grouping folder, by build
        # convention, so the report dir and queried module ID are "sc-midi" and the source
        # dir is "midi/sc-midi".
        self.tmp = Path(tempfile.mkdtemp())
        self.src = self.tmp / "midi" / "sc-midi" / "src" / "main" / "scala"
```

Replace the head of `test_uses_module_id_as_source_directory`, keeping its body:

```python
    def test_uses_module_id_as_source_directory(self):
        # The report lives at coverage-reports/sc-midi while sources live at sc-midi/src;
        # freshness must resolve the source dir from the module ID alone (no build.sbt).
```

with:

```python
    def test_finds_the_source_directory_from_the_module_id(self):
        # The report lives at coverage-reports/sc-midi while sources live at midi/sc-midi/src;
        # freshness must find the source dir from the module ID alone (no build.sbt).
```

Add these two tests to `FreshnessTest`, before `test_aggregate_uses_root_report_and_all_sources`:

```python
    def test_stale_for_a_module_named_after_its_grouping_folder(self):
        # Given the tuner module at tuner/tuner, with a source newer than its report
        src = self.tmp / "tuner" / "tuner" / "src" / "main" / "scala"
        src.mkdir(parents=True)
        source_file = src / "Tuner.scala"
        source_file.write_text("trait Tuner")
        report = _write_report(self.tmp, "tuner")
        _age_tree(src, 1000)
        _set_mtime(report, 1500)
        _set_mtime(source_file, 2000)
        # Then its report is stale
        self.assertEqual(core.freshness(self.tmp, "tuner"), core.Freshness.STALE)

    def test_aggregate_covers_the_modules_of_every_grouping_folder(self):
        # Given a fresh root report and a second module, in another grouping folder
        src = self.tmp / "app" / "config" / "src" / "main" / "scala"
        src.mkdir(parents=True)
        source_file = src / "Bar.scala"
        source_file.write_text("object Bar")
        report = _write_report(self.tmp, "root")
        _age_tree(self.tmp / "midi", 1000)
        _age_tree(src, 1000)
        _set_mtime(report, 2000)
        self.assertEqual(core.freshness(self.tmp, "root", aggregate=True), core.Freshness.FRESH)
        # When the second module's source changes
        _set_mtime(source_file, 3000)
        # Then the root report is stale
        self.assertEqual(core.freshness(self.tmp, "root", aggregate=True), core.Freshness.STALE)
```

In `ModuleSummaryTest.test_aggregate_filters_to_module_directory`, replace the comment
`# sc-midi sources live under sc-midi/ -> only Foo matches` with
`# sc-midi sources live under midi/sc-midi/ -> only Foo matches`.

In `test_server.py`, replace:

```python
    """A temp repo root with a module report and a matching source tree (id == dir)."""

    def __init__(self, report_id="sc-midi", source_dir="sc-midi"):
```

with:

```python
    """A temp repo root with a module report and a matching source tree (id == its dir's name)."""

    def __init__(self, report_id="sc-midi", source_dir="midi/sc-midi"):
```

Move the sample report's sources into grouping folders, so that the aggregate filter's tests read real paths:

```bash
sed -i '' -e 's|/repo/config/src/|/repo/app/config/src/|g' -e 's|/repo/sc-midi/src/|/repo/midi/sc-midi/src/|g' \
  .claude/mcp/scoverage_inspector/tests/resources/scoverage-sample.xml
grep -o 'source="[^"]*"' .claude/mcp/scoverage_inspector/tests/resources/scoverage-sample.xml | sort -u
```

Expected: `source="/repo/app/config/src/main/scala/org/example/Bar.scala"` and
`source="/repo/midi/sc-midi/src/main/scala/org/example/Foo.scala"`.

- [ ] **Step 2: Run the tests to verify that they fail**

```bash
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
```

Expected: `FAILED (failures=12)`: the 7 `FreshnessTest` and `ModuleDirsTest` cases that expect a stale report or a
module directory (they get `FRESH` or `[]`), and the 5 `test_server` cases that expect a rebuild or a stale error.

- [ ] **Step 3: Write the implementation**

In `scoverage_core.py`, replace `_module_dirs`:

```python
def _module_dirs(root: Path) -> list[str]:
    """Every module's base directory, i.e. each top-level dir containing a `src/`.

    Relies on the build convention that an sbt module's ID equals its base directory
    name (`build.sbt` enforces this with `.withId(<dir>)`), so a module ID *is* its
    source directory — no `build.sbt` parsing is needed. Used to scope the aggregate
    freshness check across all modules.
    """
    if not root.is_dir():
        return []
    return sorted(d.name for d in root.iterdir() if (d / "src").is_dir())
```

with:

```python
def _module_dirs(root: Path) -> list[str]:
    """Every module's directory relative to `root`, i.e. each `<group>/<id>` containing a `src/`.

    Relies on the build convention that every module sits in a grouping folder, in a
    directory named after its sbt module ID (`build.sbt` enforces this with
    `.withId(<dir>)`), so a module ID finds its source directory — no `build.sbt`
    parsing is needed.
    """
    if not root.is_dir():
        return []
    return sorted(src.parent.relative_to(root).as_posix() for src in root.glob("*/*/src") if src.is_dir())
```

and, in `freshness`, replace:

```python
    directories = _module_dirs(root) if aggregate else [module_id]
```

with:

```python
    directories = _module_dirs(root)
    if not aggregate:
        directories = [directory for directory in directories if Path(directory).name == module_id]
```

- [ ] **Step 4: Run the tests to verify that they pass**

```bash
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
```

Expected: `OK` (58 tests).

- [ ] **Step 5: Check a live module**

```bash
bin/mtlist-scoverage-inspector freshness intonation
touch theory/intonation/src/main/scala/org/calinburloiu/music/intonation/Scale.scala
bin/mtlist-scoverage-inspector freshness intonation
```

Expected: the second call prints `stale: intonation`, which the old code never did for a moved module; or `missing`
twice if `coverage-reports/intonation/` holds no report, in which case Task 7's coverage run exercises the check. The
`touch` changes no content; Task 7 rebuilds the report.

- [ ] **Step 6: Update the docstrings**

In `scoverage_core.py`, replace the module docstring's paragraph:

```text
This module relies on the build convention that an sbt module's ID equals its base
directory name (`build.sbt` enforces this with `.withId(<dir>)`). That means a module
ID *is* its source directory, so no `build.sbt` parsing is needed: scoverage reports
live at `coverage-reports/<id>/...` (sbt's `thisProject.value.id`) and sources at
`<id>/src/...`.
```

with:

```text
This module relies on the build convention that an sbt module's ID equals the name of
its folder, which sits in a grouping folder (`build.sbt` enforces this with
`.withId(<dir>)`). That means a module ID finds its source directory, so no `build.sbt`
parsing is needed: scoverage reports live at `coverage-reports/<id>/...` (sbt's
`thisProject.value.id`) and sources at `<group>/<id>/src/...`.
```

In `freshness`'s docstring, replace:

```text
    Per-module mode compares the report mtime against the newest `.scala` (and
    directory) under the module's `src/{main,test}/scala`. Because the sbt module ID
    equals its base directory name by build convention, the module ID *is* the source
    directory — no `build.sbt` lookup is needed. Aggregate mode compares the root
    report against the newest source across every module directory.
```

with:

```text
    Per-module mode compares the report mtime against the newest `.scala` (and
    directory) under the module's `<group>/<id>/src/{main,test}/scala`. Because the
    sbt module ID equals the name of its folder by build convention, the module ID
    finds the source directory — no `build.sbt` lookup is needed. Aggregate mode
    compares the root report against the newest source across every module directory.
```

In `module_summary`'s docstring, replace `Since the module ID equals its base directory name` with
`Since the module ID equals the name of its folder`.

- [ ] **Step 7: Update the skill's module lookup**

In `.claude/skills/scoverage-inspector/SKILL.md`, replace:

```text
2. By build convention the **sbt module ID is the first path segment** of the match (the base
   directory name; `build.sbt` enforces this with `.withId(<dir>)`). IDs may be kebab-case
   (`sc-midi`, `common-test-utils`, `config`). Example:
   `org.calinburloiu.music.microtonalist.config.MainConfigManager` is declared in
   `config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigManagement.scala` — note
   the file is **not** named `MainConfigManager.scala` — so the module is `config`.
```

with:

```text
2. By build convention the **sbt module ID is the path segment before `src/`** in the match (the
   module's folder, inside a grouping folder; `build.sbt` enforces this with `.withId(<dir>)`). IDs
   may be kebab-case (`sc-midi`, `common-test-utils`, `config`). Example:
   `org.calinburloiu.music.microtonalist.config.MainConfigManager` is declared in
   `app/config/src/main/scala/org/calinburloiu/music/microtonalist/config/ConfigManagement.scala` —
   note the file is **not** named `MainConfigManager.scala` — so the module is `config`.
```

- [ ] **Step 8: Rerun the tests and commit**

```bash
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
git add .claude/mcp/scoverage_inspector .claude/skills/scoverage-inspector
git commit -m "[#366/#367] Find a module's sources in its grouping folder" -m "<attribution lines>"
```

Expected: `OK` (58 tests). This session's `scoverage-inspector` MCP server still runs the old code; the user can
reload it with `/mcp`. Until then, read reports with `bin/mtlist-scoverage-inspector`.

---

### Task 5: The launchers run the fat JARs in `app/app` and `app/cli`

**Files:**
- Modify: `bin/mtlist:22`, `bin/mtlist-tool:22`
- Modify: `bin/tests/test_wrapper_scripts.py:93,104`
- Modify: `bin/README.md:26`, `docs/development/build.md:152-153`

**Interfaces:**
- Consumes: the layout from Task 2, where `assembly` writes `app/app/target/scala-<version>/microtonalist-app.jar` and
  `app/cli/target/scala-<version>/microtonalist-cli.jar`.
- Produces: launchers that run those JARs.

- [ ] **Step 1: Write the failing tests**

In `test_wrapper_scripts.py`, replace:

```python
        self.assert_same_path(args[1], self.repo / "app" / "target" / "scala-3.1.4" / "microtonalist-app.jar")
```

with:

```python
        self.assert_same_path(args[1], self.repo / "app" / "app" / "target" / "scala-3.1.4" / "microtonalist-app.jar")
```

and:

```python
        self.assert_same_path(args[1], self.repo / "cli" / "target" / "scala-3.1.4" / "microtonalist-cli.jar")
```

with:

```python
        self.assert_same_path(args[1], self.repo / "app" / "cli" / "target" / "scala-3.1.4" / "microtonalist-cli.jar")
```

- [ ] **Step 2: Run the tests to verify that they fail**

```bash
python3 -m unittest discover -s bin/tests -p "test_wrapper_scripts.py"
```

Expected: `FAILED (failures=2)`, in `test_mtlist_runs_the_app_jar_built_with_the_scala_version_of_the_build` and
`test_mtlist_tool_runs_the_cli_jar_built_with_the_scala_version_of_the_build`.

- [ ] **Step 3: Write the implementation**

In `bin/mtlist`, replace:

```bash
JAR="${SCRIPT_PATH}/../app/target/scala-${SCALA_VERSION}/microtonalist-app.jar"
```

with:

```bash
JAR="${SCRIPT_PATH}/../app/app/target/scala-${SCALA_VERSION}/microtonalist-app.jar"
```

In `bin/mtlist-tool`, replace:

```bash
JAR="${SCRIPT_PATH}/../cli/target/scala-${SCALA_VERSION}/microtonalist-cli.jar"
```

with:

```bash
JAR="${SCRIPT_PATH}/../app/cli/target/scala-${SCALA_VERSION}/microtonalist-cli.jar"
```

- [ ] **Step 4: Run the tests to verify that they pass**

```bash
python3 -m unittest discover -s bin/tests -p "test_wrapper_scripts.py"
python3 -m unittest discover -s bin/tests -p "test_*.py"
```

Expected: `OK` (7 tests), then `OK` (27 tests, in about two minutes).

- [ ] **Step 5: Update the docs of the JAR paths**

In `bin/README.md`, replace `` `app/target/scala-<version>/` and `cli/target/scala-<version>/`. The Scala `` with
`` `app/app/target/scala-<version>/` and `app/cli/target/scala-<version>/`. The Scala ``.

In `docs/development/build.md`, replace:

```text
The `bin/mtlist` and `bin/mtlist-tool` launchers run the fat JARs from `app/target/scala-<version>/` and
`cli/target/scala-<version>/`, which a plain `sbt assembly` builds; `sbtn assembly` builds them under `target-bsp/`
```

with:

```text
The `bin/mtlist` and `bin/mtlist-tool` launchers run the fat JARs from `app/app/target/scala-<version>/` and
`app/cli/target/scala-<version>/`, which a plain `sbt assembly` builds; `sbtn assembly` builds them under `target-bsp/`
```

- [ ] **Step 6: Commit**

```bash
git add bin docs/development/build.md
git commit -m "[#366/#367] Run the fat JARs from their grouping folder" -m "<attribution lines>"
```

---

### Task 6: The docs point at the grouping folders

**Files:**
- Modify: the module docs `rig/rig/docs/ARCHITECTURE.md`, `midi/sc-midi/docs/ARCHITECTURE.md`,
  `tuner/tuner/docs/ARCHITECTURE.md`, `ui/ui/docs/ARCHITECTURE.md`, `theory/composition/docs/ARCHITECTURE.md`,
  `app/config/docs/ARCHITECTURE.md`
- Modify: `docs/architecture/README.md`, `docs/architecture/data-flow.md`, `docs/architecture/domain-concepts.md`,
  `docs/architecture/module-overview.md`
- Modify: `AGENTS.md` (`CLAUDE.md` is a symlink to it), `README.md`
- Modify: `docs/development/build.md`, `docs/development/coverage.md`, `docs/development/doc-conventions.md`,
  `docs/development/claude-code-setup.md`, `docs/agents/dev-stack.md`
- Modify: `.claude/skills/architecture-docs/SKILL.md`
- Modify: `tuner/tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala` (ScalaDoc only)

**Interfaces:**
- Consumes: the layout from Task 2; Task 1's link checker.
- Produces: docs whose relative links all resolve, and whose paths name the grouping folders.

- [ ] **Step 1: Invoke the `architecture-docs` skill**

`AGENTS.md` asks for it when a change affects an architecture document. Its maintenance checklist applies: the types
and flows don't change here, only the paths and links.

- [ ] **Step 2: See which links the move broke**

```bash
python3 $SCRATCH/check_md_links.py .
```

Expected: `29 broken of 238 relative link(s) and import(s)`, in `AGENTS.md` (and its `CLAUDE.md` symlink), `README.md`,
`docs/architecture/README.md`, `docs/architecture/data-flow.md` and the module docs of `sc-midi`, `rig`, `tuner` and
`ui`. Steps 3–7 fix them all.

- [ ] **Step 3: Fix the module docs**

A module doc's links gain a `../`: into the root `docs/` and into the other modules' folders.

In `rig/rig/docs/ARCHITECTURE.md`, replace `(../../tuner/docs/ARCHITECTURE.md)` with
`(../../../tuner/tuner/docs/ARCHITECTURE.md)`, and
`(../../docs/architecture/midi-device-lifecycle.md#subject-to-change-305)` with
`(../../../docs/architecture/midi-device-lifecycle.md#subject-to-change-305)`. Then replace:

```text
[`businessync`](../../businessync/docs/ARCHITECTURE.md)); other threads go through `RigService`. The processors, and
the tuners and tuning changers they hold, are `@NotThreadSafe` too: MIDI messages run through them on the input
device's callback thread, and `tune` and `reset` come from the business thread; #121 will give each track a thread of
its own.
```

with:

```text
[`businessync`](../../../base/businessync/docs/ARCHITECTURE.md)); other threads go through `RigService`. The
processors, and the tuners and tuning changers they hold, are `@NotThreadSafe` too: MIDI messages run through them on
the input device's callback thread, and `tune` and `reset` come from the business thread; #121 will give each track a
thread of its own.
```

In `midi/sc-midi/docs/ARCHITECTURE.md`, replace `(../../businessync/docs/ARCHITECTURE.md)` with
`(../../../base/businessync/docs/ARCHITECTURE.md)`, and `(../../docs/architecture/midi-device-lifecycle.md)` with
`(../../../docs/architecture/midi-device-lifecycle.md)`.

In `ui/ui/docs/ARCHITECTURE.md`, replace `(../../businessync/docs/ARCHITECTURE.md)` with
`(../../../base/businessync/docs/ARCHITECTURE.md)`.

In `tuner/tuner/docs/ARCHITECTURE.md`, replace:

```text
[`businessync`](../../businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners and the
tuning changers are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's callback
thread, and tunes and resets them on the business thread (see [`rig`](../../rig/docs/ARCHITECTURE.md#threading)).
```

with:

```text
[`businessync`](../../../base/businessync/docs/ARCHITECTURE.md)); other threads go through `TuningService`. The tuners
and the tuning changers are `@NotThreadSafe` too: a track runs MIDI messages through them on the input device's
callback thread, and tunes and resets them on the business thread (see
[`rig`](../../../rig/rig/docs/ARCHITECTURE.md#threading)).
```

In `theory/composition/docs/ARCHITECTURE.md`, replace:

```text
The `composition` module (directory `composition/`, package `org.calinburloiu.music.microtonalist.composition`) is the
domain model
```

with:

```text
The `composition` module (directory `theory/composition/`, package `org.calinburloiu.music.microtonalist.composition`)
is the domain model
```

In `app/config/docs/ARCHITECTURE.md`, replace:

```text
The `config` module (SBT project `config`, directory `config/`, `build.sbt` `lazy val`
`appConfigModule`) owns
```

with:

```text
The `config` module (SBT project `config`, directory `app/config/`, `build.sbt` `lazy val`
`configModule`) owns
```

- [ ] **Step 4: Fix the cross-cutting architecture docs**

In `docs/architecture/README.md`, replace:

```text
This directory documents the architecture of Microtonalist that spans modules. Each module (an SBT project, see
`build.sbt`) documents its own architecture in `<module>/docs/ARCHITECTURE.md`. Next to it, the module's `README.md`
links to it for human readers, and the module's `CLAUDE.md` `@import`s it so the relevant context loads on demand when
an agent works in that module.
```

with:

```text
This directory documents the architecture of Microtonalist that spans modules. Each module (an SBT project, see
`build.sbt`) documents its own architecture in `<group>/<module>/docs/ARCHITECTURE.md`. Next to it, the module's
`README.md` links to it for human readers, and the module's `CLAUDE.md` `@import`s it so the relevant context loads on
demand when an agent works in that module.
```

replace:

```text
By build convention each module's SBT project ID equals its base directory name (`build.sbt` sets `.withId(<dir>)`).
```

with:

```text
Each module lives in a grouping folder, `<group>/<module>/`, and by build convention its SBT project ID equals the name
of its own folder (`build.sbt` sets `.withId(<dir>)`).
```

replace the rows of the *Module index* table with:

```text
| `app` | [app/app/docs/ARCHITECTURE.md](../../app/app/docs/ARCHITECTURE.md) |
| `config` | [app/config/docs/ARCHITECTURE.md](../../app/config/docs/ARCHITECTURE.md) |
| `cli` | [app/cli/docs/ARCHITECTURE.md](../../app/cli/docs/ARCHITECTURE.md) |
| `ui` | [ui/ui/docs/ARCHITECTURE.md](../../ui/ui/docs/ARCHITECTURE.md) |
| `common` | [base/common/docs/ARCHITECTURE.md](../../base/common/docs/ARCHITECTURE.md) |
| `businessync` | [base/businessync/docs/ARCHITECTURE.md](../../base/businessync/docs/ARCHITECTURE.md) |
| `composition` | [theory/composition/docs/ARCHITECTURE.md](../../theory/composition/docs/ARCHITECTURE.md) |
| `tuner` | [tuner/tuner/docs/ARCHITECTURE.md](../../tuner/tuner/docs/ARCHITECTURE.md) |
| `rig` | [rig/rig/docs/ARCHITECTURE.md](../../rig/rig/docs/ARCHITECTURE.md) |
| `format` | [format/format/docs/ARCHITECTURE.md](../../format/format/docs/ARCHITECTURE.md) |
| `intonation` | [theory/intonation/docs/ARCHITECTURE.md](../../theory/intonation/docs/ARCHITECTURE.md) |
| `sc-midi` | [midi/sc-midi/docs/ARCHITECTURE.md](../../midi/sc-midi/docs/ARCHITECTURE.md) |
| `experiments` | [app/experiments/docs/ARCHITECTURE.md](../../app/experiments/docs/ARCHITECTURE.md) |
```

and replace `` [`tuner/docs/mpe-tuner-paper.md`](../../tuner/docs/mpe-tuner-paper.md) `` with
`` [`tuner/tuner/docs/mpe-tuner-paper.md`](../../tuner/tuner/docs/mpe-tuner-paper.md) ``.

In `docs/architecture/data-flow.md`, replace:

```text
> [`businessync`](../../businessync/docs/ARCHITECTURE.md); the rig in [`rig`](../../rig/docs/ARCHITECTURE.md);
> serialization in [`format`](../../format/docs/ARCHITECTURE.md); the startup wiring that builds this pipeline in
> [`app`](../../app/docs/ARCHITECTURE.md).
```

with:

```text
> [`businessync`](../../base/businessync/docs/ARCHITECTURE.md); the rig in
> [`rig`](../../rig/rig/docs/ARCHITECTURE.md); serialization in [`format`](../../format/format/docs/ARCHITECTURE.md);
> the startup wiring that builds this pipeline in [`app`](../../app/app/docs/ARCHITECTURE.md).
```

In `docs/architecture/domain-concepts.md`, replace:

```text
rest of the architecture docs assume; each module's own architecture doc, `<module>/docs/ARCHITECTURE.md`, goes deeper
(including the concrete implementations of each Plugin family).
```

with:

```text
rest of the architecture docs assume; each module's own architecture doc, `<group>/<module>/docs/ARCHITECTURE.md`, goes
deeper (including the concrete implementations of each Plugin family).
```

In `docs/architecture/module-overview.md`, replace the *Packages* section's text and table, from
`Each module's SBT project ID (equal to its base directory name by build convention; see` to the `experiments` row,
with:

```text
Each module lives in `<group>/<id>/`: a grouping folder at the repository root holds the modules of one area and
nothing else, and only `root` stays at the root. Each module's SBT project ID (equal to the name of its own folder by
build convention; see [`../development/build.md`](../development/build.md)), directory and root package:

| SBT project | Directory | Root package |
| ----------- | --------- | ------------ |
| `app` | `app/app` | `org.calinburloiu.music.microtonalist` |
| `cli` | `app/cli` | `org.calinburloiu.music.microtonalist.cli` |
| `ui` | `ui/ui` | `org.calinburloiu.music.microtonalist.ui` |
| `common` | `base/common` | `org.calinburloiu.music.microtonalist.common` |
| `composition` | `theory/composition` | `org.calinburloiu.music.microtonalist.composition` |
| `config` | `app/config` | `org.calinburloiu.music.microtonalist.config` |
| `tuner` | `tuner/tuner` | `org.calinburloiu.music.microtonalist.tuner` |
| `rig` | `rig/rig` | `org.calinburloiu.music.microtonalist.rig` |
| `format` | `format/format` | `org.calinburloiu.music.microtonalist.format` |
| `businessync` | `base/businessync` | `org.calinburloiu.businessync` |
| `intonation` | `theory/intonation` | `org.calinburloiu.music.intonation` |
| `sc-midi` | `midi/sc-midi` | `org.calinburloiu.music.scmidi` |
| `experiments` | `app/experiments` | `org.calinburloiu.music.microtonalist.experiments` |
```

The *Directory* column is where an agent finds a module's files: `AGENTS.md` points to it in step 5.

- [ ] **Step 5: Fix `AGENTS.md` and the root `README.md`**

In `AGENTS.md` (edit it, not its `CLAUDE.md` symlink), replace:

```text
  [Architecture](#architecture) section plus the architecture documents strictly relevant to the prompt — typically the
  `$MODULE/docs/ARCHITECTURE.md` of each module you will touch and its immediate collaborators. That section explains
  how the architecture documents are organized.
```

with:

```text
  [Architecture](#architecture) section plus the architecture documents strictly relevant to the prompt — typically the
  `$GROUP/$MODULE/docs/ARCHITECTURE.md` of each module you will touch and its immediate collaborators. That section
  explains how the architecture documents are organized.
```

replace:

```text
      architecture document, under `docs/architecture/` or `$MODULE/docs/`, invoke the `architecture-docs` skill.
```

with:

```text
      architecture document, under `docs/architecture/` or `$GROUP/$MODULE/docs/`, invoke the `architecture-docs`
      skill.
```

replace, in the *Build* section:

```text
Built with **SBT 1, Scala 3, and Java 25**. The repo is split into multiple SBT projects (we call them modules), all in
the repo root: `root` aggregates them all, `app` is the executable application, and `cli` is a utility tool (e.g.
listing available MIDI devices).
```

with:

```text
Built with **SBT 1, Scala 3, and Java 25**. The repo is split into multiple SBT projects (we call them modules), each in
`$GROUP/$MODULE/`: a grouping folder holds the modules of one area and nothing else, and the *Packages* table of
[`module-overview.md`](docs/architecture/module-overview.md) gives each module's directory. `root`, at the repo root,
aggregates them all, `app` is the executable application, and `cli` is a utility tool (e.g. listing available MIDI
devices).
```

and replace, in the *Architecture* section:

```text
- **Per-module deep dives (`$MODULE/docs/ARCHITECTURE.md`)** — one document per module (an architecture document
  covering responsibility, key types, flows, and module-specific concerns). Each module's `$MODULE/CLAUDE.md`
  `@import`s its document, so when you work inside a module that detail loads automatically. To read another module's
  architecture without editing in it, open its `$MODULE/docs/ARCHITECTURE.md` directly. The module's `README.md` only
  links to it, for human readers browsing the module on GitHub.
- **Per-topic documents** — some modules carry focused topic docs next to their architecture document, in
  `$MODULE/docs/`, e.g. [`tuner/docs/mpe-tuner.md`](tuner/docs/mpe-tuner.md) for the MPE Tuner.
```

with:

```text
- **Per-module deep dives (`$GROUP/$MODULE/docs/ARCHITECTURE.md`)** — one document per module (an architecture
  document covering responsibility, key types, flows, and module-specific concerns). Each module's
  `$GROUP/$MODULE/CLAUDE.md` `@import`s its document, so when you work inside a module that detail loads
  automatically. To read another module's architecture without editing in it, open its
  `$GROUP/$MODULE/docs/ARCHITECTURE.md` directly. The module's `README.md` only links to it, for human readers
  browsing the module on GitHub.
- **Per-topic documents** — some modules carry focused topic docs next to their architecture document, in
  `$GROUP/$MODULE/docs/`, e.g. [`tuner/tuner/docs/mpe-tuner.md`](tuner/tuner/docs/mpe-tuner.md) for the MPE Tuner.
```

In `README.md`, replace `` see the [`appConfig` module architecture](config/docs/ARCHITECTURE.md) `` with
`` see the [`config` module architecture](app/config/docs/ARCHITECTURE.md) ``: the module is `config`, and the
`appConfig` name came from the `lazy val` that Task 2 renamed.

- [ ] **Step 6: Fix the development and agent docs**

In `docs/development/build.md`, replace:

```text
libraries, or separate executable applications — we simply call each of those SBT projects modules. Each one is located
in the repository root. Check `build.sbt` for details. The `root` SBT project aggregates all the other projects. The
executable application is in the `app` SBT project.
```

with:

```text
libraries, or separate executable applications — we simply call each of those SBT projects modules. Each one lives in
`<group>/<id>/`, in a grouping folder at the repository root that holds the modules of one area and nothing else: no
`src/`, `README.md`, `CLAUDE.md` or `docs/` of its own. The *Packages* table of
[`module-overview.md`](../architecture/module-overview.md) gives each module's directory. Check `build.sbt` for
details. The `root` SBT project, the only one at the repository root, aggregates all the other projects. The
executable application is in the `app` SBT project.
```

and replace:

```text
**Convention — project ID equals base directory name.** Every project sets `.withId(<base-directory-name>)` in
`build.sbt`, so its SBT project ID (used in `sbt "<id>/compile"` / `"<id>/test"`, in `thisProject.value.id`, and thus in
`coverageDataDir`) always equals its directory. IDs may be kebab-case (`sc-midi`, `config`, `common-test-utils`). The
`build.sbt` `lazy val` name follows the `<camelCase-dir-name>Module` convention (e.g. `scMidiModule` for ID `sc-midi`);
it is only used for `.dependsOn`/`.aggregate`. Keep `.withId` and the directory in lockstep when adding or renaming a
module — tooling such as the `scoverage-inspector` MCP relies on the ID being the source directory. See the CONVENTION
comment in `build.sbt`.
```

with:

```text
**Convention — project ID equals the name of its folder.** Every project sets `.withId(<folder-name>)` in
`build.sbt`, so its SBT project ID (used in `sbt "<id>/compile"` / `"<id>/test"`, in `thisProject.value.id`, and thus
in `coverageDataDir`) always equals the name of its own folder, the leaf of `<group>/<id>/`. IDs may be kebab-case
(`sc-midi`, `config`, `common-test-utils`). The `build.sbt` `lazy val` name follows the `<camelCaseId>Module`
convention (e.g. `scMidiModule` for ID `sc-midi`); it is only used for `.dependsOn`/`.aggregate`. Keep `.withId` and
the folder name in lockstep when adding or renaming a module — tooling such as the `scoverage-inspector` MCP relies on
the ID naming the source directory, `<group>/<id>/src/`. See the CONVENTION comment in `build.sbt`.
```

In `docs/development/coverage.md`, replace `equal to the module's base directory name (e.g.` with
`equal to the name of the module's folder (e.g.`.

In `docs/development/doc-conventions.md`, replace `` | Architecture docs | `<module>/docs/ARCHITECTURE.md`, `` with
`` | Architecture docs | `<group>/<module>/docs/ARCHITECTURE.md`, ``, and `` e.g. `tuner/docs/mpe-tuner-paper.md` ``
with `` e.g. `tuner/tuner/docs/mpe-tuner-paper.md` ``.

In `docs/development/claude-code-setup.md`, replace:

```text
architecture doc lives (`<module>/docs/ARCHITECTURE.md`, or `docs/architecture/` for what spans modules), what it
holds, how to draw its flows in Mermaid, and how to keep it within its budget of 1000 words, splitting it when needed.
Its bundled script checks the budget,
```

with:

```text
architecture doc lives (`<group>/<module>/docs/ARCHITECTURE.md`, or `docs/architecture/` for what spans modules),
what it holds, how to draw its flows in Mermaid, and how to keep it within its budget of 1000 words, splitting it when
needed. Its bundled script checks the budget,
```

In `docs/agents/dev-stack.md`, replace:

```text
2. **Confirm `sbtn` routes correctly**: `sbtn 'show tuner/target'` must print a path ending in `tuner/target-bsp`. If
   it prints `tuner/target`, `sbtn` reached another sbt server — investigate before continuing.
```

with:

```text
2. **Confirm `sbtn` routes correctly**: `sbtn 'show tuner/target'` must print a path ending in
   `tuner/tuner/target-bsp`. If it prints `tuner/tuner/target`, `sbtn` reached another sbt server — investigate before
   continuing.
```

In `.claude/skills/architecture-docs/SKILL.md`, replace:

```text
| A module's architecture | `<module>/docs/ARCHITECTURE.md` |
| A topic or paper of one module | `<module>/docs/<topic>.md` |
```

with:

```text
| A module's architecture | `<group>/<module>/docs/ARCHITECTURE.md` |
| A topic or paper of one module | `<group>/<module>/docs/<topic>.md` |
```

and replace:

```text
Split a doc that is over budget and covers several capabilities. Move one capability to `<module>/docs/<topic>.md`,
which has the same budget. The module's `ARCHITECTURE.md` keeps one sentence summarising it and a link to it.
`docs/architecture/README.md` lists, under *Other architecture material*, only docs that span modules, and the papers.
```

with:

```text
Split a doc that is over budget and covers several capabilities. Move one capability to
`<group>/<module>/docs/<topic>.md`, which has the same budget. The module's `ARCHITECTURE.md` keeps one sentence
summarising it and a link to it. `docs/architecture/README.md` lists, under *Other architecture material*, only docs
that span modules, and the papers.
```

- [ ] **Step 7: Fix the paths in `MpeTuner`'s ScalaDoc**

In `tuner/tuner/src/main/scala/org/calinburloiu/music/microtonalist/tuner/MpeTuner.scala`, replace:

```scala
 * It follows the MPE Configuration Messages and Pitch Bend Sensitivity RPNs it receives. Its internal flow is outlined
 * in `tuner/docs/mpe-tuner.md`, and its design in `tuner/docs/mpe-tuner-paper.md`, which the comments in this file cite
 * by section name.
```

with:

```scala
 * It follows the MPE Configuration Messages and Pitch Bend Sensitivity RPNs it receives. Its internal flow is outlined
 * in `tuner/tuner/docs/mpe-tuner.md`, and its design in `tuner/tuner/docs/mpe-tuner-paper.md`, which the comments in
 * this file cite by section name.
```

`MidiProcessor`'s ScalaDoc names `docs/architecture/midi-device-lifecycle.md`, which doesn't move: leave it.

- [ ] **Step 8: Check the links and the size budget**

```bash
python3 $SCRATCH/check_md_links.py .
python3 .claude/skills/architecture-docs/scripts/check_arch_doc_size.py
```

Expected: `0 broken of 241 relative link(s) and import(s)`, three more than the baseline: the new link to
`module-overview.md` in `AGENTS.md`, counted again through its `CLAUDE.md` symlink, and the one in `build.md`. The size
script lists the 20 docs and exits 0.

- [ ] **Step 9: Grep every tracked file for old module paths**

```bash
git grep -nE '(^|[^A-Za-z0-9_./-])(app|cli|config|experiments|businessync|common|common-test-utils|format|sc-midi|rig|composition|intonation|tuner|ui)/(src|docs|target|README\.md|CLAUDE\.md)' -- ':!issues/' ':!docs/agent-evals/'
```

Expected: one line only, `docs/agents/dev-stack.md:28`, whose `tuner/target` is the sbt key `show tuner/target`, not a
path. Fix any other line it prints.

- [ ] **Step 10: Compile the ScalaDoc change and commit**

Run `mcp__metals__compile-module` with `module = "tuner"`. Expected: success.

```bash
git add AGENTS.md README.md docs .claude/skills/architecture-docs/SKILL.md app base midi rig theory tuner ui
git status --short
git commit -m "[#366/#367] Point the docs at the grouping folders" -m "<attribution lines>"
```

Expected before the commit: only the files listed under this task's **Files** are staged.

---

### Task 7: Final checks

**Files:**
- Modify: whatever `sbtn fixLint` reformats (expected: at most `MpeTuner.scala`'s ScalaDoc)

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Full test suite**

```bash
sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"
python3 -m unittest discover -s .claude/skills/architecture-docs/scripts/tests -p "test_*.py"
python3 -m unittest discover -s .claude/mcp/scoverage_inspector/tests
python3 -m unittest discover -s bin/tests -p "test_*.py"
```

Expected: all pass. Every module moved, so the full suite is also each modified module's suite.

- [ ] **Step 2: Lint**

Lint comes before coverage, so that a reformatted file doesn't leave a stale report behind.

```bash
sbtn fixLint
git status --short
```

Expected: `fixLint` passes. If it reformatted a file, commit it:
`git commit -am "[#366/#367] Format the code" -m "<attribution lines>"`.

- [ ] **Step 3: Coverage**

Invoke the `scoverage-inspector` skill. No code changed, so every module keeps its coverage and its floor:

```bash
git diff doc/modularization-proposal -- build.sbt | grep -E '^[-+] *(coverageSettings|coverageExcludedFiles|coverageEnabled)'
bin/mtlist-coverage-all
```

Expected: the `grep` prints nothing (no coverage setting changed); `coverageAll` passes every module's floor and the
aggregate's.

- [ ] **Step 4: The scoverage inspector reports modules in grouping folders**

Read the reports with the CLI, which loads Task 4's code (or reload the MCP server with `/mcp` first):

```bash
grep -o 'source="[^"]*"' coverage-reports/tuner/scoverage-report/scoverage.xml | head -1
bin/mtlist-scoverage-inspector freshness tuner
bin/mtlist-scoverage-inspector module-summary tuner --overall-only
bin/mtlist-scoverage-inspector module-summary tuner --aggregate --overall-only
```

Expected: a source path containing `/tuner/tuner/src/main/scala/`; `fresh: tuner`; two summaries with the module's
statement and branch percentages and a non-zero class count.

- [ ] **Step 5: Links, size budget and old paths**

Rerun Task 6's steps 8 and 9. Expected: the same results.

- [ ] **Step 6: No grouping folder has a `src/`, or anything but its modules**

```bash
for g in app base format midi rig theory tuner ui; do echo "$g: $(ls -A "$g" | tr '\n' ' ')"; done
find . -mindepth 2 -maxdepth 2 -name src -type d | wc -l
```

Expected: the listing of Task 2, step 6, and `0`.

- [ ] **Step 7: `bin/mtlist` finds the JAR that `assembly` builds**

A plain `sbt` writes to `target/`, apart from the stack's `target-bsp/`; without a server it doesn't take the stack's
socket. The fake `java` only checks that its JAR argument exists, so the GUI doesn't start.

```bash
sbt -Dsbt.server.autostart=false "app/assembly" "cli/assembly" < /dev/null
mkdir -p "$SCRATCH/fake-java"
printf '#!/bin/bash\nif [ -f "$2" ]; then echo "found $2"; else echo "missing $2"; exit 1; fi\n' \
  > "$SCRATCH/fake-java/java"
chmod +x "$SCRATCH/fake-java/java"
PATH="$SCRATCH/fake-java:$PATH" bin/mtlist
PATH="$SCRATCH/fake-java:$PATH" bin/mtlist-tool
```

Expected: `found …/bin/../app/app/target/scala-<version>/microtonalist-app.jar` and
`found …/bin/../app/cli/target/scala-<version>/microtonalist-cli.jar`, with the Scala version of
`project/scala-version`.

- [ ] **Step 8: Hand over**

Report the results of the steps above. Ask the user whether to open the PR, with the `contributing` skill:

```bash
.claude/skills/contributing/scripts/microtonalist-gh pr 366/367 "Move every module into a grouping folder" "<body>"
```

The body says what moved, that the sbt IDs didn't change, and that an IDE (e.g. IntelliJ) must re-import the sbt
project. The design merges each step's PR onto `main`; if `doc/modularization-proposal` hasn't merged by then, retarget
the PR at it right after opening it (`gh pr edit <number> --base doc/modularization-proposal`), so that it shows only
this step's commits.
