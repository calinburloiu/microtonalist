---
name: contributing
description: >-
  Use when creating a GitHub issue, starting a branch, opening a pull request, or
  finishing work and deciding whether an issue or PR is needed. Prefer the bundled
  `microtonalist-gh` script — one call applies the repository's labels, the microtonalist
  Projects-v2 assignment, the milestone, draft PRs, the `[#issue]` / `[#parent/#child]`
  title prefix, and the `@me` assignee — falling back to the GitHub MCP and `gh` CLI.
---

# Contributing (issues, PRs, branches)

GitHub conventions for the microtonalist repository. Follow these whenever you create
an issue, start a branch, or open a pull request.

## Tooling

### Scripts (primary path)

Prefer the bundled script for creating issues and PRs — it applies every convention below
in one non-interactive call (near-zero tokens), so you do not run the multi-step procedure
by hand. **Invoke it directly from the usage here (or `… --help`); do not read the script's
source.** It is a self-contained black box — reading its source only burns context and
defeats the point. Everything you need to call it is below.

```bash
.claude/skills/contributing/scripts/microtonalist-gh issue <title> [body] [--label L] [--milestone M] [--wip] [--dry-run]
.claude/skills/contributing/scripts/microtonalist-gh pr    [<issue-spec>] <title> [body] [--label L] [--milestone M] [--dry-run]
```

- `issue` creates the issue, applies its label (from `--label`, else the branch prefix),
  optionally sets `--milestone`, assigns you with `--wip`, and adds it to the project.
- `pr` pushes the branch and opens a **draft** PR assigned to you (`@me`). The optional
  leading `<issue-spec>` (`33`, or `32/33` for a sub-issue) links an issue: it derives the
  `[#<issue>]` / `[#<parent>/#<child>]` title prefix and the `Resolves #<issue>` body line,
  and inherits the issue's milestone. With no `<issue-spec>` the PR links no issue. The PR is
  always added to the project.
- `--dry-run` prints the resolved label/milestone/title/body and the exact `gh` commands
  without any side effects — use it to verify before creating. Run `… --help`, `… issue
  --help`, or `… pr --help` for the full usage.

```bash
.claude/skills/contributing/scripts/microtonalist-gh issue "Support Windows audio backend" --milestone "MPE" --wip
.claude/skills/contributing/scripts/microtonalist-gh pr 220 "Replace scoverage-inspector subagent with an MCP server"
```

Deciding **which** milestone to set is still your call: the script does not pick one. Check
the existing milestones, suggest a matching one to the user (see Issues / Pull Requests
below), then pass it via `--milestone`.

### Underlying behavior / fallback

When the script does not apply (an operation it does not cover, or it is unavailable), use
the **GitHub MCP plugin** (`mcp__plugin_github_github__*`) for GitHub operations, and the
`gh` CLI (`/usr/local/bin/gh`) for what the MCP cannot do — notably GitHub Projects (v2).
The sections below document the conventions the script encodes.

## Labels

The following labels are used for issues, PRs, and as branch name prefixes:

- `feature` — a capability or component
- `bugfix` — fix for a defect
- `refactoring` — restructuring existing code without changing behavior
- `doc` — documentation-only changes
- `poc` — proof of concept or experimental work

## Branches

Branch names use the format `<label>/<kebab-case-description>`, where `<label>` is one of
the labels above. Examples: `feature/mpe-tuner`, `bugfix/pitch-bend-overflow`,
`refactoring/program-change-midi-msg-wrapper`.

The label in the branch name determines the label to apply to the corresponding issue and PR
(this is what the script infers when `--label` is omitted).

## Sub-issues

When the work is on a **sub-issue**, always use the `[#<parent>/#<child>]` notation so both
the parent and the sub-issue stay visible. This applies to **PR titles and commit messages**.
The `pr` script applies it to PR titles automatically when you pass `<parent>/<child>` (e.g.
`32/33`, which always wins over auto-detection); writing commit messages with this prefix
stays your responsibility.

## Issue documents

Documents related to an issue (plans, specs, design documents, prompts, etc.) live under `issues/`,
in a subdirectory named with a **5-digit, zero-padded issue number** followed by a **short
kebab-case description** (words joined by hyphens), e.g. `issues/00220-scoverage-inspector-mcp/`. The description may reuse
the branch name's description (the part after `<label>/`) when that makes sense. Do not leave such
documents scattered under `docs/` — keep them with their issue.

## Issues

When creating a new issue (`microtonalist-gh issue` does all of this):

- Add the appropriate label (inferred from the branch name if available).
- **Always** add the issue to the **microtonalist** GitHub project (Projects v2). The GitHub
  MCP does not support Projects v2, so the underlying step uses the `gh` CLI:
  ```bash
  gh project item-add 1 --owner calinburloiu --url https://github.com/calinburloiu/microtonalist/issues/<issue_number>
  ```
- Check existing milestones (`mcp__plugin_github_github__list_releases` or similar). If a
  milestone name matches the scope of the new work, suggest adding it to the user before
  assigning (then pass it via `--milestone`).
- If the issue is being worked on in the current session (WIP), assign it to the current user
  (`@me`, via `--wip`).

## Pull Requests

When creating a new pull request (`microtonalist-gh pr` does all of this):

- **Title format:** `[#<issue_number>] <Short description>` (e.g. `[#151] Add ScProgramChangeMidiMessage`);
  for a sub-issue use `[#<parent>/#<child>]` (see Sub-issues).
- **Body:** Include `Resolves #<issue_number>` to auto-close the linked issue on merge.
- **Draft state:** Always open new PRs as **draft**.
- **Assignee:** Assign the PR to the current user (`@me`).
- **Project:** Assign the **microtonalist** GitHub project.
- **Label:** Use the same label as the linked issue (the branch prefix).
- **Milestone:** Use the same milestone as the linked issue, if one is set.
