# Contributing to Microtonalist

Thanks for your interest in contributing! Microtonalist is a microtuner application written in Scala 3 and built with
sbt 1. This guide covers the GitHub conventions for the project — labels, branches, issues, and pull requests — plus
pointers to the development setup and coding standards.

> These are the same conventions coding agents follow (see the `contributing` skill in
> [`.claude/skills/contributing/`](.claude/skills/contributing/SKILL.md)), written here for human developers.

## Getting set up

Before writing code, set up your environment and read the standards:

- [Development Setup Guide](docs/development/README.md) — prerequisites (JDK 23, Scala 3, sbt 1), building, and testing.
- [Build reference](docs/development/build.md) — compiling and assembling the fat JAR.
- [Test reference](docs/development/test.md) — running the suite.
- [Coding conventions](docs/development/coding-conventions.md) — general / production Scala conventions.
- [Test conventions](docs/development/test-conventions.md) — how tests are written (BDD, Given/When/Then, fixtures).
- [Coverage workflow](docs/development/coverage.md) — coverage thresholds and how they are checked in CI.
- [Architecture docs](docs/architecture/README.md) — module overview, domain concepts, and per-module deep dives.

Source files carry an Apache 2.0 license header that is added automatically. Enable the git hook once per clone so new
files get a header on commit (CI also enforces it):

```bash
git config core.hooksPath .githooks
```

See [License headers](docs/development/license-headers.md) for details.

## Labels

The following labels are used for issues and pull requests, and as branch-name prefixes:

| Label         | Use for                                                  |
|---------------|----------------------------------------------------------|
| `feature`     | a new capability or component                            |
| `bugfix`      | a fix for a defect                                       |
| `refactoring` | restructuring existing code without changing behavior    |
| `doc`         | documentation-only changes                               |
| `poc`         | proof of concept or experimental work                    |

## Branches

Branch names use the format `<label>/<kebab-case-description>`, where `<label>` is one of the labels above. Examples:

- `feature/mpe-tuner`
- `bugfix/pitch-bend-overflow`
- `refactoring/program-change-midi-msg-wrapper`
- `doc/contributing-guide`

The label in the branch name determines the label applied to the corresponding issue and pull request.

## Issues

When opening a new issue:

- **Add the appropriate label** from the table above (this matches your branch-name prefix, if you have one).
- **Add it to the "microtonalist" GitHub project** (Projects v2) so the work is tracked on the board. From the issue's
  sidebar in the web UI, set **Projects → microtonalist**.
- **Check the milestones.** If an existing milestone matches the scope of the work, assign it.

## Pull Requests

When opening a pull request:

- **Title format:** `[#<issue_number>] <Short description>` — e.g. `[#151] Add ScProgramChangeMidiMessage`.
- **Link the issue:** include `Resolves #<issue_number>` in the body so the issue auto-closes on merge.
- **Open as a draft** first, and mark it ready for review once it is complete and the full test suite passes.
- **Label:** use the same label as the linked issue.
- **Project:** assign the "microtonalist" GitHub project (same as for issues).
- **Milestone:** use the same milestone as the linked issue, if one is set.

Make sure CI is green (see [`.github/workflows/scala.yml`](.github/workflows/scala.yml)) before requesting review.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE.txt), the same license that covers this project.
