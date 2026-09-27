---
name: release
description: >-
  Use when the user asks to release, cut, tag or publish a new microtonalist version (e.g. "release v1.6.0"), to draft
  the release notes of an upcoming version, or to start the next -SNAPSHOT development version after a release.
---

# Releasing microtonalist

A release of `X.Y.Z` is, directly on `main` (no PR):

1. commit `Release vX.Y.Z`: `ThisBuild / version` in `build.sbt` set to `X.Y.Z`, plus the new section of
   `docs/release-notes.md`; tagged `vX.Y.Z`;
2. commit `Start vN.N.N-SNAPSHOT`: `build.sbt` set to the next development version — the one the user gives, else the
   next minor (1.6.0 → 1.7.0-SNAPSHOT), or the SNAPSHOT `build.sbt` was at, if higher (a patch such as 1.5.1, released
   while at 2.0.0-SNAPSHOT, goes back to 2.0.0-SNAPSHOT);
3. both pushed with the tag, and the GitHub Release `vX.Y.Z`, marked Latest, with the same notes.

You draft the notes and get the user's approval twice: of the notes, then of the local commits and tag, before
anything is pushed. The bundled script does the checks and every mechanical step. **Invoke it from the usage here (or
`--help`); do not read its source.** From the repository root:

```bash
python3 .claude/skills/release/scripts/microtonalist_release.py plan X.Y.Z [--next N.N.N]    # checks, changes nothing
python3 .claude/skills/release/scripts/microtonalist_release.py context                      # input for the notes
python3 .claude/skills/release/scripts/microtonalist_release.py commit X.Y.Z [--next N.N.N]  # commits and tag, locally
python3 .claude/skills/release/scripts/microtonalist_release.py publish X.Y.Z                # push, GitHub Release
python3 .claude/skills/release/scripts/microtonalist_release.py github-release X.Y.Z         # finish a failed publish
```

## Steps

1. **Plan.** Run `plan X.Y.Z`, with `--next` when the user named the next version. It checks that `main` is clean
   (except for `docs/release-notes.md`), in sync with `origin`, green in CI, and that `vX.Y.Z` is new and follows the
   latest release. If it prints an error, stop: tell the user what failed and what would fix it. If it prints a
   warning (e.g. a version that skips releases), relay it in your next message to the user.
2. **Draft.** Run `context`. If the top section of `docs/release-notes.md` is already `## vX.Y.Z`, that is the draft:
   keep it, but point out in the review anything from `context` that it does not cover (e.g. a PR merged after it was
   written). Otherwise read `context` together with the previous release's section of `docs/release-notes.md`, then
   write the new section at the top of `docs/release-notes.md`, above the previous release, in the format below.
   Commit nothing.
3. **Review the notes.** If the user has already approved these notes, go on to Commit. Otherwise end your turn with a
   message that contains, in order: the drafted section; the plan — the two commit messages with the next `-SNAPSHOT`
   version, the tag, and the GitHub Release; a request to approve or change them.
4. **Commit.** Once the user approves the notes, run `commit X.Y.Z` (with the same `--next`). It re-runs the checks,
   dates the section, links bare `#N` references, makes both commits and the tag, and pushes nothing. Then end your
   turn — always, even if the user approved the whole release earlier, because pushing and publishing cannot be undone
   — with a message that contains, in order: the commits and the tag, as `commit` printed them; what Publish will do:
   push `main` and the tag to `origin`, and create the GitHub Release, marked Latest, from the committed notes; a
   request to inspect them (e.g. `git show vX.Y.Z`) and approve. If the user asks for changes instead, run the undo
   command that `commit` printed, which keeps the notes, make the changes, and do this step again.
5. **Publish.** Once the user approves the commits and the tag, run `publish X.Y.Z`. It checks that they are the ones
   `commit` made, right on top of `origin/main`, pushes `main` and the tag, and creates the GitHub Release. Report the
   release URL.

If a command fails, relay its message, which says what was done and how to finish or undo it.

## Release notes format

```markdown
## vX.Y.Z

This release brings **hot plugging of MIDI devices**. (One or two sentences; the headline change(s) in bold.)

### User-facing changes

- **What changed, for someone who plays or composes** (#N, #M). Why it matters, in one to three sentences.
- **Bug fixes:**
  - What was wrong, in the user's words (#N).

### Developer-facing changes

- **Code, API, build, tests or agent tooling** (#N). One to three sentences.

### Known issues

- A limitation that remains after this release, from the user's or developer's point of view (#N).
```

- **Sources.** Every item comes from `context`: the PR descriptions and issues. Say what changed and why it matters,
  not how it is implemented, and check each claim against the PR description.
- **References.** Cite the issues a change resolves (the `[#N]` of its commit, the `Resolves #N` of its PR); for a
  `[#parent/#child]` commit, that is the child, which the PR resolves. Cite the PR only for a change without an issue.
- **Audience.** Where the user, a PR description or an issue says whether a change is user-facing or
  developer-facing, or that it belongs in the notes at all, follow it.
- **Known issues** are open issues only: the previous release's known issues that `context` shows still open, and the
  remaining work that the PRs of this release name (e.g. "Not in this PR", `TODO #N`).
- References may be bare `#N`; `commit` turns them into links and dates the heading. Put any other `#N` in a code
  span, such as MIDI's `CC #74`, or it becomes a link to an issue. Match the wording and density of the previous
  release.
A release should usually fit in a single screen, and the notes should be readable in a terminal or on GitHub.
