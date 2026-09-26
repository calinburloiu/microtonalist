# #328 Release skill: progress

- **Date:** 2026-09-26
- **Base commit:** `af1096d` on `main`
- **Branch:** `feature/release-skill`. All the work below is committed on this branch, whose draft PR is
  [#329](https://github.com/calinburloiu/microtonalist/pull/329).
- **Issue:** [#328](https://github.com/calinburloiu/microtonalist/issues/328), milestone *Agentic Coding*.
- **Split (2026-09-26):** the skillgrade eval of the skill moved to
  [#330](https://github.com/calinburloiu/microtonalist/issues/330), on the branch `poc/release-skill-eval`, stacked on
  this one. Its progress, results and findings are in `issues/00330-release-skill-eval/progress.md` on that branch.

## Decisions made by the user

- The release notes go in `docs/release-notes.md`, newest first. They are backfilled with v1.0.0 to v1.5.0 and committed
  **inside** the `Release vX.Y.Z` commit.
- Issue references in the file are Markdown links to `https://github.com/calinburloiu/microtonalist/issues/N`. Drafts
  may use bare `#N`, because `publish` converts them to links.
- The work lands as issue #328 plus a draft PR (#329). The skillgrade eval lands separately, as #330 and a PR stacked
  on #329.
- Auto-mode classifier: the user chose to allow the release script via rules in `.claude/settings.local.json`. See
  Pending step 1.

## Done

- **`.claude/skills/release/SKILL.md`:** the Plan → Draft → Review → Publish steps and the notes format.
- **`.claude/skills/release/scripts/microtonalist_release.py`:** the `plan`, `context`, `publish` and `github-release`
  commands.
  - `plan` checks that the working tree is clean and on `main`, in sync with `origin`, that CI is green on HEAD, that
    the tag is new and that the version comes after the latest release.
  - `publish` dates and linkifies the notes section, creates the two commits (exact messages, no trailers) and a
    lightweight tag, runs `git push --atomic`, and runs `gh release create --latest --verify-tag` with the section's
    headings raised one level.
  - The GitHub CLI is `git config release.gh`, or `gh` when that is unset.
  - Tests: `scripts/tests/test_microtonalist_release.py`, 43 tests, all green. Run them with
    `python3 -m unittest discover -s .claude/skills/release/scripts/tests -p "test_*.py"`.
- **`docs/release-notes.md`:** backfilled from the GitHub Releases. The script's `linkify` leaves the file unchanged.
- **Pointers to the skill:** a line in `AGENTS.md`, and a `release` skill section in
  `docs/development/claude-code-setup.md`.

## Pending, in order

1. **Done (2026-09-26, approved by the user in accept-edits mode).** Allow rules for the script. The classifier had
   denied this edit to `.claude/settings.local.json` as "Auto-Mode Bypass". A rule matches the command text, not the
   directory, so these rules cover both the eval trials and the real publishing. The file is local and not committed.
   Rules added to `permissions.allow`:
   ```json
   "Bash(python3 .claude/skills/release/scripts/microtonalist_release.py publish *)",
   "Bash(python3 .claude/skills/release/scripts/microtonalist_release.py github-release *)"
   ```
2. **Done (2026-09-26, approved by the user).** SKILL.md template edit. The classifier had denied it as
   "Self-Modification". In the notes template,
   replace `One or two sentences naming the headline change(s) in **bold**.` with
   `This release brings **hot plugging of MIDI devices**. (One or two sentences; the headline change(s) in bold.)`.
   The reason: 2 of 3 drafts written with the skill did not bold their headline change.
3. **Done (2026-09-26).** Draft PR #329 opened.
4. **The user reviews #329.** Iterate on the skill from the review, and from #330's eval if it finds a regression.
5. **Final checks:** the Python tests. Then mark #329 ready for review.
6. The first real release, v1.6.0, happens in a separate session.

## Findings and gotchas

- **The script is a black box for skill users.** SKILL.md says to invoke it from its usage or `--help`, not to read its
  source.
