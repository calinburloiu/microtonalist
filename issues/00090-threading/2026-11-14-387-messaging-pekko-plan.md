# Slice 2 (#387): the `messaging-pekko` module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-11-14
- **Based on commit:** `a00b233a` (`main`, with slice 1, #386, merged)
- **Issue:** #387, slice 2 of #383 (parent #90)

**Goal:** Add `messaging-pekko`, the Pekko Typed implementation of `messaging`'s `MessagingRuntime`. It provides pinned,
control-aware mailboxes with thread names and priorities, the UI mailbox on a configurable executor, startup, and
shutdown on `CoordinatedShutdown`. The tests of `MessageBus` and of the `Future` continuations become shared behaviors
that run on both runtimes. The module ships unused.

**Architecture:** `PekkoMessagingRuntime` spawns one actor per mailbox, as a system actor of an `ActorSystem`:
- a pinned mailbox runs on a `PinnedDispatcher`, and its actor's setup names its thread and sets its priority;
- the UI mailbox runs on a dispatcher whose executor the configuration names;
- every mailbox uses Pekko's `UnboundedControlAwareMailbox`, and an `Urgent` message travels in an envelope that
  extends `ControlMessage`.

The `MessageBusTest` and `FutureContinuationsTest` cases move into behavior traits. They run on a pluggable
`MessagingTestRuntime`: `ManualTestRuntime` in `messaging`, and `PekkoTestRuntime`, over an `ActorTestKit`, in
`messaging-pekko`.

**Tech Stack:** Scala 3.9, sbt 1, Pekko 1.7.1 (`pekko-actor-typed`, `pekko-slf4j`, `pekko-actor-testkit-typed`), ScalaTest
3 (`AnyWordSpec`), scala-logging, logback.

**Spec:** [`2026-10-20-design.md`](../00383-arch-threading-and-ui/2026-10-20-design.md): section 1's *The runtime
interface* and *The Pekko runtime*, and the slice 2 entry of *Delivery*.

## Global Constraints

- **The module.** `messaging-pekko` lives in folder `base/messaging-pekko/`, package
  `org.calinburloiu.music.microtonalist.messaging.pekko`. In sbt it is `lazy val messagingPekkoModule` with
  `.withId("messaging-pekko")`, right after `messagingModule`, both in `build.sbt` and in `root`'s `.aggregate`.
  - It depends on `messaging` with `"compile->compile;test->test"` and on `common` with `"test->test"`, for `LogCapture`.
  - Its libraries are `pekko-actor-typed`, `pekko-slf4j`, `jsr305`, and `pekko-actor-testkit-typed` in `Test`.
  - It has no JavaFX and no Guava, and no module depends on it yet: `app` will, in #388.
  - Its coverage is `coverageSettings(stmt = 80, branch = 80)`.
- **Versions.** Pekko is `1.7.1`, as `pekkoVersion` in `project/Dependencies.scala`. `com.typesafe:config` moves from
  `1.3.4` to Pekko's `1.4.9`, and the `config` module's tests must still pass with `ficus` on it.
- **Packaging.** `assemblySettings` already concatenates the `reference.conf`s. `app` puts this to use once it
  depends on the module (#388).
- **Names.** The Business mailbox and its thread are named `Business`, and a track's will be `Track-<id>`. The UI
  mailbox is named `UI` (`MessagingRuntime.UiMailboxName`), and its thread is the toolkit's.
- **Pekko types.** The public API of `PekkoMessagingRuntime` takes no Pekko type: `start(config)`, `startMailbox`,
  `startUiMailbox`, `addShutdownTask`, `shutdown()` and `ShutdownPhase`. Its constructor over an `ActorSystem` is
  `private[pekko]`, for the tests. `SingleThreadExecutorConfigurator` is public, since Pekko instantiates it from the
  configuration.
- **Code conventions:**
  - brace syntax; no `return` (use `boundary`); `// TODO #<issue>`;
  - contract-only ScalaDoc on every public identifier, test sources included;
  - every compiler warning is an error;
  - `.conf` files take no license header.
- **Test conventions:**
  - `AnyWordSpec` with `Matchers`, and no `if` around an assertion;
  - no `Thread.sleep`: a wait uses a latch or a `Future` with the named `PekkoTestSupport.Timeout`, so that only a
    failing case waits it out;
  - a Pekko-thread log is checked with `LogCapture.capturing(loggerName, isCaptured)`;
  - the test snippets below leave out the `// Given` / `// When` / `// Then` comments, which every test has.
- **Compiling and testing:**
  - Compile with `mcp__metals__compile-module` (`messaging-pekko`, or `messaging-pekko-test` for the tests).
  - After a change to `build.sbt` or `project/`, run `mcp__metals__import-build` first.
  - Run tests with `sbtn "<module>/testOnly <fully qualified class> -- -oNCXEHLOPQRMWS"`.
  - For Metals' symbol tools, use `messaging`, `messaging-test`, `messaging-pekko` or `messaging-pekko-test`, since
    `app` depends on neither module yet.
- **Commits.** Messages are `[#90/#387] <imperative sentence>`, ending with the attribution trailer that the session's
  instructions give. Stage only the task's files. Work on `feature/messaging-pekko`, the branch of this plan's draft PR.

## Decisions within the design

The design leaves these to the builder; this plan settles them.

1. **The runtime is built over an `ActorSystem`.** `PekkoMessagingRuntime.start(config)` initialises SLF4J, starts
   the `ActorSystem` named `microtonalist` and builds the runtime on it. The tests build it on their `ActorTestKit`'s
   system, so a case costs only its mailboxes' threads, not an `ActorSystem`.
2. **Mailbox actors are system actors**, spawned with `systemActorOf` and named `mailbox-<n>` from a counter, since
   every bus in a test suite starts a `Business` mailbox in the same system. The mailbox's name goes to its thread
   only.
3. **Thread name and priority (design's first option).** The pinned actor's `Behaviors.setup` runs on its pinned
   thread, and sets the thread's name and priority there. `startMailbox` checks the priority on the caller's thread
   and throws `IllegalArgumentException` when it's outside `MIN_PRIORITY..MAX_PRIORITY`.
4. **Envelopes.** A normal message is sent as it is, so our code allocates nothing on the note path. An `Urgent` one
   travels in `UrgentEnvelope(message) extends ControlMessage`, which typed `tell` hands to the classic mailbox
   unwrapped.
5. **Stopping.**
   - `stop()` sets a volatile flag, then sends an internal `Stop` on the normal lane, behind the queued messages; the
     actor then stops, and `PostStop` completes the `Future`. The flag makes `enqueue` drop later messages before
     they reach Pekko.
   - `stop()` returns the same `Future` every time.
   - A message whose `enqueue` races `stop()` may still pass the flag and reach Pekko's dead letters, which log it,
     harmlessly. #391 settles MIDI after `Close`.
6. **Errors.** A non-fatal exception that escapes a handler is logged at ERROR as `A message of mailbox <name>
   failed.`, with the exception, and the mailbox goes on. So Pekko's supervision never stops a mailbox, even with a
   faulty handler.
7. **The UI mailbox.**
   - It runs on dispatcher `microtonalist.messaging.ui-dispatcher`, with `throughput = 1`.
   - Its default executor is `SingleThreadExecutorConfigurator`, a thread of the runtime's own, for an app without a
     toolkit and for the tests. Without that default, Pekko would fall back to its default dispatcher's pool, whose
     threads change between messages. `app` overrides it: with Swing in #388, then with JavaFX in #393.
   - `startUiMailbox` waits up to `UiStartTimeout` (10 s) for the actor's setup to record its thread. If the setup
     hasn't run by then, e.g. because the call ran on that very thread, it throws `IllegalStateException`.
8. **Shutdown.**
   - `addShutdownTask(phase, name)(task)` adds a task to one of two `ShutdownPhase`s: `FinishWork` (Pekko's
     `service-requests-done`), e.g. closing the rig, and `ReleaseResources` (`service-stop`), e.g. closing the
     `MidiManager`.
   - In `before-actor-system-terminate`, the runtime stops the pinned mailboxes still running, the last started first,
     each once the one after it has stopped: the tracks', then the Business mailbox. Pekko terminates the system in
     `actor-system-terminate`, which ends the UI mailbox.
   - `shutdown()` runs the sequence, or returns the run in progress, and its `Future` completes after termination.
     Pekko's JVM shutdown hook stays on in production; the tests' configuration turns it off.
9. **Configuration.** `messaging-pekko`'s `reference.conf` holds the dispatchers and the mailbox under
   `microtonalist.messaging`, and routes Pekko's logging through SLF4J (`pekko-slf4j`). The tests load
   `application-test.conf`.
10. **The shared behaviors.** These follow the design the user approved in the planning session:
    - `MessagingTestRuntime` gives `runtime`, `awaitIdle()`, `onToolkit` and `capturingLogs`;
    - the behavior traits run with `behave like`, and `MessagingFixtures`'s `withFixture` closes each case's test
      runtime;
    - the records are thread-safe, and no case races the test thread against a mailbox;
    - three queue-inspecting cases stay Manual-only.

## Review Focus

1. **A mailbox name that isn't a valid actor name**, e.g. `Track-Piano 1/2`: the mailbox starts, and its thread
   carries the name verbatim (Task 2).
2. **Two buses in one `ActorSystem`**, each starting a `Business` mailbox: both start, each on a thread of its own
   (Task 2).
3. **A priority outside `Thread.MIN_PRIORITY..Thread.MAX_PRIORITY`**: `startMailbox` throws
   `IllegalArgumentException` on the caller's thread, instead of the actor failing silently on its own thread (Task 2).
4. **A handler exception that escapes the bus's own catch**: it's logged, and the mailbox runs its next message
   (Task 3).
5. **`startUiMailbox` called on the UI dispatcher's own thread**: it throws `IllegalStateException` after its start
   timeout, instead of hanging (Task 6).

## File Structure

`M` = `org/calinburloiu/music/microtonalist/messaging`, `P` = `M/pekko`.

| File | Responsibility |
| ---- | -------------- |
| `base/messaging/src/test/scala/M/MessagingTestRuntime.scala` | Create: the pluggable test runtime, and `causingCommandId` |
| `base/messaging/src/test/scala/M/ManualTestRuntime.scala` | Create: the test runtime over `ManualMessagingRuntime`, with today's toolkit-thread wrapper |
| `base/messaging/src/test/scala/M/SynchronizedBuffer.scala` | Create: a buffer that several threads append to |
| `base/messaging/src/test/scala/M/MessagingFixtures.scala` | Create: `Fixture`, the per-case teardown, and the test messages |
| `base/messaging/src/test/scala/M/MessageBusBehaviors.scala` | Create: `MessageBusTest`'s shared cases |
| `base/messaging/src/test/scala/M/FutureContinuationsBehaviors.scala` | Create: `FutureContinuationsTest`'s cases |
| `base/messaging/src/test/scala/M/MessageBusTest.scala` | Modify: the suite on `ManualTestRuntime`, plus the Manual-only cases |
| `base/messaging/src/test/scala/M/FutureContinuationsTest.scala` | Modify: the suite on `ManualTestRuntime` |
| `base/messaging/src/test/scala/M/ManualMessagingRuntime.scala` | Modify: `causingCommandId` leaves its companion |
| `base/common/src/test/scala/.../common/LogCapture.scala` (+ `LogCaptureTest.scala`) | Modify: a thread filter |
| `project/Dependencies.scala`, `build.sbt` | Modify: Pekko, the `config` pin, the module |
| `base/messaging-pekko/src/main/resources/reference.conf` | Create: dispatchers, mailbox, logging |
| `base/messaging-pekko/src/main/scala/P/PekkoMessagingRuntime.scala` | Create: the runtime, startup and shutdown |
| `base/messaging-pekko/src/main/scala/P/PekkoMailbox.scala` | Create: a mailbox, and the behavior of its actor |
| `base/messaging-pekko/src/main/scala/P/ShutdownPhase.scala` | Create: the phases of the shutdown tasks |
| `base/messaging-pekko/src/main/scala/P/SingleThreadExecutorConfigurator.scala` | Create: the UI dispatcher's default executor |
| `base/messaging-pekko/src/test/resources/application-test.conf` | Create: no JVM shutdown hooks |
| `base/messaging-pekko/src/test/scala/P/PekkoTestSupport.scala` | Create: the test kit, the UI dispatcher, `Timeout` |
| `base/messaging-pekko/src/test/scala/P/PekkoMessagingRuntimeTest.scala` | Create: the runtime's tests |
| `base/messaging-pekko/src/test/scala/P/PekkoTestRuntime.scala` (+ `PekkoTestRuntimeTest.scala`) | Create: the test runtime over Pekko |
| `base/messaging-pekko/src/test/scala/P/PekkoMessageBusTest.scala`, `PekkoFutureContinuationsTest.scala` | Create: the shared behaviors on Pekko |
| `base/messaging-pekko/{CLAUDE.md,README.md,docs/ARCHITECTURE.md}`, `base/README.md`, `base/messaging/docs/ARCHITECTURE.md`, `docs/architecture/module-overview.md`, `AGENTS.md` | Create / modify: docs |

---

### Task 1: The shared behaviors, on `ManualTestRuntime`

A refactor of the test sources only: no production code changes, and the `messaging` suite stays green throughout.
Docs follow in Task 10.

**Files:** the `base/messaging/src/test/scala/M/` rows of the table above.

**Interfaces:**
- Produces, in package `org.calinburloiu.music.microtonalist.messaging`, test sources:

  ```scala
  /** A MessagingRuntime under test, with what the shared behaviors of the messaging layer need to drive it. */
  trait MessagingTestRuntime extends AutoCloseable {
    def runtime: MessagingRuntime                    // what a case's MessageBus is built on
    def awaitIdle(): Unit                            // returns once no mailbox holds or runs a message, including those
                                                     // enqueued meanwhile; their writes are then visible to the caller
    def onToolkit[T](body: => T): T                  // runs on the UI toolkit's thread, outside any message
    def capturingLogs[R](loggerName: String)(body: => R): (R, Seq[ILoggingEvent]) // the caller's and the mailboxes' threads
  }
  object MessagingTestRuntime {
    def causingCommandId(using messageBus: MessageBus): Option[Long]   // moved from ManualMessagingRuntime's companion
  }

  final class ManualTestRuntime extends MessagingTestRuntime   // awaitIdle = drainAll(); close shuts its toolkit down

  final class SynchronizedBuffer[T] {   // appended to by several threads at once, read as a snapshot
    def +=(element: T): Unit
    def ++=(elements: IterableOnce[T]): Unit
    def clear(): Unit
    def toSeq: Seq[T]
  }

  trait MessagingFixtures extends TestSuite {
    protected def newTestRuntime(): MessagingTestRuntime   // a fresh one per case; closed after the case
    abstract class Fixture {
      val testRuntime: MessagingTestRuntime
      val messageBus: MessageBus
      given MessageBus = messageBus
      val log: SynchronizedBuffer[String]
      def awaitIdle(): Unit
      def onToolkit[T](body: => T): T
      def capturingLogs[R](body: => R): (R, Seq[ILoggingEvent])   // MessageBus's logger
      def askWithUiEvent(): Future[String]                         // ToolkitFixture's, with awaitIdle()
    }
  }
  object MessagingFixtures   // MessageBusTest's companion members: constants, messages, Nameless, suppressedMessages

  trait MessageBusBehaviors extends MessagingFixtures { this: AnyWordSpec & Matchers =>
    def aMessageBus(): Unit             // registers the "dispatching a command" … "awaiting a Future" should-groups
  }
  trait FutureContinuationsBehaviors extends MessagingFixtures { this: AnyWordSpec & Matchers =>
    def futureContinuations(): Unit     // registers "the Business continuations" and "the UI continuations"
  }
  ```

- [ ] **Step 1: Write `MessagingTestRuntime`, `ManualTestRuntime`, `SynchronizedBuffer` and `MessagingFixtures`.**
  - **`ManualTestRuntime`** absorbs `MessageBusTest.ToolkitRuntime`. Its `runtime` wraps a `ManualMessagingRuntime`,
    so that the UI mailbox also counts the toolkit's thread as its own. The toolkit is a single-thread executor,
    created on the first `onToolkit`, which `close()` shuts down. Its `capturingLogs` is
    `LogCapture.capturing(loggerName)(body)`.
  - **`MessagingFixtures`** overrides `withFixture(test: NoArgTest)`: it runs `super.withFixture(test)`, then closes
    every test runtime made during the case, in a `finally`. `Fixture` registers its runtime with the suite as it's
    built.

- [ ] **Step 2: Move the cases into `MessageBusBehaviors.aMessageBus()` and
  `FutureContinuationsBehaviors.futureContinuations()`.**

  Keep each case's group, name, order and Given / When / Then, except as listed after these mechanical rewrites:

  | Before | After |
  | ------ | ----- |
  | `runtime.drainAll()` | `awaitIdle()` |
  | `LogCapture.capturing(classOf[MessageBus].getName) { … }` | `capturingLogs { … }` |
  | `withToolkitFixture { fixture => import fixture.*; … }` | `new Fixture { … }`, with `onToolkit` and `askWithUiEvent()` |
  | `ManualMessagingRuntime.causingCommandId…` | `MessagingTestRuntime.causingCommandId…` |
  | `log shouldEqual …`, `log shouldBe empty`, `stamps should contain …`, `ids …` | the same on `log.toSeq`, `stamps.toSeq`, `ids.toSeq` |
  | `mutable.ArrayBuffer[…]()` for `stamps` and `ids` | `SynchronizedBuffer[…]()` |

  The exceptions follow the rule that no case races the test thread against a mailbox. A message reads only what was
  set before its first enqueue, or on the mailbox's own thread, and the assertions come after `awaitIdle()`.

  - **Drop the intermediate "not run yet" checks**: the `log shouldBe empty`, `isCompleted shouldBe false` or
    partial-drain steps before the final drain. Rename the cases where their names promised it:
    - "run a command's handler on the Business Thread";
    - "deliver an event to a Business subscriber on the Business Thread";
    - "deliver events to UI subscribers on the UI Thread, in publish order", with one `awaitIdle()` in place of the
      two partial drains;
    - "complete the reply with the result of the request's handler";
    - "run mapOnBusiness's function on the Business Thread once the Future completes", which keeps its name.

    The thread check in each case already shows that the message ran on its mailbox, not inline.
  - **Replace "not deliver an event published before its subscription was cancelled"** with two cases. In each, one
    message both publishes and cancels:

    ```scala
    "not deliver an event to a Business subscription cancelled after the event was published" in new Fixture {
      private val dogs = messageBus.subscribeOnBusiness(classOf[Dog])(dog => log += s"business ${dog.name}")
      messageBus.handleCommand(classOf[Retune]) { _ =>
        messageBus.publish(Dog("Rex"))
        dogs.cancel()
      }
      messageBus.dispatch(Retune(1))
      awaitIdle()
      log.toSeq shouldBe empty
    }

    "not deliver an event to a UI subscription cancelled after the event was published" in new Fixture {
      private val dogs = messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"ui ${dog.name}")
      messageBus.subscribeOnUi(classOf[Wolf]) { _ =>
        messageBus.publish(Dog("Rex"))
        dogs.cancel()
      }
      messageBus.publish(Wolf("Grey"))
      awaitIdle()
      log.toSeq shouldBe empty
    }
    ```
  - **"complete the reply only after the events its handler published reached their Business subscribers"** asks
    from a Business handler, so that `reply` is set before the request's handler runs:

    ```scala
    messageBus.handleCommand(classOf[Retune])(_ => reply = messageBus.ask(Echo("hi")))
    messageBus.dispatch(Retune(1))
    awaitIdle()
    completedAtDelivery shouldEqual Some(false)
    reply.value shouldEqual Some(Success("hi"))
    ```
  - **"deliver the events that a callback of the reply publishes on the Business Thread"** registers the `parasitic`
    callback from a Business handler, before the reply completes:

    ```scala
    messageBus.handleCommand(classOf[Retune]) { _ =>
      messageBus.ask(Echo("Rex")).foreach(text => messageBus.publish(Dog(text)))(using ExecutionContext.parasitic)
    }
    messageBus.dispatch(Retune(1))
    awaitIdle()
    log.toSeq shouldEqual Seq("Rex")
    ```
  - **"complete an ask's reply through the Business mailbox when the handler's Future completes later"** becomes
    "complete an ask's reply on the Business Thread when the handler's Future completes later":

    ```scala
    private val input = Promise[String]()
    private var completedOnBusiness = false
    messageBus.handleRequest(classOf[Later])(_ => input.future)
    private val reply = messageBus.ask(Later())
    awaitIdle()
    // `parasitic` runs the callback on the thread that completes the reply.
    reply.onComplete(_ => completedOnBusiness = Try(messageBus.requireBusinessThread()).isSuccess)(
      using ExecutionContext.parasitic)
    input.success("late")
    awaitIdle()
    reply.value shouldEqual Some(Success("late"))
    completedOnBusiness shouldBe true
    ```

- [ ] **Step 3: Rewrite the two suites.**
  - **`MessageBusTest`** is `AnyWordSpec with Matchers with MessageBusBehaviors`. It sets
    `newTestRuntime() = ManualTestRuntime()` and registers `"a MessageBus" when { behave like aMessageBus(); … }`.
    - Inside that `when`, a `"running on the ManualMessagingRuntime" should` group holds the three cases that inspect
      the Manual queues: "start the Business mailbox at normal priority", "start the UI mailbox after the Business
      one" and "enqueue an event to the UI mailbox as soon as it's published".
    - Their bodies stay as today, on a local `ManualFixture` that builds a `ManualMessagingRuntime` and a
      `MessageBus` over it.
  - **`FutureContinuationsTest`** is `AnyWordSpec with Matchers with FutureContinuationsBehaviors`, with the same
    `newTestRuntime()`, and `behave like futureContinuations()` at its top level.
  - **`ManualMessagingRuntime`'s companion** keeps `MaxDrainedMessages` and loses `causingCommandId`.

- [ ] **Step 4: Run the module's tests.**

  Run: `sbtn "messaging/testOnly * -- -oNCXEHLOPQRMWS"`
  Expected: all pass. `MessageBusTest` runs 69 cases (66 shared and 3 Manual-only), `FutureContinuationsTest` 27 and
  `ManualMessagingRuntimeTest` 11.

- [ ] **Step 5: Commit** `[#90/#387] Share the messaging tests as behaviors over a pluggable test runtime`.

### Task 2: The module, and the pinned mailbox

**Files:**
- Modify: `project/Dependencies.scala`, `build.sbt`
- Create: `reference.conf`, `PekkoMessagingRuntime.scala`, `PekkoMailbox.scala`, `application-test.conf`,
  `PekkoTestSupport.scala`, `PekkoMessagingRuntimeTest.scala`, `base/messaging-pekko/CLAUDE.md`,
  `base/messaging-pekko/README.md`, in the same shape as `messaging`'s

**Interfaces:**
- Consumes: `MessagingRuntime`, `Mailbox`, `Urgent` from `messaging`.
- Produces:

  ```scala
  @ThreadSafe
  final class PekkoMessagingRuntime private[pekko] (private[pekko] val system: ActorSystem[?],
                                                    uiStartTimeout: FiniteDuration = PekkoMessagingRuntime.UiStartTimeout)
    extends MessagingRuntime {
    /** @throws IllegalArgumentException if `priority` is outside Thread.MIN_PRIORITY..Thread.MAX_PRIORITY */
    override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M]
  }
  object PekkoMessagingRuntime {
    val UiStartTimeout: FiniteDuration = 10.seconds
    private[pekko] val PinnedDispatcherPath: String = "microtonalist.messaging.pinned-dispatcher"
    private[pekko] val UiDispatcherPath: String = "microtonalist.messaging.ui-dispatcher"
    private[pekko] val MailboxPath: String = "microtonalist.messaging.mailbox"
  }
  // A Mailbox over a system actor that the runtime spawns. `onStart` runs in the actor's setup, on its thread, before
  // the mailbox records that thread; Task 5 adds `onStopped`. The builder settles the other constructor parameters.
  private[pekko] final class PekkoMailbox[M <: AnyRef](override val name: String, …, onStart: () => Unit)(handler: M => Unit)
    extends Mailbox[M]
  object PekkoTestSupport {   // test sources
    val Timeout: FiniteDuration = 5.seconds
    def startTestKit(name: String): ActorTestKit   // initialises SLF4J, then loads application-test.conf
  }
  ```

- [ ] **Step 1: Add the dependencies and the module.**
  - **`Dependencies.scala`:**
    - `pekkoVersion = "1.7.1"`;
    - `pekkoActorTyped`, `pekkoActorTestkitTyped` and `pekkoSlf4j` (`"org.apache.pekko" %% "<artifact>" %
      pekkoVersion`), each in its alphabetical place;
    - `typesafeConfigVersion = "1.4.9"`, with the comment `// Brought by Pekko; ficus brings 1.3.4, which reads it
      too: see the config module's tests`.
  - **`build.sbt`:** the module, as the Global Constraints give it.
  - **`reference.conf`:**
    - `pinned-dispatcher`: `type = PinnedDispatcher`, `executor = thread-pool-executor`,
      `thread-pool-executor.allow-core-timeout = off`;
    - `ui-dispatcher`: `type = Dispatcher`, `executor = "<FQCN of SingleThreadExecutorConfigurator>"`,
      `throughput = 1` (Task 6 adds the class; nothing reads the key before then);
    - `mailbox.mailbox-type = "org.apache.pekko.dispatch.UnboundedControlAwareMailbox"`;
    - `pekko.loggers = ["org.apache.pekko.event.slf4j.Slf4jLogger"]` and
      `pekko.logging-filter = "org.apache.pekko.event.slf4j.Slf4jLoggingFilter"`;
    - each with a one-line comment saying why.
  - **`application-test.conf`:** `pekko.jvm-shutdown-hooks = off` and
    `pekko.coordinated-shutdown.run-by-jvm-shutdown-hook = off`, since the tests run in the sbt server's JVM.

  Run `mcp__metals__import-build`, then `sbtn "config/testOnly * -- -oNCXEHLOPQRMWS"`. Expected: all pass, on config
  1.4.9.

- [ ] **Step 2: Write the failing tests** in `PekkoMessagingRuntimeTest`.

  The suite shares one `ActorTestKit` (`startTestKit`, `shutdownTestKit()` in `afterAll`) and one
  `PekkoMessagingRuntime(testKit.system)`. Its messages are `Probe(name)`, `UrgentProbe(name) extends Urgent`,
  `Gate(entered, release: CountDownLatch)`, `CheckThread(result: CompletableFuture[Boolean])` and `Fail(text)`.

  Its `Fixture(name = "Pinned", priority = Thread.NORM_PRIORITY)` starts a pinned mailbox whose handler does this:
  - puts `(probe's name, Thread.currentThread())` in `runs: LinkedBlockingQueue`;
  - at a `Gate`, counts down `entered` and awaits `release`;
  - completes a `CheckThread`'s `result` with `mailbox.isCurrentThread`;
  - throws at a `Fail`.

  Its helpers:
  - `nextRun()` polls `runs` for up to `Timeout` and fails if nothing comes;
  - `hold()` enqueues a `Gate`, awaits `entered` and returns `release`.

  ```scala
  "a pinned mailbox" should {
    "run its messages on one thread, named after the mailbox, at the given priority" in
      new Fixture("Track-1", Thread.NORM_PRIORITY + 2) {
        mailbox.enqueue(Probe("a"))
        mailbox.enqueue(Probe("b"))
        private val (_, first) = nextRun()
        private val (_, second) = nextRun()
        second should be theSameInstanceAs first
        first.getName shouldEqual "Track-1"
        first.getPriority shouldEqual Thread.NORM_PRIORITY + 2
      }

    "run its messages in arrival order" in new Fixture() {
      (1 to 100).foreach(index => mailbox.enqueue(Probe(index.toString)))
      Seq.fill(100)(nextRun()._1) shouldEqual (1 to 100).map(_.toString)
    }

    "run each mailbox on a thread of its own, also two of the same name" in {
      val business1 = new Fixture("Business") {}
      val business2 = new Fixture("Business") {}
      business1.mailbox.enqueue(Probe("1"))
      business2.mailbox.enqueue(Probe("2"))
      val (_, thread1) = business1.nextRun()
      val (_, thread2) = business2.nextRun()
      thread1 should not be theSameInstanceAs(thread2)
      Seq(thread1.getName, thread2.getName) shouldEqual Seq("Business", "Business")
    }

    "name its thread with a name that isn't a valid actor name" in new Fixture("Track-Piano 1/2") {
      mailbox.enqueue(Probe("a"))
      nextRun()._2.getName shouldEqual "Track-Piano 1/2"
    }

    "count its own thread as current from its first message, and no other thread" in new Fixture() {
      private val inFirst = CompletableFuture[Boolean]()
      mailbox.enqueue(CheckThread(inFirst))
      inFirst.get(Timeout.toMillis, TimeUnit.MILLISECONDS) shouldBe true
      mailbox.isCurrentThread shouldBe false
    }

    "throw IllegalArgumentException on the caller's thread for a priority out of range" in {
      an[IllegalArgumentException] should be thrownBy
        runtime.startMailbox[Message]("Low", Thread.MIN_PRIORITY - 1)(_ => ())
      an[IllegalArgumentException] should be thrownBy
        runtime.startMailbox[Message]("High", Thread.MAX_PRIORITY + 1)(_ => ())
    }
  }
  ```

- [ ] **Step 3: Run them** to verify they fail on the `???` stubs, not on compile errors.

  Run: `sbtn "messaging-pekko/testOnly org.calinburloiu.music.microtonalist.messaging.pekko.PekkoMessagingRuntimeTest -- -oNCXEHLOPQRMWS"`
  Expected: 6 failed, with `NotImplementedError`.

- [ ] **Step 4: Implement `startMailbox` and `PekkoMailbox`'s `name`, `enqueue` and `isCurrentThread`.**
  - `startMailbox` checks the priority with `require`, then spawns, with Props
    `DispatcherSelector.fromConfig(PinnedDispatcherPath).withMailboxFromConfig(MailboxPath)`:
    ```scala
    system.systemActorOf(PekkoMailbox.behavior(…), s"mailbox-${nextMailboxNumber.getAndIncrement()}", props)
    ```
  - The behavior's setup, on the actor's thread, runs an `onStart` the runtime passes: `setName(name)` and
    `setPriority(priority)` for a pinned mailbox. Then it records `Thread.currentThread()` in the mailbox's volatile
    field, which `isCurrentThread` compares by identity.
  - The behavior is `Behavior[AnyRef]`. `enqueue` tells it the message itself, or `UrgentEnvelope(message)` for an
    `Urgent` one, which Task 4 tests. The behavior casts the message to `M`, explicitly with `asInstanceOf`, since a
    type test on `M` would be an unchecked-warning error.

- [ ] **Step 5: Run the tests to verify they pass.** Same command. Expected: 6 passed.

- [ ] **Step 6: Commit** `[#90/#387] Add messaging-pekko, with pinned mailboxes named and prioritized`.

### Task 3: A handler's escaped exception, and `LogCapture`'s thread filter

**Files:**
- Modify: `LogCapture.scala`, `LogCaptureTest.scala`, `PekkoMailbox.scala`, `PekkoMessagingRuntimeTest.scala`

**Interfaces:**
- Produces:

  ```scala
  // LogCapture
  /** As `capturing(loggerName)`, but keeps the events logged on the threads that `isCaptured` accepts, evaluated on the
    * logging thread when it logs. */
  def capturing[R](loggerName: String, isCaptured: Thread => Boolean)(body: => R): (R, Seq[ILoggingEvent])
  ```

  The existing `capturing(loggerName)` delegates with `_ eq` the calling thread, and its ScalaDoc points to the new
  overload. The appender's filter compares `Thread.currentThread()` instead of the thread name, since names repeat
  across parallel suites: every bus has a `Business` thread.

- [ ] **Step 1: Write the failing tests.**

  ```scala
  // LogCaptureTest
  "capture the events of the threads that its filter accepts, and only those" in {
    val loggerName = s"${getClass.getName}.filtered"
    val accepted = Thread(() => Logger(loggerName).debug("accepted"))
    val rejected = Thread(() => Logger(loggerName).debug("rejected"))
    val (_, events) = LogCapture.capturing(loggerName, _ eq accepted) {
      accepted.start()
      rejected.start()
      accepted.join(TimeoutSeconds * 1000)
      rejected.join(TimeoutSeconds * 1000)
    }
    events.messagesAt(Level.DEBUG) shouldEqual Seq("accepted")
  }

  // PekkoMessagingRuntimeTest, "a pinned mailbox"
  "log a message whose handler throws, and go on with the next" in new Fixture() {
    mailbox.enqueue(Probe("first"))
    private val (_, thread) = nextRun()
    private val ((next, _), events) = LogCapture.capturing(classOf[PekkoMailbox[?]].getName, _ eq thread) {
      mailbox.enqueue(Fail("boom"))
      mailbox.enqueue(Probe("next"))
      nextRun()
    }
    next shouldEqual "next"
    events.failuresAt(Level.ERROR) shouldEqual Seq(("A message of mailbox Pinned failed.", Some("boom")))
  }
  ```

- [ ] **Step 2: Run them to verify they fail.** The new overload doesn't exist yet, so add it as a `???` stub first.

  Run: `sbtn "common/testOnly org.calinburloiu.music.microtonalist.common.LogCaptureTest -- -oNCXEHLOPQRMWS"`, then
  the `PekkoMessagingRuntimeTest` command.
  Expected: the new case of each fails. The Pekko one fails because the exception isn't logged under that message
  (and the actor's supervision may stop it).

- [ ] **Step 3: Implement the overload**, and wrap the handler call in `PekkoMailbox` with `try … catch { case
  NonFatal(exception) => logger.error("A message of mailbox {} failed.", name, exception) }`. `PekkoMailbox` uses
  `StrictLogging`.

- [ ] **Step 4: Run the tests to verify they pass.** Same commands, plus `sbtn "common/testOnly * -- -oNCXEHLOPQRMWS"`,
  since every suite of `LogCapture`'s callers depends on the old overload's behavior. Expected: all pass.

- [ ] **Step 5: Commit** `[#90/#387] Log a mailbox handler's escaped exception, and capture other threads' logs`.

### Task 4: The urgent lane

**Files:** Modify: `PekkoMailbox.scala`, `PekkoMessagingRuntimeTest.scala`

**Interfaces:** Produces `private[pekko] final case class UrgentEnvelope(message: AnyRef) extends ControlMessage`, in
`PekkoMailbox`'s companion.

- [ ] **Step 1: Write the failing test.**

  ```scala
  "run the urgent messages queued before the normal ones, each kind in arrival order" in new Fixture() {
    private val release = hold()
    mailbox.enqueue(Probe("n1"))
    mailbox.enqueue(UrgentProbe("u1"))
    mailbox.enqueue(Probe("n2"))
    mailbox.enqueue(UrgentProbe("u2"))
    release.countDown()
    Seq.fill(4)(nextRun()._1) shouldEqual Seq("u1", "u2", "n1", "n2")
  }
  ```

- [ ] **Step 2: Run it to verify it fails.** If Task 2 already wrapped urgent messages, temporarily tell `UrgentProbe`
  unwrapped to watch the test fail. Expected: FAIL, `Seq("n1", "u1", "n2", "u2")`.
- [ ] **Step 3: Implement.** `enqueue` wraps an `Urgent` message in `UrgentEnvelope`, and the behavior unwraps it
  before calling the handler. The mailbox selected by `MailboxPath` takes control messages first.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: all `PekkoMessagingRuntimeTest` cases pass.
- [ ] **Step 5: Commit** `[#90/#387] Let an urgent message overtake the queued ones`.

### Task 5: Stopping a mailbox

**Files:** Modify: `PekkoMailbox.scala`, `PekkoMessagingRuntimeTest.scala`

**Interfaces:**
- Produces: `PekkoMailbox.stop(): Future[Unit]`, which returns the same `Future` on every call. It takes an
  `onStopped: () => Unit` callback, which runs in `PostStop` before the `Future` completes; Task 7 uses it to forget
  the mailbox.

- [ ] **Step 1: Write the failing tests.**

  ```scala
  "run the messages queued before stop, then complete its Future" in new Fixture() {
    private val release = hold()
    mailbox.enqueue(Probe("a"))
    mailbox.enqueue(Probe("b"))
    private val stopped = mailbox.stop()
    stopped.isCompleted shouldBe false
    release.countDown()
    Await.result(stopped, Timeout)
    runs.asScala.toSeq.map(_._1) shouldEqual Seq("a", "b")
  }

  "drop a message enqueued after stop, before it reaches Pekko's dead letters" in new Fixture() {
    private val deadLetters = testKit.createDeadLetterProbe()
    private val stopped = mailbox.stop()
    mailbox.enqueue(Probe("late"))
    Await.result(stopped, Timeout)
    deadLetters.expectNoMessage()
    runs.isEmpty shouldBe true
  }

  "return the same Future when stopped twice" in new Fixture() {
    mailbox.stop() should be theSameInstanceAs mailbox.stop()
  }
  ```

- [ ] **Step 2: Run them** to verify they fail on the `???` stub. Expected: 3 failed.
- [ ] **Step 3: Implement it as decision 5 says.** The behavior's receive returns `Behaviors.stopped` on the internal
  `Stop`, and its `receiveSignal` runs `onStopped()`, then completes the stop `Promise`, on `PostStop`.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: all pass.
- [ ] **Step 5: Commit** `[#90/#387] Stop a mailbox after its queued messages, and drop what comes after`.

### Task 6: The UI mailbox

**Files:** Create `SingleThreadExecutorConfigurator.scala`; modify `PekkoMessagingRuntime.scala`,
`PekkoTestSupport.scala`, `PekkoMessagingRuntimeTest.scala`

**Interfaces:**
- Produces:

  ```scala
  /** Gives a Pekko dispatcher a thread of its own, the UI mailbox's default. */
  final class SingleThreadExecutorConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
    extends ExecutorServiceConfigurator(config, prerequisites)
  // PekkoMessagingRuntime
  /** @throws IllegalStateException if the mailbox didn't record its thread within the start timeout, e.g. because the
    *   call ran on that thread */
  override def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M]
  // PekkoTestSupport
  /** Runs `body` on the UI dispatcher's thread, outside any actor's message, and returns its result. */
  def onUiDispatcher[T](system: ActorSystem[?])(body: => T): T
  ```

- [ ] **Step 1: Write the failing tests** in a `"the UI mailbox" should` group. Its `UiFixture` records as `Fixture`
  does, on `runtime.startUiMailbox`.

  ```scala
  "be named UI" in new UiFixture {
    mailbox.name shouldEqual MessagingRuntime.UiMailboxName
  }

  "count the UI dispatcher's thread as its own once started, in its messages and outside them" in new UiFixture {
    private val (outside, toolkitThread) =
      PekkoTestSupport.onUiDispatcher(testKit.system)(mailbox.isCurrentThread -> Thread.currentThread())
    mailbox.enqueue(Probe("a"))
    outside shouldBe true
    nextRun()._2 should be theSameInstanceAs toolkitThread
    mailbox.isCurrentThread shouldBe false
  }

  "throw IllegalStateException when started on the UI dispatcher's thread, which it waits for" in {
    val impatient = PekkoMessagingRuntime(testKit.system, uiStartTimeout = ShortUiStartTimeout)  // 200.millis
    val attempt = PekkoTestSupport.onUiDispatcher(testKit.system)(Try(impatient.startUiMailbox[Message](_ => ())))
    attempt.failed.get shouldBe an[IllegalStateException]
  }
  ```

- [ ] **Step 2: Run them** to verify they fail. Expected: 3 failed.
- [ ] **Step 3: Implement.**
  - **The configurator's factory** returns `Executors.newSingleThreadExecutor(threadFactory)`, through an anonymous
    `ExecutorServiceFactory`: its method has no parameter list, so it isn't a SAM.
  - **`startUiMailbox`** spawns on `DispatcherSelector.fromConfig(UiDispatcherPath).withMailboxFromConfig(MailboxPath)`.
    Its `onStart` only completes a `Promise`, which `startUiMailbox` awaits with `Await.ready(…, uiStartTimeout)`,
    turning a `TimeoutException` into the `IllegalStateException`. Its message says that `startUiMailbox` must not
    run on the UI toolkit's thread.
  - **`onUiDispatcher`** is `Await.result(Future(body)(system.dispatchers.lookup(DispatcherSelector.fromConfig(UiDispatcherPath))), Timeout)`.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: all pass.
- [ ] **Step 5: Commit** `[#90/#387] Run the UI mailbox on a configurable executor`.

### Task 7: Startup and shutdown

**Files:** Create `ShutdownPhase.scala`; modify `PekkoMessagingRuntime.scala`, `PekkoMessagingRuntimeTest.scala`

**Interfaces:**
- Produces:

  ```scala
  enum ShutdownPhase(private[pekko] val pekkoPhase: String) {
    case FinishWork extends ShutdownPhase(CoordinatedShutdown.PhaseServiceRequestsDone)
    case ReleaseResources extends ShutdownPhase(CoordinatedShutdown.PhaseServiceStop)
  }
  // PekkoMessagingRuntime
  def addShutdownTask(phase: ShutdownPhase, name: String)(task: () => Future[Unit]): Unit
  def shutdown(): Future[Unit]                        // completes once the ActorSystem has terminated
  private[pekko] def runningMailboxes: Seq[Mailbox[?]] // the pinned mailboxes started and not yet stopped, in start order
  // PekkoMessagingRuntime's companion
  def start(config: Config = ConfigFactory.load()): PekkoMessagingRuntime
  private[pekko] def stopLastStartedFirst(mailboxes: Seq[Mailbox[?]])(using ExecutionContext): Future[Unit]
  ```

- [ ] **Step 1: Write the failing tests.**
  - A `ShutdownFixture` starts its own runtime with `PekkoMessagingRuntime.start(ConfigFactory.load("application-test"))`,
    since a shutdown ends the system.
  - A test `FakeMailbox(name, stops: SynchronizedBuffer[String]) extends Mailbox[AnyRef]` has a `stop()` that adds
    `name` to `stops` and returns its `stopped: Promise[Unit]`'s `Future`.

  ```scala
  "starting and shutting down" should {
    "start an ActorSystem whose mailboxes run, and terminate it on shutdown" in new ShutdownFixture {
      private val ran = CompletableFuture[String]()
      started.startMailbox[String]("Business", Thread.NORM_PRIORITY)(text => ran.complete(text)).enqueue("hello")
      ran.get(Timeout.toMillis, TimeUnit.MILLISECONDS) shouldEqual "hello"
      Await.result(started.shutdown(), Timeout)
      Await.result(started.system.whenTerminated, Timeout)
    }

    "run the FinishWork tasks, then the ReleaseResources ones, then stop the mailboxes" in new ShutdownFixture {
      private val steps = SynchronizedBuffer[String]()
      private val mailbox = started.startMailbox[Promise[Unit]]("Business", Thread.NORM_PRIORITY) { done =>
        steps += "mailbox"
        done.success(())
      }
      started.addShutdownTask(ShutdownPhase.ReleaseResources, "release") { () =>
        steps += "release"
        Future.unit
      }
      started.addShutdownTask(ShutdownPhase.FinishWork, "finish") { () =>
        steps += "finish"
        val done = Promise[Unit]()
        mailbox.enqueue(done)
        done.future
      }
      Await.result(started.shutdown(), Timeout)
      steps.toSeq shouldEqual Seq("finish", "mailbox", "release")
      mailbox.stop().isCompleted shouldBe true
    }

    "run each task once when shut down twice" in new ShutdownFixture {
      private val runs = AtomicInteger()
      started.addShutdownTask(ShutdownPhase.FinishWork, "count") { () =>
        runs.incrementAndGet()
        Future.unit
      }
      private val first = started.shutdown()
      private val second = started.shutdown()
      Await.result(first, Timeout)
      Await.result(second, Timeout)
      runs.get shouldEqual 1
    }

    "forget a pinned mailbox once it has stopped" in new ShutdownFixture {
      private val mailbox = started.startMailbox[String]("Business", Thread.NORM_PRIORITY)(_ => ())
      started.runningMailboxes shouldEqual Seq(mailbox)
      Await.result(mailbox.stop(), Timeout)
      started.runningMailboxes shouldBe empty
    }

    "stop the mailboxes last started first, each once the one after it has stopped" in {
      val stops = SynchronizedBuffer[String]()
      val first = FakeMailbox("first", stops)
      val second = FakeMailbox("second", stops)
      val all = PekkoMessagingRuntime.stopLastStartedFirst(Seq(first, second))(using ExecutionContext.parasitic)
      stops.toSeq shouldEqual Seq("second")
      second.stopped.success(())
      stops.toSeq shouldEqual Seq("second", "first")
      all.isCompleted shouldBe false
      first.stopped.success(())
      all.isCompleted shouldBe true
    }
  }
  ```

  Every `ShutdownFixture` case ends with `Await.result(started.shutdown(), Timeout)`, or has already run it, so that
  no `ActorSystem` outlives its case.

- [ ] **Step 2: Run them** to verify they fail on the stubs. Expected: 5 failed.
- [ ] **Step 3: Implement it as decision 8 says.**
  - **`start`** calls `LoggerFactory.getILoggerFactory` before `ActorSystem[Nothing](Behaviors.empty, "microtonalist",
    config)`, since SLF4J must initialise before the `ActorSystem` starts.
  - **The constructor** adds the `stop-mailboxes` task to `CoordinatedShutdown(system)` in
    `PhaseBeforeActorSystemTerminate`, as `stopLastStartedFirst(runningMailboxes)`. `startMailbox` adds its mailbox to
    the running ones, and that mailbox's `onStopped` removes it, under one lock.
  - **`stopLastStartedFirst`** chains each `stop()` on the previous one, over `mailboxes.reverse`.
  - **`shutdown()`** maps `CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason)` to `Unit`, and
    `addShutdownTask` maps the task's `Future` to `Done`.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: all pass.
- [ ] **Step 5: Commit** `[#90/#387] Start the runtime, and shut it down on CoordinatedShutdown`.

### Task 8: `PekkoTestRuntime`

**Files:** Create `PekkoTestRuntime.scala`, `PekkoTestRuntimeTest.scala`, in `messaging-pekko`'s test sources

**Interfaces:**
- Consumes: `MessagingTestRuntime` (Task 1); `PekkoMessagingRuntime`, `PekkoTestSupport` (Tasks 2–7);
  `LogCapture.capturing(loggerName, isCaptured)` (Task 3).
- Produces:

  ```scala
  /** A MessagingTestRuntime over a PekkoMessagingRuntime whose mailboxes count their messages in flight. */
  final class PekkoTestRuntime(pekkoRuntime: PekkoMessagingRuntime, idleTimeout: FiniteDuration = PekkoTestSupport.Timeout)
    extends MessagingTestRuntime {
    /** @throws IllegalStateException if the mailboxes don't go idle within `idleTimeout` */
    override def awaitIdle(): Unit
    override def close(): Unit   // stops every mailbox it started, and waits for them up to idleTimeout
  }
  ```

  **How idleness is counted:**
  - Its `runtime` wraps each mailbox of `pekkoRuntime`. `enqueue` adds one to a counter under the runtime's monitor,
    then delegates. The wrapped handler records `Thread.currentThread()` in a concurrent set, runs the handler, and in
    a `finally` subtracts one, calling `notifyAll()` at zero.
  - `awaitIdle()` `wait`s on the monitor until the count is zero, or throws once `idleTimeout` has passed.
  - A message enqueues its follow-ups before it's counted out, so zero means idle. The monitor publishes the
    handlers' writes to the caller.
  - The other members:
    - `onToolkit` is `PekkoTestSupport.onUiDispatcher(pekkoRuntime.system)`, and records the toolkit's thread too;
    - `capturingLogs` accepts the caller's thread and the recorded ones;
    - the count is meaningless after `close()`.

- [ ] **Step 1: Write the failing tests.** The suite shares a test kit and a `PekkoMessagingRuntime`, as
  `PekkoMessagingRuntimeTest` does.

  ```scala
  "a PekkoTestRuntime" should {
    "return from awaitIdle once a chain of messages across its mailboxes has run, with their writes visible" in
      new Fixture {
        // Members, unlike a block's local values, may refer to later ones from a lambda: each handler reaches the
        // other mailbox, and runs only once both are built.
        private var count = 0 // touched by one mailbox at a time, never by two at once
        private val ui: Mailbox[String] = testRuntime.runtime.startUiMailbox[String] { _ =>
          count += 1
          if (count < 50) business.enqueue("ping")
        }
        private val business: Mailbox[String] =
          testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY) { _ =>
            count += 1
            ui.enqueue("pong")
          }
        business.enqueue("ping")
        testRuntime.awaitIdle()
        count shouldEqual 50
        testRuntime.close()
      }

    "throw from awaitIdle when a message runs past the timeout" in {
      val testRuntime = PekkoTestRuntime(pekkoRuntime, idleTimeout = ShortIdleTimeout)   // 100.millis
      val release = CountDownLatch(1)
      testRuntime.runtime.startMailbox[String]("Stuck", Thread.NORM_PRIORITY)(_ =>
        release.await(Timeout.toMillis, TimeUnit.MILLISECONDS)).enqueue("hold")
      an[IllegalStateException] should be thrownBy testRuntime.awaitIdle()
      release.countDown()
      testRuntime.close()
    }

    "run onToolkit's body on the UI mailbox's thread, outside its messages" in {
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val ui = testRuntime.runtime.startUiMailbox[String](_ => ())
      testRuntime.onToolkit(ui.isCurrentThread) shouldBe true
      testRuntime.close()
    }

    "capture the logs of its mailboxes' threads" in {
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val loggerName = s"${getClass.getName}.mailboxes"
      val business = testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY)(text =>
        Logger(loggerName).error(text))
      val (_, events) = testRuntime.capturingLogs(loggerName) {
        business.enqueue("logged")
        testRuntime.awaitIdle()
      }
      events.messagesAt(Level.ERROR) shouldEqual Seq("logged")
      testRuntime.close()
    }

    "stop its mailboxes on close" in {
      val testRuntime = PekkoTestRuntime(pekkoRuntime)
      val business = testRuntime.runtime.startMailbox[String]("Business", Thread.NORM_PRIORITY)(_ => ())
      testRuntime.close()
      business.stop().isCompleted shouldBe true
    }
  }
  ```

  `Fixture` holds `val testRuntime = PekkoTestRuntime(pekkoRuntime)`; the other cases may use it too.
  `ShortIdleTimeout` (100 ms) is a constant of the suite's companion.

- [ ] **Step 2: Run them** to verify they fail on the stubs. Expected: 5 failed.
- [ ] **Step 3: Implement it as the Interfaces block says.**
- [ ] **Step 4: Run the tests to verify they pass.** Expected: 5 passed.
- [ ] **Step 5: Commit** `[#90/#387] Add PekkoTestRuntime, which awaits the Pekko mailboxes going idle`.

### Task 9: The shared behaviors on Pekko

These are the slice's acceptance tests: the `MessageBus` and continuation cases on real threads.

**Files:** Create `PekkoMessageBusTest.scala`, `PekkoFutureContinuationsTest.scala`

**Interfaces:** Consumes `MessageBusBehaviors.aMessageBus()`, `FutureContinuationsBehaviors.futureContinuations()`
(Task 1) and `PekkoTestRuntime` (Task 8).

- [ ] **Step 1: Write the two suites.**

  ```scala
  class PekkoMessageBusTest extends AnyWordSpec with Matchers with BeforeAndAfterAll with MessageBusBehaviors {
    private val testKit: ActorTestKit = PekkoTestSupport.startTestKit("PekkoMessageBusTest")
    private val pekkoRuntime: PekkoMessagingRuntime = PekkoMessagingRuntime(testKit.system)

    override protected def newTestRuntime(): MessagingTestRuntime = PekkoTestRuntime(pekkoRuntime)

    override protected def afterAll(): Unit = testKit.shutdownTestKit()

    "a MessageBus" when {
      behave like aMessageBus()
    }
  }
  ```

  `PekkoFutureContinuationsTest` has the same shape, with `FutureContinuationsBehaviors` and a top-level
  `behave like futureContinuations()`.

- [ ] **Step 2: Run them.**

  Run: `sbtn "messaging-pekko/testOnly org.calinburloiu.music.microtonalist.messaging.pekko.Pekko*Test -- -oNCXEHLOPQRMWS"`
  Expected: 66 `PekkoMessageBusTest` and 27 `PekkoFutureContinuationsTest` cases pass. The runtime tests pass too.

- [ ] **Step 3: If a case fails, invoke `superpowers:systematic-debugging` before changing anything.**
  - **The case races the test thread against a mailbox:** rephrase it in the behavior trait by Task 1's rule. Check it
    against the list in Task 1, Step 2, and run it on both runtimes.
  - **The runtime is wrong:** fix it with a case in `PekkoMessagingRuntimeTest` that reproduces the bug.
  - **`MessageBus` has a concurrency bug:** the failing shared case is the red test. Fix `MessageBus`, then rerun
    `messaging`'s suite too.

  Run each failing case at least 20 times (`sbtn "… -- -z \"<case name>\" …"` in a shell loop) before calling it fixed,
  since a race may pass once.

- [ ] **Step 4: Commit** `[#90/#387] Run the messaging behaviors on the Pekko runtime`.

### Task 10: Docs and final checks

**Files:**
- Create `base/messaging-pekko/docs/ARCHITECTURE.md`
- Modify `base/README.md`, `base/messaging/docs/ARCHITECTURE.md`, `docs/architecture/module-overview.md`, `AGENTS.md`

- [ ] **Step 1: Invoke the `architecture-docs` skill and write the docs.**
  - **`messaging-pekko`'s `ARCHITECTURE.md`:**
    - its responsibility: the runtime, and that only `app` will depend on it;
    - key types: `PekkoMessagingRuntime`, `PekkoMailbox`, `ShutdownPhase`, `SingleThreadExecutorConfigurator`;
    - the mailbox's actor: pinned dispatcher, control-aware mailbox, `UrgentEnvelope`, stop;
    - the UI mailbox's executor and how `app` overrides it;
    - the shutdown sequence;
    - errors;
    - testing: `PekkoTestRuntime` and the shared behaviors;
    - subject to change: `app` adopts it in #388; MIDI after `Close` in #391; the JavaFX executor in #393.
  - **`messaging`'s `ARCHITECTURE.md`:**
    - *The causing command* now names `MessagingTestRuntime.causingCommandId`;
    - *Testing* describes the behavior traits over `MessagingTestRuntime`;
    - *Subject to change* drops the #387 line.
  - **The indexes:**
    - `base/README.md` gets a `messaging-pekko` row;
    - `module-overview.md` gets a `messaging-pekko` row in *Main modules*, and `messagingPekko --> messaging` in the
      graph;
    - `AGENTS.md`'s *Symbol tool targets* bullet adds `messaging-pekko` next to "until #388, `messaging`".
- [ ] **Step 2: Module tests.** Run `sbtn "messaging/testOnly * -- -oNCXEHLOPQRMWS"`, the same for
  `messaging-pekko`, `common` and `config`. Expected: all pass.
- [ ] **Step 3: Coverage.** Invoke the `scoverage-inspector` skill for `messaging-pekko`, whose target is 80/80, and
  check that `messaging` and `common` keep their floors. Iterate until they're met.
- [ ] **Step 4: Full suite.** Run `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`. Expected: all pass, the
  `perf-midi-latency` cases canceled.
- [ ] **Step 5: Lint.** Run `sbtn fixLint`, fix by hand what it reports, and repeat until it passes.
- [ ] **Step 6: Commit** `[#90/#387] Document messaging-pekko`, plus a separate commit for any lint fixes. Mark the
  draft PR ready for review only when the user asks.
