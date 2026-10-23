# Design review

We are reviewing the design doc `issues/00383-arch-threading-and-ui/2026-10-20-design.md`:

- Can we use Pekko’s priority mailboxes to give some messages a higher priority such that they get in front of the queue? I am particularly
interested in optimizing the round-trip from a pedal tuning change trigger from a Track Actor to Business Actor and
back to the Track Actor. If a `ChangeTuning` command takes precedence we can improve the total tuning change
latency.
- Businessync-related renames:
    - We may consider renaming the word *businessync* to something else. Initially, this was suppose to be a standalone threading library in the monorepo with a future plan to move outside of it. But I decided to simplify by using Apache Pekko and now the standalone library idea does not hold anymore. We can use a more intuitive while still short name and make its own package inside `org.calinburloiu.music.microtonalist`. Renaming will also help with the old class deletion, because we can create a completely new module with a different name and eventually remove the old `businessync` module together with its class.
    - Maybe rename `businessync-pekko` to `businessync-pekko-javafx` because it also adds support for running the UI Actor on JavaFX Application Thread.
    - Regarding `def send(command: Command): Unit` method, I am not convinced that `send` is the right name with respect to `command` and I don’t think it disambiguates from `publish`. Maybe rename it to `execute`, or do you have a better idea?
- **1. The messaging layer**
    - Is there an alternative for `Businessync`'s `onComplete`.
        - (a) The `Future`s today use the global `ExecutionContext`. Can we force the `Future` result to somehow arrive
        on the desired thread (Business Thread or UI Thread). If we can do that somehow, then, we don't need that
        `onComplete` method.
        - (b) Alternatively, can we instead use some kind of extension method such that we can chain on the `Future`
        with a method that gets a callback with the result? E.g. `flatMapOnBusiness` or `mapOnUi` etc.
    - “`send` throws `IllegalStateException` on the caller's thread when none or several match” → If we also validate on `handle` during registration for multiple handlers on the same class or a supertype, can `send` still find several matches?
    - `ask`: I don’t agree with the following limitation: “It throws when called on the Business or UI Thread, where awaiting its result would block, and where a result arrives as an event instead”. Using a `Future` does not necessarily mean that one was to await it. It’s true that it can do that, but it’s in their responsibility. And there might be cases when an event result is awkward because you don’t clearly see the correlation between the request and the response. And my understanding is that `ask` will probably allow sequential consistency in the future, so how does throwing when called from the UI Thread make that work?
    - We might also need something similar to `onComplete` on UI especially if we allow `ask` there which returns a `Future`.
    - “The test runtime”: a `test->test` dependency is no longer forbidden, issue #395 was resolved. So `ManualRuntime` no longer needs to be in main resources. Let’s put it in test sources and use a `test->test` dependency when is needed in another module.
- **3. Track threads**
    - In `RigMidiGraph`, “Closing several tracks”: Close tracks in topological order, closing in rig order is not always correct. Also reject cycles in this change, because cycles would crash the app and not resolving them would complicate the topological order. We can also close tracks that don’t wait for dependencies in parallel to optimize.
- **4. The JavaFX UI**
    - In “Shutdown” sub-section: it’s not clear what thread does this and how. Is it based on a `Future` chain, so it’s from the execution context thread pool, or some kind of dedicated thread. Add few details about this.
    - When writing the plan for the JavaFX UI, also create a test plan document. When implementing it, we will spawn an adhoc QA sub-agent that manually tests the GUI. See the details about the test plan document and the sub-agent in `issues/00397-gui-qa-agent/2026-10-22-gui-qa-in-process-guide.md`. Note that creating a skill for this is in a future follow-up issue (#397). When the adhoc QA sub-agent finishes testing, it should write a retrospective document that will be used when implementing the follow-up issue, the document should be in the skill issue directory, `issues/00397-gui-qa-agent/`.
