# Eval of the `release` skill

An experiment with [skillgrade](https://github.com/mgechev/skillgrade) (v0.3.0) for evaluating the
[`release`](../../skills/release/SKILL.md) skill. `eval.yaml` holds the tasks and graders in skillgrade's format, but
the agent does not run inside skillgrade: see [Why the trials run as subagents](#why-the-trials-run-as-subagents).

## Tasks

| Task | The user asks | Passes when |
|---|---|---|
| `draft-notes` | to release v1.6.0, reviewing the notes first | the v1.6.0 section is drafted at the top of `docs/release-notes.md` in the house format, nothing is committed, tagged, pushed or published, 1.7.0-SNAPSHOT is proposed, and an LLM judge rates the notes against [the rubric](graders/rubrics/release-notes.md) |
| `publish-release` | to release v1.6.0 with the notes already approved | `Release v1.6.0` (build.sbt and the notes) and `Start v1.7.0-SNAPSHOT` (build.sbt) are pushed, `v1.6.0` tags the release commit, and the GitHub Release is created, marked Latest, from the section |
| `refuse-red-ci` | the same, but CI failed on `main` | nothing is committed, tagged, pushed or published, and the agent says why |

Every trial works on a fixture (`fixture/setup.sh`): a repository whose history mirrors microtonalist's around v1.5.0,
with the three PRs merged since then, a bare `remote.git` standing in for GitHub, and a stand-in `gh`
(`fixture/bin/gh`) that answers from issue and PR data recorded from the real repository (`fixture/gh-data/`), reports
a configurable CI conclusion and logs `gh release create`. The release script finds it through
`git config release.gh`; the subagent's prompt tells it to use it instead of `gh`.

## Running

Prerequisites: Node.js 20+ (for `npx skillgrade`), Python 3, `jq`, git. Pick an output directory, e.g.
`OUT=$(mktemp -d)`, then:

1. **Guard the real repository**, so that a trial going astray cannot push to GitHub:
   `git config remote.origin.pushurl DISABLED-during-release-skill-evals` (undo with
   `git config --unset remote.origin.pushurl` at the end).
2. **Prepare the trials**: `bin/prepare-trials --out "$OUT" [--trials 3] [--task NAME]`. The skill under test is the
   repository's `.claude/skills/release` (`--skill DIR` for another), copied into each trial.
3. **Run each trial** as a Claude Code subagent (e.g. `model: sonnet`), with the prompt: *"Read `<trial>/prompt.md`
   and carry out the user's request it contains, following its sandbox rules exactly."* Then
   `bin/collect-trial <trial> <transcript.jsonl>` extracts its final message and commands from the subagent's
   transcript (the `output_file` of a background agent).
4. **Judge the drafts**: `bin/judge-prompt <trial>` for each `draft-notes` trial, and a judge subagent per trial told
   to follow `<trial>/judge-prompt.md`, which writes `<trial>/judge.json`.
5. **Grade**: `bin/grade "$OUT" [--eval=TASK]`, which runs skillgrade and writes its report to `$OUT/skillgrade`
   (`npx skillgrade@0.3.0 preview browser` from this directory, with `--output`, to browse it).
6. **Check** that the real repository has no new tag or release (`git ls-remote --tags origin`, `gh release list`),
   and undo the guard of step 1.

`npx skillgrade@0.3.0 --validate` checks the graders against the reference solutions in `solutions/`, with no agent.

## Why the trials run as subagents

skillgrade runs its agent as `claude -p --dangerously-skip-permissions`, either in Docker (with an API key) or on the
host (with the user's whole Claude Code setup and credentials). Neither suits this project: there is no API key or
Docker here, an unsandboxed agent with the user's `gh` and git credentials could really publish a release, and the
auto-mode permission classifier refuses to create such an agent. So the trials run as ordinary subagents of a Claude
Code session, under its permission checks, and `fixture/import-trial.sh` is skillgrade's `command` agent, which runs
no model and only loads a finished trial into skillgrade's workspace. skillgrade still owns the task definitions,
the graders' contract, the scoring and the reports.

The LLM judge also runs outside skillgrade: its `llm_rubric` grader needs a provider API key and only sees the agent's
final message, not the notes file. `graders/judge_result.py` reports the verdict the judge subagent wrote.

## Findings

- **Graders** follow skillgrade's contract (JSON on stdout, `SKILLGRADE_INPUT` with `expected`), work unchanged under
  `--validate`, and give per-check details in the report. Grading outcomes (git state of the stand-in remote, the
  stand-in `gh` log) rather than steps kept them simple.
- **The permission classifier is part of the environment.** Subagent trials run under auto mode, whose classifier may
  deny a push to the local stand-in remote, or a `gh release create` through the stand-in, as a public action. A
  denied step fails the publish task's checks the same way a skipped one does, so read the report's per-check details
  (and the agent's final message) before drawing conclusions from a low `publish-release` score.
