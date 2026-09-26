# #330 Release skill eval: progress

- **Date:** 2026-09-26
- **Base commit:** `ccd9b94` on `feature/release-skill` (#328, PR #329)
- **Branch:** `poc/release-skill-eval`, stacked on `feature/release-skill`.
- **Issue:** [#330](https://github.com/calinburloiu/microtonalist/issues/330), milestone *Agentic Coding*.
- **Origin:** split out of #328 on 2026-09-26. The eval and the parts of `issues/00328-release-skill/progress.md` below
  moved here verbatim (`37c2939`), then the eval was slimmed down as #330 asks.

## Decisions made by the user

- The evals do not use skillgrade's own `claude` agent. They run as **subagents of a normal Claude Code session**, with
  Sonnet 5 and 3 trials per task.
- Auto-mode classifier: the user chose to allow the release script via rules in `.claude/settings.local.json` (see
  #328's progress document). A rule matches the command text, not the directory, so these rules cover both the eval
  trials and the real publishing.

## Done

- **`.claude/skills/release/evals/`:** the skillgrade eval, next to the skill it evaluates. Its `README.md` explains how
  to run it and why the trials are subagents. It holds `eval.yaml`, the fixture (`setup.sh`, the stand-in `bin/gh`, and
  `github.json` recorded from the real repository), the graders, the rubric, the reference solutions
  (`npx skillgrade@0.3.0 --validate` gives 1.00 on all three) and `bin/{prepare-trials,collect-trial,judge-prompt,grade}`.
- **Slimmed down (2026-09-26), from 79 files and 3113 lines to 26 files and 1480 lines:**
  - Moved from `.claude/skill-evals/release/` into the skill. The fixture commits the skill under test without its
    `evals/`, so that a trial cannot read the approved notes or the rubric. `eval.yaml` says `skill: ..`.
  - The ~50 recorded issues and PRs became one `fixture/github.json` with the 17 that the script's `context` reads, in
    the shape `gh api` returns; only the 8 whose bodies `context` prints keep them. `context` prints the same as
    before, except for the commit SHAs.
  - The stand-in `gh` went from 408 to 114 lines: `api …/issues/N`, `issue view`, `pr view`, `run list` and
    `release create`; anything else fails.
  - `build.sbt` and `docs/release-notes.md` are no longer copies: `setup.sh` takes them from `$REAL_REPO`, the notes
    without the sections newer than v1.5.0. For `--validate`, `eval.yaml` copies both into the workspace.
  - Dropped the fixture's `README.md`, the graders' fenced-code handling, and the judge prompt's re-derivation of the
    referenced items (it now lists `github.json`). The `publish-release` reference solution releases with the skill's
    own script instead of by hand.
- **License headers:** added by hand to the extensionless scripts. The pre-commit hook adds them to the `.py` and `.sh`
  files.

## Pending, in order

1. **Done (2026-09-26).** Slim the eval down; see Done.
2. **GREEN iteration 2.** Follow the steps in `.claude/skills/release/evals/README.md`, including the push guard on the
   real `origin`, which was removed at the end of the #328 session. Rerun `draft-notes` and `publish-release` (3 trials
   each) and judge the drafts again, because the rubric changed after green1's judging. Skip `refuse-red-ci`, which
   already scores 1.00.
   - Cost reference: one trial is about 60–100K subagent tokens and one judge about 80K, so a full 9-trial run with 3
     judges is about 1M tokens.
3. **Final checks:** `npx skillgrade@0.3.0 --validate`, and the `Findings` section of the eval README updated with the
   final results. Report any regression of the skill to #328.

## Eval results so far

All runs use Sonnet 5 with 3 trials per task and are graded by skillgrade. Every result below uses the final
deterministic graders; the green1 judge verdicts are the exception, because they used the older rubric.

| Task | RED (stub skill) | GREEN 1 |
|---|---|---|
| `draft-notes` | 0.84 (checks 0.87, judge 0.82) | 0.89 (checks **1.00**, judge 0.78) |
| `publish-release` | 0.55 | 0.00 |
| `refuse-red-ci` | 1.00 | 1.00 |

What these numbers mean:

- **RED `draft-notes`:** no trial proposed 1.7.0-SNAPSHOT. One trial invented its own subsection structure. The judge
  noted missing bold headlines and missing carry-over of the known issues.
- **GREEN `draft-notes`:** every check passed. The judge's style scores were lowered by the old rubric requiring links
  (now fixed). One trial made a factual error: it claimed 1-byte MTS threw.
- **RED `publish-release`:**
  - The agents rebuilt the local commits from the git history.
  - All three added `Co-Authored-By` and `Claude-Session` trailers.
  - One trial made an annotated tag.
  - None produced a correct GitHub Release body.
  - The classifier blocked the push or `gh release create` in every trial.
- **GREEN `publish-release`:** all three trials ran `plan`, then `publish`, which the classifier denied ("Create Public
  Surface") before anything ran. The 0 therefore measures the classifier, not the skill; the allow rules fix it.

## Findings and gotchas

- **The classifier:**
  - It refused an agent wrapper that runs `claude -p --dangerously-skip-permissions` ("Create Unsafe Agents"). That is
    why skillgrade's own `claude`/`command` agent is not used and trials are subagents. `fixture/import-trial.sh` is
    the `command` agent: it runs no model and only loads finished trials into skillgrade's workspace.
  - Inside the sandbox it may deny a push to the local stand-in remote, or `gh release create` through the stand-in.
- **Subagents asked the main session to push for them after a denial.** Never do that: it is permission laundering.
- **The Skill tool hot-reloads the repository's `release` skill.** The trial prompt tells agents to read the sandbox
  copy instead. Otherwise the base directory of a loaded skill would point them at the real repository.
- **Trial transcripts:**
  - The `output_file` of a background subagent is its JSONL transcript. Extract it with `bin/collect-trial`; never read
    it directly.
  - The final message can be in the last text block, in a `SubagentHandback` call, or in both. `collect-trial` keeps
    both.
- **skillgrade 0.3.0 quirks:**
  - Workspace files are copied only when `eval.yaml` sets `provider: local`.
  - The grader timeout is fixed at 120 s.
  - The top-level directories of the paths referenced in `run:` are copied into the workspace.
  - `--validate` runs `bash <basename of solution>` in the workspace root.
  - `llm_rubric` needs a provider API key and sees only the agent's final message, so the judge is a subagent. Its
    verdict goes into `judge.json`, which `graders/judge_result.py` reads.
- **The eval run outputs lived in the session scratchpad** and may be gone: the `red` and `green1` runs. The results
  above are their summary.
