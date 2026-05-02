# A/B Test: scoverage-inspector skill vs no skill

## Setup

- **Model**: claude-opus-4-7
- **Prompt**: identical in both runs (non-interactive `claude -p`)
- **With-skill**: branch `feature/class-coverage-skill` (skill present at `.claude/skills/scoverage-inspector/`)
- **Without-skill**: branch `main` (no skill)
- **Existing reports**: `coverage-reports/intonation/` and `coverage-reports/tuner/` were fresh in both runs; neither agent ran `sbt`

## Prompt

```
Check the test coverage of `org.calinburloiu.music.intonation.Scale` and
`org.calinburloiu.music.microtonalist.tuner.MpeTuner`. For each class, report
the overall statement coverage % and branch coverage %, and list which source
line numbers in that class are not covered by tests.

Constraints:
- Do NOT run any sbt commands. Only inspect existing reports under coverage-reports/.
- Be concise: just the numbers and uncovered lines per class. No prose explanations.
```

## Results

| Metric | With skill | Without skill | Ratio (without/with) |
|---|---:|---:|---:|
| Cost (USD) | **$0.229** | $0.420 | 1.83× |
| Duration | **17.7 s** | 55.9 s | 3.16× |
| Turns | **5** | 10 | 2.0× |
| Output tokens | **959** | 3,093 | 3.23× |
| Cache-read input tokens | **91,305** | 358,411 | 3.93× |
| Cache-creation input tokens | 25,474 | 26,039 | ~1× |

Both runs returned the same numbers and equivalent uncovered-line lists.

## Analysis

The biggest driver is **cache-read input tokens**: without the skill the agent
took twice as many turns and re-streamed the large `scoverage.xml` files
(~3,000–7,000 lines each) into context across iterations. The skill's
`class_summary.py` / `class_uncovered_lines.py` scripts emit a few hundred
bytes instead, so each turn carries far less context.
