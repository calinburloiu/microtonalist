# Prompt for bug-fixing and improving the scoverage-inspector skill

Let's use /skill-creator:skill-creator to fix some issues and improve `scoverage-inspect` skill.

There are three main area of improvement described in the sections below.

You should know that the scoverage integration into this project faced some issues that caused occasional and
non-deterministic failure of the sbt coverage commands. File `docs/development/scoverage-issue.md` contains more
details.

## Skill subagent failure handling

The unstaged changes from `SKILL.md` already sketch this changes, but we need to generalize and improve them.

Sometimes, if the subagent runs into an issue, such as that described in `docs/development/scoverage-issue.md`, it will
attempt to fix the problem by modifying code and doing workarounds, or it will try to resolve the problem in a different
way causing token to be wasted. The subagent should be read-only (except for logs) and report errors back to the main
agent if any. This should happen for any issues, with sbt commands, with the Python scripts or with coverage numbers
that
suspiciously show 0% when it doesn't make sense. It may report something suspicious but should not try to fix it. The
point is, it's a Haiku subagent which doesn't have right skills for this.

The skill subagent should only attempt to retry an sbt coverage command once and **only** if it recognizes it as being a
manifestation of the issue described in `docs/development/scoverage-issue.md`. I am not sure if it's a good idea for the
subagent to refence this file, it's long, and it's not included in the skill files. We can just do a minimal one phrase
training for the agent to recognize the issue like seeing a failure about TASTy files. Each attempt to run the sbt
coverage file should have a different log file (it may use a 1-based number that is incremented). This allows an
investigation of all failures that occured during an skill subagent run and could help improve it.

When it reports back to the main agent it should mention the log files of the sbt command runs.

## Controlling the output of the scripts

Some scripts output too much information for some use cases:

- `module_summary.py` outputs both the module percentages and coverage information about each class. If the user only
  wants module coverage percentages, the coverage information about each class is irrelevant and waists tokens. Consider
  adding a flag, or some other solution, to disable coverage information about each class when not necessary.
- `class_summary.py` outputs both the class coverage percentages and coverage information about each method. If the user
  only wants class coverage percentages, the coverage information about each method is irrelevant and waists tokens.
  Consider adding a flag, or some other solution, to disable coverage information about each method when not necessary.

## Updating the project rules and documentation for coverage

In @AGENTS.md, move the section that describes how to run coverage manually, via the commands from
`project/Coverage.scala`, to a dedicated document that can be used by both agents and humans, in
`docs/development/coverage.md`. You may duplicate the general principles of the project about coverage in that file.

The "Coverage" section from @AGENTS.md should only include:

- The general principles of the project about coverage.
- The fact that it should use the `scoverage-inspector` skill for coverage inquiries.
- A reference to the coverage issue from `docs/development/scoverage-issue.md`. This should help the main agent identify
  such an issue if the skill subagent reports a failure. If such a failure occurs it stop trying to compute coverage
  wait for the user input.
- A reference to `docs/development/coverage.md` for more information if it's required.
