# Coverage (Agents)

This document contains the coverage policy a coding agent must apply whenever it changes code.

For coverage inquiries use the `scoverage-inspector` skill which helps with the following:
- branch and statement coverage percentages for classes, modules or the aggregated coverage for the whole project
- finding gaps (uncovered lines)
- verifying that a module still meets its threshold.
Use the `scoverage-inspector` skill rather than running `sbt coverage…` by hand. For the manual `sbt coverageAll` /
`sbt coverageModules` workflow and CI's `coverageCheck`, see [`../development/coverage.md`](../development/coverage.md).

Code coverage is measured via [scoverage](https://github.com/scoverage/sbt-scoverage). Each SBT project has per-module
statement and branch thresholds configured in `build.sbt` via the `coverageSettings` helper.

The target for this project is **80% statement and branch coverage**. This applies for every module, as well as for the
project-wide aggregated coverage. Modules that have not yet reached 80% are configured with their current coverage minus
_a 3% buffer_ and an open issue tracking the work needed to reach 80%.

**Per-module coverage must never decrease below the configured threshold.** When changing code in a module:

- The threshold in `build.sbt` is a floor, not a target. It can stay flat or be raised toward 80%, but never lowered.
- If your change reduces coverage below the configured threshold, add tests so it stays at or above the threshold.
- If your change raises coverage, you may raise the threshold in `build.sbt` accordingly, but keep the 3% buffer. Once
  both statement and branch reach 80%, switch the module to `coverageSettings(stmt = 80, branch = 80)` and close the
  tracking issue.
- **New files must always meet the 80% statement and branch coverage target on their own**, regardless of the module's
  current threshold. The per-module floor exists to track legacy code paying down toward 80%; it is not a license for
  newly authored code to ship under-tested.

Coverage runs occasionally fail with TASTy / companion-class errors due to a known sbt-scoverage + Scala 3 concurrency
issue documented in [`../development/scoverage-issue.md`](../development/scoverage-issue.md). If the
`scoverage-inspector` skill reports such a failure, **stop and wait for user input** rather than retrying or modifying
code.
