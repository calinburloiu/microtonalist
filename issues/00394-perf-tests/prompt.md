We are working on issue #394. We want to build a performance test infrastructure for measuring end-to-end MIDI latency, and to add the test cases specified in the issue for it. Use /superpowers:brainstorming . Work in the current branch that I created for this.

1. Test cases are expressed as Scala code in test files in `src/test/scala/`. For measuring MIDI latency that means that we can use sc-midi.
2. The test suite launches the app in a different process, sends MIDI messages via a configured IAC bus and receives them via another one.
3. A test case input/output:
    - Given: an input file from resources
    - When: a particular sequence of MIDI messages is sent to the app's input
    - Then: it expects a particular sequence of output MIDI messages.
4. Measuring metrics for a test case: both the input and the output sequence of MIDI messages is split into one or more series of MIDI messages which can be marked. A test case specifies desired pairs of input and output marks which are called metrics. Then, the infrastructure measures that metric as the delta between those marks.
5. Output messages must be asserted to exceed the expectation. In this way we make sure that we identify the right marked series.
6. The infrastructure must perform a warm up to ensure that the JVM JIT compiler kicks in.
7. Every test case must run multiple times to ensure statistical significance.
8. For each delta measured we compute the probability distribution (which will be plotted), average, standard deviation, median, and various other percentiles (90th, 95th, 99th).
9. The measurements are recorded in JSON files which are committed in git for history and tracking. They include:
    - Date / time
    - The machine details because the delta depends on its performance. This may contain info to identify it and performance characteristics which may include RAM, CPU, sound adaptor/card, OS. We should be able to uniquely identify a machine across different runs performed in different days.
    - The commit Sha.
    - The version.
    - JDK version 
    - Scala version
    - Room for extending with other things in the future.
10. Generate an HTML report to explore metrics values. For each show:
    - A timeline with the evolution of a metric in time, across runs. For example, you can see how the median or 99th percentile evolved across app versions, assuming we had a run for each version.
    - Details for a metric in a run: the plot of the probability distribution, various measurents for the metric like average, median, 99th percentile etc.
    - If a metric significantly drifted since the last measurement (maybe based on standard deviation if it's a normal distribution).
11. Not sure if the HTML report should be static. Maybe it's better for it to be able to dynamically read the JSON files. We should aim for a single page with JavaScript and no server logic if we go with this path, all logic should be on the frontend side.
