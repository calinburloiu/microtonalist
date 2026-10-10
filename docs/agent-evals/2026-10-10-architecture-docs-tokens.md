# Architecture docs and agent token usage

- **Date:** 2026-10-10
- **Commit:** `1a21550c` (branch `doc/doc-conventions`, #359)
- **Models:** `claude-sonnet-5-5` and `claude-opus-5-5`, run through Claude Code 2.1.296

## Summary

Do the architecture docs make an agent spend fewer tokens resolving an issue? Not reliably.

| | Sonnet | Opus |
| --- | ---: | ---: |
| A, the bug: token change with docs | −38% | +15% |
| B, the feature: token change with docs | +16% | +11% |
| Cost change with docs | +8% | +3% |

- **Why:** the docs make every API call's context bigger, so they pay off only when they save calls.
  - They saved calls once: Sonnet on the bug, 4 calls instead of 7.
  - Opus read the code just as thoroughly with the docs as without them.
- **Cost:** never lower with the docs. The tokens they save are cache reads, the cheapest kind.
- **Quality:** the docs didn't improve it.
  - Every plan found the bug's root cause and covered the feature.
  - The fix for the bug was correct in 2 of 4 control plans and in 1 of 4 treatment plans.

## Method

For each model, 8 headless sessions (`claude -p --model sonnet|opus`) ran at the same time: 2 challenges × 2 groups × 2
runs.

- **Treatment** ran in a snapshot of the commit as it is.
- **Control** ran in the same snapshot without:
  - `docs/architecture/`;
  - the 12 module `CLAUDE.md` files, which only import those docs;
  - the `architecture-docs` skill;
  - the text in `AGENTS.md` and `docs/development/doc-conventions.md` that imports or points to the docs.

Both groups ran under the same conditions:

- **Repository:** each snapshot was a fresh single-commit repository at a new path. Git history couldn't reveal the
  removed docs, and no auto-memory loaded.
- **Tools:** no MCP servers (`--strict-mcp-config`), so no Metals. Agents had `Read`, `Grep`, `Glob` and read-only
  `Bash`. `Edit`, `Write`, `Agent`, `AskUserQuestion` and the web tools were disallowed. The user's settings, plugins and
  hooks loaded as usual.
- **Output:** a plan of at most 400 words instead of code, so there was no edit, compile or test loop.

A pilot run confirmed that control agents saw no architecture docs and treatment agents did.

**Metric.** Total tokens are uncached input plus cache writes, cache reads and output, summed over every API call of a
session. They come from the `modelUsage` field of the session's final `result` event. Cost, API calls and duration are
reported too.

**Quality.** The plans were graded against the rubrics below, shuffled and without group labels. Lines that named the
architecture docs were masked, since only treatment agents could know about them.

### Prompts

Both prompts start with: "Work non-interactively and don't modify any files. Skip the Warm-up and Metals steps of
CLAUDE.md; sbt is unavailable."

**A: tunings get the wrong names** (a bug, open issue #362):

> A user reports: "My composition has several tunings that use the same scale file, and I give each tuning its own
> `name`. When I load the composition, all of those tunings show the same name. If the first one has no `name`, a later
> named tuning shows the scale file's own name instead of mine." Find the root cause and plan the fix as if you were
> about to implement it. Your final message is the plan, at most 400 words: the root cause (file and line), the change
> to make, and the regression test to add.

**B: switch tunings with Program Change** (a feature):

> Feature request: some MIDI foot controllers can only send Program Change messages, not Control Changes. Let users
> switch tunings with Program Change messages, configured in the tracks file like the existing pedal-based tuning
> change. Program numbers map to previous, next or a specific tuning index, and users can choose whether the trigger
> messages pass through to the output or are filtered out. Plan the change end to end as if you were about to implement
> it. Your final message is the plan, at most 400 words: every file to add or change (production code, tests and
> anything else) and what changes in each.

### Rubrics

**A:**

- **Root cause:** `CompositionRepr.loadDeferredData` caches scales by resolved URI only, but each tuning loads its scale
  with a `ScaleFormatContext` that carries its own name.
- **Fix:** the tuning's name must still reach the scale parser, because a `.jscl` scale with no `name` of its own takes
  it from the context, and parsing fails without one. Keying the cache by URI and context satisfies this.
  - Caching the scale loaded without a name, then renaming each tuning's copy, breaks a named tuning whose `.jscl` file
    has no name.
  - The rubric first accepted that fix too. One Opus plan pointed out the problem, and all plans were then regraded
    against this stricter rubric.
- **Regression test:** it checks the names of tunings that share a URI.

**B:**

- **Changer:** a new `TuningChanger` that matches `ProgramChangeMidiMsg`, reuses `TuningChangeTriggers[Int]` and
  honours `triggersThru`.
- **Format:** it is registered in `JsonTuningChangerPluginFormat`, with program numbers checked to be 0–127.
- **Schema:** `json-schemas/v1/track/tuningChanger.schema.json` is updated.
- **Tests:** in both `tuner` and `format`.
- **Accuracy:** every file, type and method the plan names exists.

## Results

### Means

| | Sonnet control | Sonnet treatment | Diff. | Opus control | Opus treatment | Diff. |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| A: total tokens | 254,516 | 156,802 | −38% | 515,849 | 594,587 | +15% |
| A: API calls | 7 | 4 | −3 | 10.5 | 11 | +0.5 |
| A: cost (USD) | 0.210 | 0.209 | 0% | 0.646 | 0.661 | +2% |
| B: total tokens | 248,648 | 289,010 | +16% | 914,082 | 1,012,294 | +11% |
| B: API calls | 6 | 6 | 0 | 15 | 16.5 | +1.5 |
| B: cost (USD) | 0.258 | 0.297 | +15% | 0.977 | 1.004 | +3% |
| Both: total tokens | 251,582 | 222,906 | −11% | 714,965 | 803,440 | +12% |
| Both: cost (USD) | 0.234 | 0.253 | +8% | 0.811 | 0.833 | +3% |

### Per run

Uncached input was 8–40 tokens per run, so it is left out.

| Run | Total tokens | Cache writes | Cache reads | Output | API calls | Cost (USD) | Seconds |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Sonnet A control 1 | 272,309 | 44,412 | 225,040 | 2,843 | 7 | 0.229 | 28 |
| Sonnet A control 2 | 236,722 | 35,767 | 198,155 | 2,786 | 7 | 0.191 | 27 |
| Sonnet A treatment 1 | 151,010 | 42,782 | 105,659 | 2,561 | 4 | 0.207 | 22 |
| Sonnet A treatment 2 | 162,594 | 44,488 | 115,920 | 2,178 | 4 | 0.211 | 21 |
| Sonnet B control 1 | 243,031 | 49,180 | 189,967 | 3,872 | 6 | 0.254 | 34 |
| Sonnet B control 2 | 254,265 | 50,905 | 199,504 | 3,844 | 6 | 0.262 | 32 |
| Sonnet B treatment 1 | 283,356 | 57,136 | 222,297 | 3,911 | 6 | 0.290 | 33 |
| Sonnet B treatment 2 | 294,664 | 60,389 | 230,413 | 3,850 | 6 | 0.303 | 32 |
| Opus A control 1 | 537,682 | 54,367 | 475,959 | 7,334 | 11 | 0.677 | 79 |
| Opus A control 2 | 494,015 | 50,851 | 437,135 | 6,009 | 10 | 0.614 | 65 |
| Opus A treatment 1 | 559,818 | 60,035 | 494,540 | 5,223 | 10 | 0.684 | 67 |
| Opus A treatment 2 | 629,355 | 50,414 | 572,900 | 6,017 | 12 | 0.638 | 70 |
| Opus B control 1 | 715,964 | 66,685 | 640,208 | 9,045 | 13 | 0.843 | 95 |
| Opus B control 2 | 1,112,199 | 84,127 | 1,016,279 | 11,759 | 17 | 1.112 | 125 |
| Opus B treatment 1 | 1,199,265 | 70,608 | 1,117,362 | 11,255 | 20 | 1.014 | 122 |
| Opus B treatment 2 | 825,323 | 79,973 | 734,915 | 10,409 | 13 | 0.995 | 134 |

### Quality

All 16 plans found the bug's root cause and covered every rubric item of the feature.

**A, the fix:** 5 of the 8 plans chose the regressing fix. The other 3 keyed the cache by URI and context.

| | Control | Treatment |
| --- | --- | --- |
| Sonnet | 1 of 2 correct | 1 of 2 correct |
| Opus | 1 of 2 correct | 0 of 2 correct |

- **Explicit reasoning:** only one plan, Opus control 1, worked out why the name must reach the parser. The other correct
  plans chose the key without that reason.
- **Other errors:**
  - Sonnet control 1 also claimed a second bug, a scale file's own `name` overriding the tuning's, but
    `DefaultScaleRepo` renames the scale after parsing, which rules it out.
  - Sonnet control 2 assumed, wrongly, that `Scale` has no rename method.
- **The format doc as a reason:** it says "each distinct scale URI is fetched once". One Opus treatment plan cited that
  sentence as still holding after its regressing fix. The doc says the context renames scales, but not that it supplies
  a missing name.

**B:** all 8 plans covered every rubric item. All four treatment plans also updated the architecture docs.

**Length:** the plans ran 406–546 words (counted by whitespace, Markdown included), so the 400-word limit held only
loosely in both groups.

## Analysis

Every API call re-reads the whole context, mostly from the cache. So a session's total tokens are roughly its number of
API calls times its context size.

- **The docs make each call bigger.**
  - The always-loaded overviews added 3.6k tokens from the first call on, with both models.
  - A module's architecture doc also loads automatically, through its `CLAUDE.md`, once the agent reads a file in that
    module with `Read`.
  - By the last call of Sonnet's B runs, treatment contexts were 57–60k tokens and control contexts 49–51k.
- **The docs pay off only by saving calls, and they rarely did.**
  - Sonnet on A went straight to `CompositionRepr` with the docs: 4 calls instead of 7.
  - On B, the existing `PedalTuningChanger` is an obvious grep target, so the docs saved nothing.
  - Opus made 10–20 calls per session and read the code just as thoroughly with the docs as without them.
- **How the docs reached the agents:**
  - **Sonnet:** no agent opened an architecture doc on purpose; all of it came through the imports and module
    `CLAUDE.md` files. One plan cited a heading from `docs/architecture/tuner/README.md`, a file its agent never opened.
  - **Opus:** both B treatment agents also opened module docs with `Read`. One opened `docs/architecture/tuner/README.md`
    after reading tuner sources, by which point the same text had most likely loaded already.
- **Saving tokens didn't save money.** The tokens saved were cache reads, the cheapest kind, while the docs' extra
  context was written to the cache at a higher rate. No cell cost less with the docs.

## Limitations

- **Sample size:** two runs per cell.
  - Opus's B runs varied from 716k to 1.2M tokens within a group, which is more than the difference between groups.
  - Sonnet's differences exceed the spread within each cell, but two runs can't rule out chance.
- **Small tasks:** each run took 21–134 seconds and 4–20 API calls. Resolving a real issue takes many more calls, which
  multiplies both the docs' per-call overhead and any calls they save.
- **Plans only:** there was no edit, compile or test loop, and no Metals.
- **Cache:** all runs of a model started at once, so each wrote its own prompt cache. In everyday use, sessions share
  cached prefixes, which lowers the cost of cache writes.

## Reproducing

1. Clone the commit twice and reinitialize each clone as a single-commit repository.
2. Strip the control clone as described in [Method](#method).
3. Run each prompt from each clone:

   ```bash
   claude -p "$(cat prompt.txt)" --model sonnet --output-format stream-json --verbose \
     --strict-mcp-config --no-session-persistence \
     --allowedTools Read Grep Glob "Bash(ls:*)" "Bash(find:*)" "Bash(cat:*)" "Bash(head:*)" "Bash(tail:*)" \
       "Bash(sed -n:*)" "Bash(wc:*)" "Bash(rg:*)" "Bash(grep:*)" "Bash(tree:*)" "Bash(git log:*)" \
       "Bash(git show:*)" "Bash(git grep:*)" "Bash(git blame:*)" "Bash(rtk:*)" \
     --disallowedTools Edit Write NotebookEdit AskUserQuestion Agent WebFetch WebSearch \
     < /dev/null > run.jsonl
   ```
