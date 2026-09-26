# Copyright 2026 Calin-Andrei Burloiu
#
#    Licensed under the Apache License, Version 2.0 (the "License");
#    you may not use this file except in compliance with the License.
#    You may obtain a copy of the License at
#
#        http://www.apache.org/licenses/LICENSE-2.0
#
#    Unless required by applicable law or agreed to in writing, software
#    distributed under the License is distributed on an "AS IS" BASIS,
#    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#    See the License for the specific language governing permissions and
#    limitations under the License.

"""Reports the LLM judge's verdict on the drafted release notes, recorded in judge.json (see `bin/judge-prompt` and
graders/rubrics/release-notes.md), as a skillgrade result: the weighted mean of the criteria scores.

skillgrade's own `llm_rubric` grader needs a provider API key and sees only the agent's final message, not the notes
file, so the judge runs outside skillgrade."""

import json

from common import read

WEIGHTS = {"accuracy": 0.3, "audience": 0.2, "known_issues": 0.2, "style": 0.2, "concision": 0.1}

raw = read("judge.json")
if raw is None:
    print(json.dumps({"score": 0.0, "details": "judge.json missing: the trial was not judged"}))
else:
    verdict = json.loads(raw)
    scores = {c["name"]: max(0.0, min(1.0, float(c.get("score", 0)))) for c in verdict.get("criteria", [])}
    score = sum(WEIGHTS[name] * scores.get(name, 0.0) for name in WEIGHTS)
    print(json.dumps({
        "score": round(score, 4),
        "details": verdict.get("reasoning", ""),
        "checks": [{"name": c["name"], "passed": scores[c["name"]] >= 0.5,
                    "message": f"{scores[c['name']]:.2f} {c.get('comment', '')}"}
                   for c in verdict.get("criteria", []) if c["name"] in scores],
    }))
