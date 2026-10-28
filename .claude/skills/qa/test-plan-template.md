# <Feature> test plan

- **Date:** <YYYY-MM-DD>
- **Based on commit:** `<short SHA>`
- **Issue:** #<n>. Design: [`<date>-design.md`](<date>-design.md).

Steps are in user terms, and expected results are what a user or an instrument can observe: what the window shows, what
the instrument receives in cents per pitch class, a log without errors. No tool commands.

## Scope

<What the plan tests, from the design's acceptance criteria.>

Out of scope: <what it leaves out, and why>.

## Fixtures

<The `qa` skill's: the QA composition, with C Just Major, C Romanian Minor and C Septimal, and its rig, from the
input `keys` through the pedal tuning changer and an MTS octave 1-byte non-real-time tuner to the output `synth`. Or
feature-specific ones next to this plan, each with what it holds.>

## Scenarios

Each has an ID, a priority, `must` or `should`, and Given/When/Then steps. A check that only a human can do, such as
"sounds in tune on the piano", is tagged `human`; the agent reports it as `not run`.

### TP-01 <Title> (must)

Given <the starting state>
When <what the user or the instrument does>
Then <what the window shows>
And <what the output receives>
And the log has no ERROR

### TP-03 The pedal changes the selected tuning (must)

Given the QA composition is open with "C Just Major" selected
When the input receives Sostenuto pressed, then released
Then the tuning list selects "C Romanian Minor"
And the output receives one MTS message: C 0, D +4, E♭ +16, F♯ −17, G +2, A +6, B♭ −4 cents, other keys 0
And the log has no ERROR

### TP-<nn> <Title> (should, human)

Given <…>
When <…>
Then <what only a human can check>

## Exploration

Time box: <n> minutes in all.

- <An area to explore beyond the scenarios, e.g. fast repeated input, a pedal during a click, a dialog left open while
  MIDI arrives.>
