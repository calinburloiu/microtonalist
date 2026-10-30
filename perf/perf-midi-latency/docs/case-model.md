# The model of a latency case

A latency case tells the suite what to send to the app, what to expect back and which latency to measure. The types
are in `PerfCase.scala`, and the cases are written in their language, `PerfDsl`; how to write one is in
[`docs/development/performance-tests.md`](../../../docs/development/performance-tests.md#write-a-case).

## Case and scenario

A **`PerfCase`** is a scenario played on a rig of the app. The rig comes from two resources of the suite: a tracks
template, whose `{{input.name}}`-style variables the suite fills with the configured devices, and a composition. The
tracks template picks the tuner and the tuning changer, so the suite itself knows nothing about tuners.

A **`Scenario`** is what the suite does on that rig:

- its **`Readiness`**: when the launched app is ready for the iterations to start, e.g. once a probe note played comes
  back;
- its **`Step`**s: the steps of one *iteration*, which the suite repeats many times;
- its **`Metric`**s: the latencies it measures in each iteration.

The suite launches the app several times for each case. Each *launch* waits for readiness, then runs the warm-up
iterations, which let the JIT compiler work and record nothing, then the timed iterations, which record one sample of
each metric. Since the same steps repeat with the same expected output, **an iteration must leave the app as it found
it**: a case that changes the tuning on a pedal changes it back on another, and one that presses a note releases it.

## Steps and series

A **`Step`** sends one or more **`InputSeries`**, then waits until each of its **`OutputSeries`** has fully arrived,
before the next step starts. Steps are thus the synchronisation points of an iteration: the suite never sends a step's
messages while the app still answers the previous one.

- An **input series** is messages the suite sends back to back, e.g. the Control Change pair of a pedal press and
  release. Its *send time* is when its first message is sent.
- An **output series** is the messages the suite expects in this order, each an **`Expected`**: exactly a message, or
  any message that a pattern matches, e.g. a Note On of E4 on any channel, for a tuner that picks the channel. Its
  *receive time* is when its last message arrives. A step's output series may arrive interleaved, since the suite
  imposes no order among them.

A missing, extra or different message fails the case at once, with the expected and received messages. Thus each case
also checks that the app's output is right.

## Marks and metrics

A series may carry a **mark**, a name unique among the series of its direction. A **`Metric`** names an input mark,
`from`, and an output mark, `to`; its sample in a timed iteration is the receive time of the `to` series minus the send
time of the `from` series. The two series may be in the same step or in different steps, as long as the output's step
doesn't come before the input's. The unmarked series are there to keep the iteration valid and the app as it found it,
e.g. the release of a note, or the pedal that changes back to the first tuning.

The *measured step* is the first one that sends a metric's `from` series. The suite starts it on a pace: in the warm-up,
one that keeps each device under its configured maximum rate of messages; in the timed iterations, the configured
pace, which spaces the samples.

## Example

The MTS tuner's tuning change, from the pedal press to the SysEx that retunes the output:

```scala
perfCase(MtsTracks, TwoTunings, readyAfterPlaying(E4))(
  input("pedal")(pedal*) ~> output("tuning")(pythagoreanSysEx),
  input(pedalBack*) ~> output(justSysEx)
)(metric("pedal", "tuning"))
```

An iteration has two steps. The first sends the pedal's press and release, marked `pedal`, and waits for the SysEx of
the second tuning, marked `tuning`. The second presses the other pedal, which changes back to the first tuning, and
waits for its SysEx. The metric `pedal→tuning` is the latency of the first step.
