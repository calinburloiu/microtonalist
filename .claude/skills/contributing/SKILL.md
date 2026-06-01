---
name: contributing
description: >-
  Use when creating a GitHub issue, starting a branch, opening a pull request, or
  finishing work and deciding whether an issue or PR is needed. Covers applying the
  repository's labels, assigning the microtonalist GitHub project (Projects v2, via
  the `gh` CLI fallback since the GitHub MCP can't do it), and setting a milestone.
---

# Contributing (issues, PRs, branches)

GitHub conventions for the microtonalist repository. Follow these whenever you create
an issue, start a branch, or open a pull request.

## Tooling

Use the **GitHub MCP plugin** (`mcp__plugin_github_github__*`) for all GitHub operations
(issues, PRs, labels, milestones, etc.). Fall back to the `gh` CLI (`/usr/local/bin/gh`)
only for features not available in the MCP, such as managing GitHub Projects (v2).

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

The label in the branch name determines the label to apply to the corresponding issue and PR.

## Issues

When creating a new issue:

- **Always** add the issue to the **microtonalist** GitHub project (Projects v2). The GitHub
  MCP does not support Projects v2, so use the `gh` CLI:
  ```bash
  gh project item-add 1 --owner calinburloiu --url https://github.com/calinburloiu/microtonalist/issues/<issue_number>
  ```
- Add the appropriate label (inferred from the branch name if available).
- Check existing milestones (`mcp__plugin_github_github__list_releases` or similar). If a
  milestone name matches the scope of the new work, suggest adding it to the user before
  assigning.

## Pull Requests

When creating a new pull request:

- **Title format:** `[#<issue_number>] <Short description>` (e.g. `[#151] Add ScProgramChangeMidiMessage`).
- **Body:** Include `Resolves #<issue_number>` to auto-close the linked issue on merge.
- **Draft state:** Always open new PRs as **draft**.
- **Project:** Assign the **microtonalist** GitHub project.
- **Label:** Use the same label as the linked issue.
- **Milestone:** Use the same milestone as the linked issue, if one is set.
