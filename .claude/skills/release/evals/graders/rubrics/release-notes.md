# Rubric: drafted release notes

You are grading the release notes an agent drafted for a new Microtonalist release. Compare the **draft** with the
**ground truth** (the commits since the previous release, and the recorded pull requests and issues they reference) and
with the **previous release's notes**, which set the house style. Score each criterion from 0.0 to 1.0.

## Criteria

- `accuracy` — Every statement is supported by the ground truth: no invented features, fixes or behaviour, no wrong
  issue or PR numbers, nothing claimed fixed that the PRs say remains. 1.0: fully supported. 0.5: one noticeable error
  or unsupported claim. 0.0: several, or the draft misrepresents the release.
- `audience` — Changes are split by who notices them. *User-facing*: what a performer or composer experiences (an
  output device turned off and on again keeps its tuning; the first note after a reset is in tune; a tuning beyond a
  tuner's limits is clamped with a warning instead of failing). *Developer-facing*: code, API, tests, build and agent
  tooling (the `Tuner` contract — `onTune`/`onReset`, `canTune`, `final` `tune`/`reset`; the Metals MCP agent
  instructions; test doubles and coverage). 1.0: every item in the right section and every significant change of the
  ground truth covered. Deduct for misplaced items and for significant changes left out.
- `known_issues` — The *Known issues* section lists limitations that remain after this release and are still open in
  the ground truth, with their issue numbers, described from the user's point of view where they are user-facing (for
  example the remaining work of #305, or the check at load time of #326). It lists no closed issue as open. 1.0:
  relevant, correct and useful. 0.0: missing, empty or wrong.
- `style` — Consistent with the previous release's notes: a short intro paragraph that names the headline change(s) in
  bold; the subsections `### User-facing changes`, `### Developer-facing changes` and `### Known issues`; bullets that
  open with a bold lead-in; issue references in parentheses, citing the issues a change resolves rather than its PR;
  plain, precise wording. References may be bare `#N` or Markdown links to
  `https://github.com/calinburloiu/microtonalist/issues/<n>`: publishing turns bare ones into links, so do not
  deduct for either.
- `concision` — Reads as release notes, not as a dump of PR descriptions: each bullet says what changed and why it
  matters, in one to three sentences; no implementation minutiae that only a reviewer of the PR would need.

## Output

Answer with a single JSON object and nothing else:

```json
{
  "criteria": [
    {"name": "accuracy", "score": 0.0, "comment": "one sentence"},
    {"name": "audience", "score": 0.0, "comment": "one sentence"},
    {"name": "known_issues", "score": 0.0, "comment": "one sentence"},
    {"name": "style", "score": 0.0, "comment": "one sentence"},
    {"name": "concision", "score": 0.0, "comment": "one sentence"}
  ],
  "reasoning": "two or three sentences on the draft as a whole"
}
```

If there is no draft, score every criterion 0.0.
