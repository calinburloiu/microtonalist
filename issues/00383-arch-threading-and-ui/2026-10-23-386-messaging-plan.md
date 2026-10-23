# Slice 1 (#386): the `messaging` module — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- **Date:** 2026-10-23
- **Based on commit:** `38fc62a3` (`doc/threading`), whose code is `main` at `35f08845`
- **Issue:** #386, slice 1 of #383 (parent #90)

**Goal:** Add the `messaging` module: commands, events, `dispatch`, `ask`, `publish`, the `Future` continuations, the
causing command, the refused `Await`, the thread checks and the runtime interface, tested on the hand-drained
`ManualRuntime`. It ships unused, next to `businessync`.

**Architecture:** `MessageBus` is the layer, built over a `MessagingRuntime`, which starts mailboxes: the bus starts
the Business mailbox and the UI mailbox and runs each of its messages, commands, event deliveries and continuations,
through one runner per thread. A Business message runs its body, then drains the events it published, then settles
the reply of the `ask` that caused it. `ManualRuntime`, in the test sources, implements the runtime with mailboxes that
a test drains on its own thread; slice 2 (#387) implements it with Pekko.

**Tech Stack:** Scala 3.9, sbt 1, ScalaTest 3 (`AnyWordSpec`), scala-logging, JSR 305 annotations.

**Spec:** [`2026-10-20-design.md`](2026-10-20-design.md), section 1 and the slice 1 entry of *Delivery*.

## Global Constraints

- Module `messaging`: folder `base/messaging/`, package `org.calinburloiu.music.microtonalist.messaging`, sbt
  `lazy val messagingModule` with `.withId("messaging")`. "It depends on no Pekko, no JavaFX and no Guava": only on
  `common`, with `"compile->compile;test->test"` for `LogCapture`, and on `jsr305`.
- It ships unused: no other module depends on it, and `businessync` stays as it is until #388.
- `ManualRuntime` lives in `messaging`'s test sources; later slices' modules reach it through
  `"compile->compile;test->test"`.
- The Business mailbox is named `Business` and runs at `Thread.NORM_PRIORITY`.
- `IllegalStateException` for a command or request matching no handler or several (thrown on the caller's thread), a
  failed thread check, a refused `Await` and a continuation started off its thread; `IllegalArgumentException` for a
  handler class related to a registered one.
- Errors are logged at ERROR, with the exception, as `The handler of <command> failed.`, `A subscriber of <event>
  failed.` and `A continuation failed.`, with `<command>` and `<event>` the message's `toString`.
- Conventions: brace syntax; no `return` (use `boundary`); `// TODO #<issue>`; contract-only ScalaDoc on every public
  identifier, test sources included; every compiler warning is an error.
- Tests: `AnyWordSpec` with `Matchers`; no `if` around an assertion; no `Thread.sleep`. Another thread's call is
  `CompletableFuture.runAsync(() => …).join()` or `supplyAsync`. Logs are checked with
  `LogCapture.capturing(classOf[MessageBus].getName)`. The snippets below leave out the `// Given` / `// When` /
  `// Then` comments, which every test has.
- Compile with `mcp__metals__compile-module` (module `messaging`, or `messaging-test` for the tests). Run tests with
  `sbtn "messaging/testOnly <fully qualified class> -- -oNCXEHLOPQRMWS"`. For Metals' symbol tools, use the module
  `messaging` or `messaging-test`, since `app` doesn't depend on `messaging` yet.
- Commits: `[#90/#386] <imperative sentence>`, the sub-issue notation of the `contributing` skill, ending with the
  attribution trailer that the session's instructions give; stage only the task's files. The pre-commit hook adds the
  license headers of new `.scala` files.
- Work on `feature/messaging`, branched from `doc/threading`, which holds this plan.

## Decisions within the design

The design leaves these to the builder; this plan settles them.

1. `MessageBus` is a class over a `MessagingRuntime`, not a trait: it has one implementation, and the tests of every
   module run it on `ManualRuntime`.
2. `startMailbox` returns at once; its mailbox records its thread before running its first message. Only
   `startUiMailbox` returns once its thread is recorded, since the toolkit calls UI code outside the mailbox's
   messages. Nothing runs on Business outside its messages, and waiting would block Business when slice 5 starts a
   track mailbox from there.
3. Subscribers are matched when an event is published, and a subscription cancelled before its delivery runs is
   skipped. An event that matches no subscriber of a thread sends no message there.
4. An `ask`'s reply completes on the Business Thread: at the end of the handler's message if the handler's `Future` has
   completed by then, otherwise in a Business message of its own once it completes.
5. An error on Business, in a handler, a subscriber or an `onComplete…` continuation, fails the pending reply of its
   causing `ask`, once the message's drain is over. An error on the UI Thread is only logged, since the reply has
   usually completed by then. A handler's failed `Future` fails the reply and isn't logged.
6. `dispatch` of a request runs its handler and drops the reply. `ask` of a request whose class resolves to a handler
   registered with `handle` throws `IllegalStateException` on the caller's thread.
7. Command ids are `Long`s from 1, one counter per bus. Tests read the stamp with `ManualRuntime.causingCommandId`,
   which reads `MessageBus.causingCommandId`, `private[messaging]`, from inside a handler, a continuation or a
   subscriber.

## Review Focus

1. A handler that `ask`s and then `Await`s the reply: it throws `IllegalStateException` at once, rather than deadlock
   Business (Task 8).
2. A subscription cancelled after an event was published but before its delivery: the subscriber isn't called, on
   either thread (Task 3).
3. A request handler that publishes events, then throws: the events still reach their subscribers, and the `ask`
   fails after them (Task 5).
4. A continuation of a `Future` that failed, such as a failed file read: `onCompleteOnBusiness` gets the `Failure` on
   Business, and `mapOnBusiness` returns it without calling its function (Task 6).
5. A subscriber that subscribes another while an event is delivered: no `ConcurrentModificationException`, and the
   new subscription gets the events published after it (Task 3).

## File Structure

Main code, in `base/messaging/src/main/scala/org/calinburloiu/music/microtonalist/messaging/`:

| File | Holds |
| ---- | ----- |
| `MessagingRuntime.scala` | `MessagingRuntime`, `Mailbox`, `Urgent`: the runtime interface |
| `Command.scala` | `Command`, `Request[R]` |
| `Subscription.scala` | `Subscription` |
| `MessageBus.scala` | `MessageBus`: the public API, the Business and UI message runners, the drain, the replies, the stamps, the refusing `BlockContext`, the continuation `ExecutionContext`s |
| `CommandHandlers.scala` | `private[messaging]`: handler registration, rejecting related classes, and resolution, cached per command class |
| `Subscribers.scala` | `private[messaging]`: the copy-on-write subscription registry and the matching of an event |
| `FutureContinuations.scala` | The top-level `Future` extension methods `mapOnBusiness`, `flatMapOnBusiness`, `onCompleteOnBusiness`, `mapOnUi`, `onCompleteOnUi` |

Test code, in `base/messaging/src/test/scala/org/calinburloiu/music/microtonalist/messaging/`:

| File | Holds |
| ---- | ----- |
| `ManualRuntime.scala` | `ManualRuntime`, its companion, `ManualMailbox` |
| `ManualRuntimeTest.scala` | Tests of `ManualRuntime` |
| `MessageBusTest.scala` | Tests of `MessageBus`; its companion holds the test messages and the `Fixture` |
| `FutureContinuationsTest.scala` | Tests of the continuations |

Docs: `base/messaging/README.md`, `CLAUDE.md` and `docs/ARCHITECTURE.md` (Task 9).

---

### Task 1: The module, the runtime interface and `ManualRuntime`

**Files:**
- Modify: `build.sbt` (the module after `commonModule`; `root`'s `.aggregate` lists `messagingModule` after
  `commonModule`)
- Create: `base/messaging/src/main/scala/org/calinburloiu/music/microtonalist/messaging/MessagingRuntime.scala`
- Create: `base/messaging/src/test/scala/org/calinburloiu/music/microtonalist/messaging/ManualRuntime.scala`
- Test: `base/messaging/src/test/scala/org/calinburloiu/music/microtonalist/messaging/ManualRuntimeTest.scala`

**Interfaces:**
- Produces, in main:

  ```scala
  trait MessagingRuntime {
    def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): Mailbox[M]
    def startUiMailbox[M <: AnyRef](handler: M => Unit): Mailbox[M]
  }
  trait Mailbox[-M <: AnyRef] {
    def enqueue(message: M): Unit
    def isCurrentThread: Boolean
    def stop(): Future[Unit]
  }
  trait Urgent
  ```

  ScalaDoc contracts: a mailbox runs its messages one at a time on a thread of its own; `enqueue` is safe from any
  thread, and an `Urgent` message overtakes the non-urgent ones queued, each kind keeping its arrival order;
  `isCurrentThread` tells whether the calling thread is the mailbox's; `stop()` lets the queued messages run, then
  completes its `Future`. `startMailbox` and `startUiMailbox` as in decision 2; `priority` is a Java thread priority.
- Produces, in test:

  ```scala
  final class ManualRuntime extends MessagingRuntime {
    override def startMailbox[M <: AnyRef](name: String, priority: Int)(handler: M => Unit): ManualMailbox[M]
    override def startUiMailbox[M <: AnyRef](handler: M => Unit): ManualMailbox[M] // named "UI", priority None
    def mailboxes: Seq[ManualMailbox[?]] // in start order
    def mailbox(name: String): ManualMailbox[?] // NoSuchElementException if none
    def drainAll(): Unit
  }
  object ManualRuntime {
    val MaxDrainedMessages: Int = 10000
  }
  final class ManualMailbox[M <: AnyRef] private[messaging] (val name: String, val priority: Option[Int], …)
      extends Mailbox[M] {
    def isEmpty: Boolean
    def drain(): Unit
  }
  ```

  `drain()` runs this mailbox's messages, urgent lane first, until both lanes are empty, including those enqueued
  meanwhile. `drainAll()` drains every mailbox in start order, round after round, until all are empty. Both throw
  `IllegalStateException` when a mailbox of the runtime is running a message, and once one call has run
  `MaxDrainedMessages` messages. `isCurrentThread` is true only on the thread running one of the mailbox's messages.
  After `stop()`, `enqueue` drops its message; the `Future` completes at once when both lanes are empty, otherwise
  when a drain empties them.

- [ ] **Step 1: Add the module to `build.sbt`**

  ```scala
  lazy val messagingModule = (project in file("base/messaging"))
    .withId("messaging")
    .dependsOn(
      commonModule % "compile->compile;test->test",
    )
    .disablePlugins(AssemblyPlugin)
    .settings(
      name := "microtonalist-messaging",
      commonSettings,
      libraryDependencies ++= Seq(
        jsr305,
      ),
      coverageSettings(stmt = 80, branch = 80),
    )
  ```

  Run `bin/mtlist-dev-stack restart`, then `mcp__metals__compile-full`. Expected: compilation succeeds, and
  `mcp__metals__list-modules` lists `messaging` and `messaging-test`.

- [ ] **Step 2: Write `MessagingRuntime.scala`**: the three traits of *Interfaces*, with their ScalaDoc and no logic.

- [ ] **Step 3: Write the failing tests**

  ```scala
  object ManualRuntimeTest {
    sealed trait Message { def name: String }
    case class Normal(name: String) extends Message
    case class UrgentMessage(name: String) extends Message with Urgent
  }

  class ManualRuntimeTest extends AnyWordSpec with Matchers {
    import ManualRuntimeTest.*

    abstract class Fixture {
      val runtime: ManualRuntime = ManualRuntime()
      val handled: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
      val business: ManualMailbox[Message] =
        runtime.startMailbox[Message]("Business", Thread.NORM_PRIORITY)(message => handled += message.name)
    }

    "a ManualRuntime" should {
      "record the name and priority of each mailbox it starts, in start order" in new Fixture {
        runtime.startUiMailbox[Message](_ => ())
        runtime.startMailbox[Message]("Track-1", Thread.NORM_PRIORITY + 2)(_ => ())
        runtime.mailboxes.map(mailbox => (mailbox.name, mailbox.priority)) shouldEqual Seq(
          ("Business", Some(Thread.NORM_PRIORITY)), ("UI", None), ("Track-1", Some(Thread.NORM_PRIORITY + 2)))
        runtime.mailbox("Track-1").name shouldEqual "Track-1"
      }

      "run nothing until drained, then the messages in arrival order" in new Fixture {
        business.enqueue(Normal("a"))
        business.enqueue(Normal("b"))
        handled shouldBe empty
        business.isEmpty shouldBe false
        runtime.drainAll()
        handled shouldEqual Seq("a", "b")
        business.isEmpty shouldBe true
      }

      "let urgent messages overtake the queued ones, each lane keeping its order" in new Fixture {
        Seq(Normal("a"), UrgentMessage("u1"), Normal("b"), UrgentMessage("u2")).foreach(business.enqueue)
        business.drain()
        handled shouldEqual Seq("u1", "u2", "a", "b")
      }

      "drain the messages that messages enqueue, across mailboxes, until all are empty" in new Fixture {
        private val track = runtime.startMailbox[Message]("Track-1", Thread.NORM_PRIORITY + 2) { message =>
          handled += message.name
          business.enqueue(Normal(s"${message.name}-ack"))
        }
        private val ui = runtime.startUiMailbox[Message] { message =>
          handled += message.name
          track.enqueue(Normal("from-ui"))
        }
        ui.enqueue(Normal("click"))
        runtime.drainAll()
        handled shouldEqual Seq("click", "from-ui", "from-ui-ack")
      }

      "treat a mailbox's thread as current only while it runs one of its messages there" in new Fixture {
        private var during: Option[(Boolean, Boolean, Boolean)] = None
        private val ui = runtime.startUiMailbox[Message](_ => ())
        private val probe: ManualMailbox[Message] = runtime.startMailbox[Message]("Probe", Thread.NORM_PRIORITY) { _ =>
          during = Some((probe.isCurrentThread, ui.isCurrentThread,
            CompletableFuture.supplyAsync(() => probe.isCurrentThread).join()))
        }
        probe.enqueue(Normal("a"))
        runtime.drainAll()
        during shouldEqual Some((true, false, false))
        probe.isCurrentThread shouldBe false
      }

      "refuse to drain while one of its mailboxes runs a message" in new Fixture {
        private var attempt: Option[Try[Unit]] = None
        private val reentrant = runtime.startMailbox[Message]("Reentrant", Thread.NORM_PRIORITY) { _ =>
          attempt = Some(Try(runtime.drainAll()))
        }
        reentrant.enqueue(Normal("a"))
        runtime.drainAll()
        attempt.get.failed.get shouldBe an[IllegalStateException]
      }

      "fail a drain whose messages keep enqueuing others" in new Fixture {
        private val loop: ManualMailbox[Message] =
          runtime.startMailbox[Message]("Loop", Thread.NORM_PRIORITY)(message => loop.enqueue(message))
        loop.enqueue(Normal("again"))
        an[IllegalStateException] should be thrownBy runtime.drainAll()
      }

      "run the queued messages before completing a stop, and drop the later ones" in new Fixture {
        business.enqueue(Normal("a"))
        private val stopped = business.stop()
        business.enqueue(Normal("late"))
        stopped.isCompleted shouldBe false
        runtime.drainAll()
        handled shouldEqual Seq("a")
        stopped.isCompleted shouldBe true
      }
    }
  }
  ```

  And:
  - `"accept messages enqueued from another thread"`: `CompletableFuture.runAsync(() =>
    business.enqueue(Normal("a"))).join()`, then `drainAll()`; `handled` is `Seq("a")`.
  - `"complete a stop at once when nothing is queued"`: `business.stop().isCompleted` is `true`.

- [ ] **Step 4: Stub `ManualRuntime.scala`** with the signatures of *Interfaces* and `???` bodies, then run
  `sbtn "messaging/testOnly org.calinburloiu.music.microtonalist.messaging.ManualRuntimeTest -- -oNCXEHLOPQRMWS"`.
  Expected: FAIL, every test with `NotImplementedError`, none with a compile error.

- [ ] **Step 5: Implement `ManualRuntime`.** Each mailbox keeps an urgent and a normal `mutable.Queue` under its own
  monitor, and runs a message outside it, so that a handler may enqueue anywhere. The running thread is a `@volatile`
  field set around the handler call in a `try`/`finally`; `isCurrentThread` compares it with `Thread.currentThread`.
  `mailboxes` is safe from any thread. A mailbox's handler exceptions propagate to the test.

- [ ] **Step 6: Run the tests.** Same command. Expected: PASS.

- [ ] **Step 7: Commit**

  ```bash
  git add build.sbt base/messaging
  git commit -m "[#90/#386] Add the messaging module with its runtime interface and ManualRuntime"
  ```

---

### Task 2: Dispatching commands

**Files:**
- Create: `…/messaging/Command.scala`, `…/messaging/MessageBus.scala`, `…/messaging/CommandHandlers.scala` (main)
- Test: `…/messaging/MessageBusTest.scala`

**Interfaces:**
- Consumes: Task 1's runtime interface and `ManualRuntime`.
- Produces:

  ```scala
  trait Command
  @ThreadSafe
  class MessageBus(runtime: MessagingRuntime) extends StrictLogging {
    def dispatch(command: Command): Unit
    def handle[C <: Command](commandClass: Class[C])(handler: C => Unit): Unit
    def requireBusinessThread(): Unit
  }
  ```

  The constructor starts the Business mailbox. `dispatch` resolves the handler on the caller's thread, then enqueues
  the command, even from Business. A Business message runs its body in a `try`: a `NonFatal` exception is logged, and
  the mailbox goes on.

- [ ] **Step 1: Write the failing tests**

  ```scala
  object MessageBusTest {
    sealed trait TunerCommand extends Command
    case class Retune(index: Int) extends TunerCommand
    case class Reset() extends TunerCommand
    trait RigCommand extends Command
    case class TuneRig() extends TunerCommand with RigCommand
    case class Unhandled() extends Command
    case class Explode() extends Command

    abstract class Fixture {
      val runtime: ManualRuntime = ManualRuntime()
      val messageBus: MessageBus = MessageBus(runtime)
      val log: mutable.ArrayBuffer[String] = mutable.ArrayBuffer()
    }
  }

  // In MessageBusTest: "a MessageBus" when { "dispatching a command" should { … } }
  "start the Business mailbox at normal priority" in new Fixture {
    runtime.mailbox("Business").priority shouldEqual Some(Thread.NORM_PRIORITY)
  }

  "run a command's handler on the Business Thread once the Business mailbox drains" in new Fixture {
    private var onBusiness = false
    messageBus.handle(classOf[Retune]) { retune =>
      log += s"retune ${retune.index}"
      onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
    }
    messageBus.dispatch(Retune(1))
    log shouldBe empty
    runtime.drainAll()
    log shouldEqual Seq("retune 1")
    onBusiness shouldBe true
  }

  "run the handler registered for a supertype of the command's class" in new Fixture {
    messageBus.handle(classOf[TunerCommand])(command => log += command.toString)
    messageBus.dispatch(Retune(1))
    messageBus.dispatch(Reset())
    runtime.drainAll()
    log shouldEqual Seq("Retune(1)", "Reset()")
  }

  "throw on the caller's thread when no handler matches the command" in new Fixture {
    messageBus.handle(classOf[Retune])(_ => ())
    an[IllegalStateException] should be thrownBy messageBus.dispatch(Unhandled())
  }

  "throw on the caller's thread when several handlers match the command" in new Fixture {
    messageBus.handle(classOf[TunerCommand])(_ => ())
    messageBus.handle(classOf[RigCommand])(_ => ())
    an[IllegalStateException] should be thrownBy messageBus.dispatch(TuneRig())
  }

  "reject a handler for a class that already has one" in new Fixture {
    messageBus.handle(classOf[Retune])(_ => ())
    an[IllegalArgumentException] should be thrownBy messageBus.handle(classOf[Retune])(_ => ())
  }

  "enqueue a command that a handler dispatches instead of running it inside the handler" in new Fixture {
    messageBus.handle(classOf[Retune]) { _ =>
      messageBus.dispatch(Reset())
      log += "retune done"
    }
    messageBus.handle(classOf[Reset])(_ => log += "reset")
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    log shouldEqual Seq("retune done", "reset")
  }

  "accept a command dispatched from another thread" in new Fixture {
    messageBus.handle(classOf[Retune])(retune => log += s"retune ${retune.index}")
    CompletableFuture.runAsync(() => messageBus.dispatch(Retune(2))).join()
    runtime.drainAll()
    log shouldEqual Seq("retune 2")
  }

  "log a failing handler and go on with the next command" in new Fixture {
    messageBus.handle(classOf[Explode])(_ => throw RuntimeException("boom"))
    messageBus.handle(classOf[Retune])(retune => log += s"retune ${retune.index}")
    private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
      messageBus.dispatch(Explode())
      messageBus.dispatch(Retune(1))
      runtime.drainAll()
    }
    events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Explode() failed.", Some("boom")))
    log shouldEqual Seq("retune 1")
  }

  "throw from requireBusinessThread off the Business Thread" in new Fixture {
    an[IllegalStateException] should be thrownBy messageBus.requireBusinessThread()
  }
  ```

  And `"reject a handler for a subtype of a handled class"` (`TunerCommand`, then `Retune`) and `"reject a handler
  for a supertype of a handled class"` (`Retune`, then `TunerCommand`), each throwing `IllegalArgumentException`.

- [ ] **Step 2: Stub** `Command`, and `MessageBus` with `???` bodies and an empty constructor, then run the
  `MessageBusTest` tests. Expected: FAIL with `NotImplementedError` or `NoSuchElementException` (no Business mailbox),
  no compile error. In this task and the next ones, a stub's `NotImplementedError` thrown inside a handler is caught
  and logged by the layer, so its test fails on an assertion instead.

- [ ] **Step 3: Implement.** `CommandHandlers` registers under a lock, with `require` rejecting a class equal to, a
  subtype of or a supertype of a registered one, and resolves a command's class to the one registered class that is
  it or a supertype, with `isAssignableFrom`. The Business mailbox's internal message type is `private[messaging]`;
  a command's message holds the command and its resolved handler.

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Refactor: cache the resolution.** `dispatch` resolves each command class once: a `ConcurrentHashMap`
  filled with `computeIfAbsent`, which caches no failure, and cleared by each registration. Run the tests. Expected:
  PASS.

- [ ] **Step 6: Commit** `[#90/#386] Dispatch commands to their handler on the Business Thread`.

---

### Task 3: Publishing events

**Files:**
- Create: `…/messaging/Subscription.scala`, `…/messaging/Subscribers.scala` (main)
- Modify: `…/messaging/MessageBus.scala`
- Test: `…/messaging/MessageBusTest.scala`

**Interfaces:**
- Consumes: Task 2's `MessageBus` and its Business message runner.
- Produces:

  ```scala
  trait Subscription {
    def cancel(): Unit // idempotent; skips the deliveries not run yet
  }
  // on MessageBus:
  def publish(event: AnyRef): Unit
  def subscribe[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription
  def subscribeOnUi[E <: AnyRef](eventClass: Class[E])(handler: E => Unit): Subscription
  def requireUiThread(): Unit
  ```

- The algorithm, as section 1 and decision 3 pin it:
  - `publish` takes, from one snapshot of the registry, the subscriptions whose class `isAssignableFrom` the event's
    class, in subscription order, and splits them by thread. The UI ones go at once to the UI mailbox, in one message.
    The Business ones go to the running Business message's local queue on the Business Thread, otherwise to the
    Business mailbox in one message.
  - After running its body, a Business message delivers the events of its local queue in order, events published
    meanwhile included, until the queue is empty.
  - Each subscriber call skips a cancelled subscription, and runs in a `try` that logs a `NonFatal` exception and goes
    on. A UI message delivers its event the same way.
  - The constructor starts the UI mailbox after the Business one.

- [ ] **Step 1: Write the failing tests**

  ```scala
  // In MessageBusTest's companion:
  abstract class Animal
  trait Pet
  case class Dog(name: String) extends Animal with Pet
  case class Wolf(name: String) extends Animal

  // "publishing an event" should { … }
  "deliver an event to a Business subscriber on the Business Thread once the Business mailbox drains" in new Fixture {
    private var onBusiness = false
    messageBus.subscribe(classOf[Dog]) { dog =>
      log += dog.name
      onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
    }
    messageBus.publish(Dog("Rex"))
    log shouldBe empty
    runtime.drainAll()
    log shouldEqual Seq("Rex")
    onBusiness shouldBe true
  }

  "deliver an event to the subscribers of its class and of its supertypes, classes and traits" in new Fixture {
    messageBus.subscribe(classOf[Animal])(event => log += s"animal $event")
    messageBus.subscribe(classOf[Pet])(event => log += s"pet $event")
    messageBus.subscribe(classOf[Dog])(event => log += s"dog $event")
    messageBus.subscribe(classOf[Wolf])(event => log += s"wolf $event")
    messageBus.publish(Dog("Rex"))
    runtime.drainAll()
    log shouldEqual Seq("animal Dog(Rex)", "pet Dog(Rex)", "dog Dog(Rex)")
  }

  "deliver an event once per matching subscription, in subscription order" in new Fixture {
    private val record: AnyRef => Unit = event => log += s"twice $event"
    messageBus.subscribe(classOf[Animal])(event => log += s"animal $event")
    messageBus.subscribe(classOf[Pet])(record)
    messageBus.subscribe(classOf[Animal])(record)
    messageBus.publish(Dog("Rex"))
    runtime.drainAll()
    log shouldEqual Seq("animal Dog(Rex)", "twice Dog(Rex)", "twice Dog(Rex)")
  }

  "deliver the events a handler publishes after it returns and before the next command" in new Fixture {
    messageBus.handle(classOf[Retune]) { retune =>
      messageBus.publish(Dog(s"d${retune.index}"))
      log += s"retune ${retune.index}"
    }
    messageBus.subscribe(classOf[Dog])(dog => log += dog.name)
    messageBus.dispatch(Retune(1))
    messageBus.dispatch(Retune(2))
    runtime.drainAll()
    log shouldEqual Seq("retune 1", "d1", "retune 2", "d2")
  }

  "deliver the events published while draining in the same drain" in new Fixture {
    messageBus.handle(classOf[Retune])(_ => messageBus.publish(Dog("Rex")))
    messageBus.handle(classOf[Reset])(_ => log += "reset")
    messageBus.subscribe(classOf[Dog]) { dog =>
      log += dog.name
      messageBus.publish(Wolf("Grey"))
    }
    messageBus.subscribe(classOf[Wolf])(wolf => log += wolf.name)
    messageBus.dispatch(Retune(1))
    messageBus.dispatch(Reset())
    runtime.drainAll()
    log shouldEqual Seq("Rex", "Grey", "reset")
  }

  "enqueue an event to the UI mailbox as soon as it's published" in new Fixture {
    private var uiQueued = false
    messageBus.subscribeOnUi(classOf[Dog])(_ => ())
    messageBus.handle(classOf[Retune]) { _ =>
      messageBus.publish(Dog("Rex"))
      uiQueued = !runtime.mailbox("UI").isEmpty
    }
    messageBus.dispatch(Retune(1))
    runtime.mailbox("Business").drain()
    uiQueued shouldBe true
  }

  "deliver events to UI subscribers on the UI Thread, in publish order" in new Fixture {
    private var onUi = false
    messageBus.subscribeOnUi(classOf[Dog]) { dog =>
      log += dog.name
      onUi = Try(messageBus.requireUiThread()).isSuccess
    }
    messageBus.handle(classOf[Retune]) { _ =>
      messageBus.publish(Dog("a"))
      messageBus.publish(Dog("b"))
    }
    messageBus.dispatch(Retune(1))
    runtime.mailbox("Business").drain()
    log shouldBe empty
    runtime.mailbox("UI").drain()
    log shouldEqual Seq("a", "b")
    onUi shouldBe true
  }

  "not deliver an event published before its subscription was cancelled" in new Fixture {
    private val business = messageBus.subscribe(classOf[Dog])(dog => log += s"business ${dog.name}")
    private val ui = messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"ui ${dog.name}")
    messageBus.publish(Dog("Rex"))
    business.cancel()
    ui.cancel()
    runtime.drainAll()
    log shouldBe empty
  }

  "give a subscription made while delivering an event the events published after it" in new Fixture {
    messageBus.subscribe(classOf[Wolf]) { _ =>
      messageBus.subscribe(classOf[Dog])(dog => log += s"new ${dog.name}")
      messageBus.publish(Dog("after"))
    }
    messageBus.publish(Dog("before"))
    messageBus.publish(Wolf("Grey"))
    runtime.drainAll()
    log shouldEqual Seq("new after")
  }

  "log a failing Business subscriber and still run the others" in new Fixture {
    messageBus.subscribe(classOf[Dog])(_ => throw RuntimeException("bite"))
    messageBus.subscribe(classOf[Dog])(dog => log += dog.name)
    private val (_, events) = LogCapture.capturing(classOf[MessageBus].getName) {
      messageBus.publish(Dog("Rex"))
      runtime.drainAll()
    }
    events.failuresAt(Level.ERROR) shouldEqual Seq(("A subscriber of Dog(Rex) failed.", Some("bite")))
    log shouldEqual Seq("Rex")
  }

  "throw from requireUiThread off the UI Thread" in new Fixture {
    private var inHandler: Option[Try[Unit]] = None
    messageBus.handle(classOf[Retune])(_ => inHandler = Some(Try(messageBus.requireUiThread())))
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    an[IllegalStateException] should be thrownBy messageBus.requireUiThread()
    inHandler.get.failed.get shouldBe an[IllegalStateException]
  }
  ```

  And:
  - `"start the UI mailbox after the Business one"`: `runtime.mailboxes.map(_.name)` is `Seq("Business", "UI")`.
  - `"deliver an event of any class"`: a subscriber of `classOf[String]` gets `publish("plain")`.
  - `"not deliver to a cancelled subscription"`: `subscribe(…).cancel()` before `publish`; the log stays empty.
  - `"log a failing UI subscriber and still run the others"`: as the Business one, with `subscribeOnUi`.

- [ ] **Step 2: Stub** `Subscription` and the new methods with `???`, then run `MessageBusTest`. Expected: the new
  tests FAIL with `NotImplementedError` or their assertions, Task 2's still pass.

- [ ] **Step 3: Implement** the algorithm above. `Subscribers` holds an `AtomicReference` to an immutable `Vector`
  of subscriptions, each with a `@volatile` active flag that `cancel()` clears before removing it.

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Commit** `[#90/#386] Publish events to Business and UI subscribers`.

---

### Task 4: The causing command

**Files:**
- Modify: `…/messaging/MessageBus.scala`, `…/messaging/ManualRuntime.scala` (test)
- Test: `…/messaging/MessageBusTest.scala`

**Interfaces:**
- Consumes: Tasks 2–3's runners.
- Produces:

  ```scala
  // on MessageBus:
  private[messaging] def causingCommandId: Option[Long]
  // on object ManualRuntime:
  def causingCommandId(using messageBus: MessageBus): Option[Long] // reads messageBus.causingCommandId
  ```

- The algorithm: `dispatch` takes the next id of an `AtomicLong` that starts at 1 and stamps the command's message
  with it. Every Business and UI message carries a stamp, `Option[Long]`. While a runner runs a message, its thread's
  current stamp is the message's, held in a field confined to that thread, one for Business and one for UI.
  `causingCommandId` returns the current stamp of the calling thread: Business's when `isCurrentThread` of the
  Business mailbox, UI's when that of the UI mailbox, `None` otherwise. `publish` stamps what it enqueues with it.

- [ ] **Step 1: Write the failing tests** (`"stamping the causing command" should { … }`)

  ```scala
  "give each dispatched command the next id of one counter" in new Fixture {
    private val ids = mutable.ArrayBuffer[Option[Long]]()
    messageBus.handle(classOf[Retune])(_ => ids += ManualRuntime.causingCommandId(using messageBus))
    (1 to 3).foreach(index => messageBus.dispatch(Retune(index)))
    runtime.drainAll()
    ids shouldEqual Seq(Some(1L), Some(2L), Some(3L))
  }

  "stamp the events a handler publishes with its command's id, for Business and UI subscribers" in new Fixture {
    private val stamps = mutable.ArrayBuffer[(String, Option[Long])]()
    messageBus.handle(classOf[Retune]) { _ =>
      stamps += "handler" -> ManualRuntime.causingCommandId(using messageBus)
      messageBus.publish(Dog("Rex"))
    }
    messageBus.subscribe(classOf[Dog])(_ => stamps += "business" -> ManualRuntime.causingCommandId(using messageBus))
    messageBus.subscribeOnUi(classOf[Dog])(_ => stamps += "ui" -> ManualRuntime.causingCommandId(using messageBus))
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    stamps shouldEqual Seq("handler" -> Some(1L), "business" -> Some(1L), "ui" -> Some(1L))
  }
  ```

  And, each asserting the stamps the subscribers read:
  - `"keep the stamp of the events that Business subscribers publish while draining"`: the handler of `Retune`
    publishes `Dog`, whose Business subscriber publishes `Wolf`; a Business and a UI subscriber of `Wolf` read
    `Some(1L)`.
  - `"keep the stamp of the events that UI subscribers publish"`: the handler publishes `Dog`, whose UI subscriber
    publishes `Wolf`; a Business subscriber of `Wolf` reads `Some(1L)`.
  - `"give a command that a handler dispatches an id of its own"`: the handler of `Retune` reads its stamp and
    dispatches `Reset`, whose handler publishes `Dog`; the stamps read are `Seq("retune" -> Some(1L), "dog" ->
    Some(2L))`.
  - `"stamp nothing published off the Business and UI Threads"`: `publish(Dog("Rex"))` from the test thread; its
    Business and UI subscribers read `None`, and `ManualRuntime.causingCommandId(using messageBus)` on the test
    thread is `None`.

- [ ] **Step 2: Stub** both `causingCommandId`s with `???`. Run `MessageBusTest`. Expected: the new tests FAIL, on
  their assertions or with `NotImplementedError`.

- [ ] **Step 3: Implement** the algorithm above.

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Commit** `[#90/#386] Stamp events with their causing command`.

---

### Task 5: Asking requests

**Files:**
- Modify: `…/messaging/Command.scala`, `…/messaging/MessageBus.scala`, `…/messaging/CommandHandlers.scala`
- Test: `…/messaging/MessageBusTest.scala`

**Interfaces:**
- Consumes: Task 4's stamp.
- Produces:

  ```scala
  trait Request[R] extends Command
  // on MessageBus:
  def ask[R](request: Request[R]): Future[R]
  def handleRequest[R, C <: Request[R]](requestClass: Class[C])(handler: C => Future[R]): Unit
  ```

  `handleRequest` registers in the same registry as `handle`, with the same rejection. Its ScalaDoc says that a
  handler with a value at hand returns it in `Future.successful`.
- Internal, used by Task 6: the stamp becomes the *cause*, the command id plus, for an `ask`, its reply `Promise`.
- The algorithm, after decisions 4–6:
  - `ask` resolves like `dispatch`, and also throws `IllegalStateException` when the class resolves to a `handle`
    handler. It takes the next id of the same counter and enqueues the request with a cause holding a new reply
    `Promise`.
  - A Business message keeps the first `NonFatal` exception caught in it, from its body or a subscriber.
  - Once its drain is over: a kept exception fails the cause's reply. Otherwise, a request handler's `Future` that
    has completed completes the reply. Replies complete with `tryComplete`, so the first outcome wins.
  - A dispatched request's handler runs, and its `Future` is dropped.

- [ ] **Step 1: Write the failing tests** (`"asking a request" should { … }`, with `case class Echo(text: String)
  extends Request[String]` in the companion)

  ```scala
  "complete the reply with the result of the request's handler once the Business mailbox drains" in new Fixture {
    messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))
    private val reply = messageBus.ask(Echo("hi"))
    reply.isCompleted shouldBe false
    runtime.drainAll()
    reply.value shouldEqual Some(Success("hi"))
  }

  "complete the reply only after the events its handler published reached their Business subscribers" in new Fixture {
    private var reply: Future[String] = Future.never
    private var completedAtDelivery: Option[Boolean] = None
    messageBus.handleRequest(classOf[Echo]) { echo =>
      messageBus.publish(Dog("Rex"))
      Future.successful(echo.text)
    }
    messageBus.subscribe(classOf[Dog])(_ => completedAtDelivery = Some(reply.isCompleted))
    reply = messageBus.ask(Echo("hi"))
    runtime.drainAll()
    completedAtDelivery shouldEqual Some(false)
    reply.value shouldEqual Some(Success("hi"))
  }

  "deliver the events a handler published before throwing, then fail the reply and log" in new Fixture {
    messageBus.handleRequest(classOf[Echo]) { _ =>
      messageBus.publish(Dog("Rex"))
      throw RuntimeException("boom")
    }
    messageBus.subscribe(classOf[Dog])(dog => log += dog.name)
    private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
      val reply = messageBus.ask(Echo("hi"))
      runtime.drainAll()
      reply
    }
    log shouldEqual Seq("Rex")
    reply.value.get.failed.get.getMessage shouldEqual "boom"
    events.failuresAt(Level.ERROR) shouldEqual Seq(("The handler of Echo(hi) failed.", Some("boom")))
  }
  ```

  And:
  - `"fail the reply when the handler's Future fails"`: the handler returns
    `Future.failed(RuntimeException("nope"))`; the reply fails with `"nope"`.
  - `"fail the reply when a Business subscriber of its events throws"`: the handler publishes `Dog` and returns
    `Future.successful`; the subscriber throws `RuntimeException("bite")`; the reply fails with `"bite"`.
  - `"accept a request asked from another thread"`: `CompletableFuture.supplyAsync(() =>
    messageBus.ask(Echo("hi"))).join()`; after `drainAll()` its value is `Some(Success("hi"))`.
  - `"give an asked request the next id of the dispatch counter"`: `dispatch(Retune(1))` then `ask(Echo("hi"))`;
    their handlers read `Seq(Some(1L), Some(2L))`.
  - `"run a request's handler and drop its reply when the request is dispatched"`: `dispatch(Echo("hi"))`; the
    handler logs `"hi"`.
  - `"throw on the caller's thread when asking a request whose handler was registered with handle"`:
    `handle(classOf[Echo])(_ => ())`, then `ask(Echo("hi"))` throws `IllegalStateException`.
  - `"reject a request handler for a class that already has one"`: a second `handleRequest(classOf[Echo])` throws
    `IllegalArgumentException`.

- [ ] **Step 2: Stub** `Request`, `ask` and `handleRequest` with `???`. Run `MessageBusTest`. Expected: the new tests
  FAIL with `NotImplementedError`.

- [ ] **Step 3: Implement** the algorithm above. A handler whose `Future` hasn't completed when its message ends
  leaves the reply pending until Task 6.

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Commit** `[#90/#386] Ask requests, replying after their events`.

---

### Task 6: Continuations on the Business Thread

**Files:**
- Create: `…/messaging/FutureContinuations.scala` (main)
- Modify: `…/messaging/MessageBus.scala`, `…/messaging/MessageBusTest.scala` (`given MessageBus = messageBus` in the
  `Fixture`, next to the `val`: a `given` alias that built the bus would be initialised lazily, so the mailboxes
  wouldn't start until the first use)
- Test: `…/messaging/FutureContinuationsTest.scala`

**Interfaces:**
- Consumes: Task 5's cause and after-drain step.
- Produces:

  ```scala
  // FutureContinuations.scala, top level in package messaging:
  extension [T](future: Future[T])(using messageBus: MessageBus) {
    def mapOnBusiness[U](f: T => U): Future[U]
    def flatMapOnBusiness[U](f: T => Future[U]): Future[U]
    def onCompleteOnBusiness(f: Try[T] => Unit): Unit
  }
  // on MessageBus:
  private[messaging] def businessContinuations: ExecutionContext
  ```

- The algorithm:
  - `businessContinuations` returns a new `ExecutionContext` capturing the calling thread's cause. Its `execute`
    enqueues a Business message with the `Runnable` and that cause. The message runs like a handler's: the events it
    publishes are drained after it, and a kept exception fails the cause's reply. Its `reportFailure`, which Scala
    calls inside the `Runnable` with the exception of an `onComplete` function, logs it and keeps it as the message's
    failure.
  - Each extension method calls `requireBusinessThread()`, then `Future`'s own `map`, `flatMap` or `onComplete` with
    `businessContinuations`. So a `map…` function that throws fails the returned `Future`.
  - Once a request handler's message has drained, a `Future` not completed yet completes the reply through
    `businessContinuations`, in a Business message of its own.

- [ ] **Step 1: Write the failing tests** (`"the Business continuations" should { … }`, using `MessageBusTest`'s
  messages and `Fixture`, with `case class Later() extends Request[String]` added to the companion)

  ```scala
  "run mapOnBusiness's function on the Business Thread once the Future completes" in new Fixture {
    private val input = Promise[Int]()
    private var output: Future[Int] = Future.never
    private var onBusiness = false
    messageBus.handle(classOf[Retune]) { _ =>
      output = input.future.mapOnBusiness { value =>
        onBusiness = Try(messageBus.requireBusinessThread()).isSuccess
        value * 2
      }
    }
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    input.success(21)
    output.isCompleted shouldBe false
    runtime.drainAll()
    output.value shouldEqual Some(Success(42))
    onBusiness shouldBe true
  }

  "complete an ask's reply after the drain of the continuation that completed the handler's Future" in new Fixture {
    private val input = Promise[String]()
    private var reply: Future[String] = Future.never
    private var completedAtDelivery: Option[Boolean] = None
    messageBus.handleRequest(classOf[Later]) { _ =>
      input.future.mapOnBusiness { text =>
        messageBus.publish(Dog(text))
        text
      }
    }
    messageBus.subscribe(classOf[Dog])(_ => completedAtDelivery = Some(reply.isCompleted))
    reply = messageBus.ask(Later())
    runtime.drainAll()
    input.success("Rex")
    runtime.drainAll()
    completedAtDelivery shouldEqual Some(false)
    reply.value shouldEqual Some(Success("Rex"))
  }

  "pass a failure to onCompleteOnBusiness, and skip mapOnBusiness's function" in new Fixture {
    private val failure = RuntimeException("unreadable")
    private var completion: Option[Try[Int]] = None
    private var mapped: Future[Int] = Future.never
    messageBus.handle(classOf[Retune]) { _ =>
      val read = Future.failed[Int](failure)
      read.onCompleteOnBusiness(result => completion = Some(result))
      mapped = read.mapOnBusiness { value =>
        log += "mapped"
        value
      }
    }
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    completion shouldEqual Some(Failure(failure))
    mapped.value shouldEqual Some(Failure(failure))
    log shouldBe empty
  }

  "log an onCompleteOnBusiness function that throws, and fail the pending ask of its command" in new Fixture {
    private val pending = Promise[String]()
    messageBus.handleRequest(classOf[Later]) { _ =>
      Future.unit.onCompleteOnBusiness(_ => throw RuntimeException("boom"))
      pending.future
    }
    private val (reply, events) = LogCapture.capturing(classOf[MessageBus].getName) {
      val reply = messageBus.ask(Later())
      runtime.drainAll()
      reply
    }
    reply.value.get.failed.get.getMessage shouldEqual "boom"
    events.failuresAt(Level.ERROR) shouldEqual Seq(("A continuation failed.", Some("boom")))
  }
  ```

  And:
  - `"enqueue the continuation of a completed Future instead of running it in place"`: the handler calls
    `Future.successful(1).onCompleteOnBusiness(_ => log += "continuation")`, then logs `"handler"`; the log is
    `Seq("handler", "continuation")`.
  - `"deliver the events a continuation publishes after it returns"`: `Future.unit.onCompleteOnBusiness` publishes
    `Dog("Rex")`, then logs `"continuation"`; a subscriber logs the name; the log is `Seq("continuation", "Rex")`.
  - `"stamp the events a continuation publishes with the command that started it"`: `Retune`'s handler, id 1,
    continues `input.future` with `onCompleteOnBusiness`, which publishes `Dog`; drain, `dispatch(Reset())`, id 2,
    drain, complete `input`, drain; the `Dog` subscriber reads `Some(1L)`.
  - `"complete an ask's reply through the Business mailbox when the handler's Future completes later"`: the handler
    returns a test-held `Promise`'s future; `drainAll()`; `success("late")` on the test thread; the reply isn't
    completed until the next `drainAll()`, then is `Some(Success("late"))`.
  - `"continue with the Future that flatMapOnBusiness's function returns"`:
    `Future.successful(1).flatMapOnBusiness(value => Future.successful(value + 1))` completes with `Success(2)`.
  - `"fail the Future that mapOnBusiness returns when its function throws"`: it fails with the thrown exception's
    message.
  - `"refuse a Business continuation off the Business Thread"`: on the test thread, each of the three methods on
    `Future.successful(1)` throws `IllegalStateException`. In a UI subscriber, `Try(… .mapOnBusiness(identity))` is a
    `Failure` of `IllegalStateException`.

- [ ] **Step 2: Stub** the extension methods and `businessContinuations` with `???`. Run `FutureContinuationsTest`.
  Expected: FAIL, on assertions or with `NotImplementedError`.

- [ ] **Step 3: Implement** the algorithm above.

- [ ] **Step 4: Run** `FutureContinuationsTest` and `MessageBusTest`. Expected: PASS.

- [ ] **Step 5: Commit** `[#90/#386] Continue Futures on the Business Thread`.

---

### Task 7: Continuations on the UI Thread

**Files:**
- Modify: `…/messaging/FutureContinuations.scala`, `…/messaging/MessageBus.scala`
- Test: `…/messaging/FutureContinuationsTest.scala`

**Interfaces:**
- Consumes: Task 6's continuation machinery.
- Produces:

  ```scala
  extension [T](future: Future[T])(using messageBus: MessageBus) {
    def mapOnUi[U](f: T => U): Future[U]
    def onCompleteOnUi(f: Try[T] => Unit): Unit
  }
  // on MessageBus:
  private[messaging] def uiContinuations: ExecutionContext
  ```

  As Task 6, on the UI mailbox, after `requireUiThread()`. Its `reportFailure` only logs (decision 5).

- [ ] **Step 1: Write the failing tests** (`"the UI continuations" should { … }`)

  ```scala
  "run a mapOnUi continuation of an ask's reply after the UI subscribers of its events" in new Fixture {
    messageBus.handleRequest(classOf[Echo]) { echo =>
      messageBus.publish(Dog(echo.text))
      Future.successful(echo.text)
    }
    messageBus.subscribeOnUi(classOf[Dog])(dog => log += s"event ${dog.name}")
    messageBus.subscribeOnUi(classOf[Wolf]) { wolf =>
      messageBus.ask(Echo(wolf.name)).mapOnUi(text => log += s"reply $text")
    }
    messageBus.publish(Wolf("Grey"))
    runtime.drainAll()
    log shouldEqual Seq("event Grey", "reply Grey")
  }
  ```

  And:
  - `"run mapOnUi's and onCompleteOnUi's functions on the UI Thread"`: in a UI subscriber,
    `Future.successful(1).mapOnUi` and `.onCompleteOnUi` each read `Try(messageBus.requireUiThread()).isSuccess`;
    both are `true`, and the `mapOnUi` result is `Success` of the mapped value.
  - `"log an onCompleteOnUi function that throws"`: the error log is `Seq(("A continuation failed.", Some("boom")))`.
  - `"refuse a UI continuation off the UI Thread"`: on the test thread both methods throw `IllegalStateException`;
    in a Business handler, `Try(… .mapOnUi(identity))` is a `Failure` of `IllegalStateException`.

- [ ] **Step 2: Stub** with `???`; run `FutureContinuationsTest`. Expected: the new tests FAIL, on assertions or with
  `NotImplementedError`.

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run the tests.** Expected: PASS.

- [ ] **Step 5: Commit** `[#90/#386] Continue Futures on the UI Thread`.

---

### Task 8: The refused `Await`

**Files:**
- Modify: `…/messaging/MessageBus.scala`
- Test: `…/messaging/MessageBusTest.scala`

**Interfaces:**
- Consumes: the Business and UI runners.
- Produces: no API. Each Business message, its body and its drain, and each UI message run inside
  `BlockContext.withBlockContext` with a private `BlockContext` whose `blockOn` throws `IllegalStateException`. Its
  message names the thread and points to the continuations. `Await` consults it only for a `Future` that hasn't
  completed.

- [ ] **Step 1: Write the failing tests** (`"awaiting a Future" should { … }`, with
  `private val RefusedAwaitTimeout: FiniteDuration = 1.second` in the companion, commented as waited for only when the
  refusal is missing)

  ```scala
  "throw instead of blocking in a Business handler" in new Fixture {
    private var attempt: Option[Try[Int]] = None
    messageBus.handle(classOf[Retune]) { _ =>
      attempt = Some(Try(Await.result(Promise[Int]().future, RefusedAwaitTimeout)))
    }
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    attempt.get.failed.get shouldBe an[IllegalStateException]
  }

  "throw instead of deadlocking when a handler awaits an ask's reply" in new Fixture {
    private var attempt: Option[Try[String]] = None
    messageBus.handleRequest(classOf[Echo])(echo => Future.successful(echo.text))
    messageBus.handle(classOf[Retune]) { _ =>
      attempt = Some(Try(Await.result(messageBus.ask(Echo("hi")), RefusedAwaitTimeout)))
    }
    messageBus.dispatch(Retune(1))
    runtime.drainAll()
    attempt.get.failed.get shouldBe an[IllegalStateException]
  }
  ```

  And, each awaiting `Promise[Int]().future` and expecting a `Failure` of `IllegalStateException`:
  - `"throw instead of blocking in a Business subscriber"`, in a subscriber draining a handler's event;
  - `"throw instead of blocking in a Business continuation"`, in `Future.unit.onCompleteOnBusiness`;
  - `"throw instead of blocking in a UI subscriber"`.

  And `"return the result of a completed Future in a handler"`: `Try(Await.result(Future.successful(1),
  RefusedAwaitTimeout))` is `Success(1)`.

- [ ] **Step 2: Run `MessageBusTest`** before implementing. Expected: the refusal tests FAIL, each after
  `RefusedAwaitTimeout`, with a `TimeoutException` where `IllegalStateException` is expected; the completed-`Future`
  test passes.

- [ ] **Step 3: Implement** as *Interfaces* says.

- [ ] **Step 4: Run the tests.** Expected: PASS, with no wait.

- [ ] **Step 5: Commit** `[#90/#386] Refuse to await on the Business and UI Threads`.

---

### Task 9: Final checks

**Files:**
- Create: `base/messaging/README.md`, `base/messaging/CLAUDE.md`, `base/messaging/docs/ARCHITECTURE.md`
- Modify: `base/README.md`, `docs/architecture/module-overview.md`, `AGENTS.md`, and what the `architecture-docs`
  skill finds

- [ ] **Step 1: Module tests.** `sbtn "messaging/testOnly * -- -oNCXEHLOPQRMWS"`. Expected: all pass.

- [ ] **Step 2: Coverage.** Invoke the `scoverage-inspector` skill and follow it for `messaging`. Expected: at least
  80% statements and 80% branches, and no other module's floor broken. Cover a gap with a test of the behavior
  missed; check scala-logging's macro branches against the XML before chasing them.

- [ ] **Step 3: Full suite.** `sbtn "root/testOnly * -- -oNCXEHLOPQRMWS"`. Expected: all pass.

- [ ] **Step 4: Documentation.** Invoke the `architecture-docs` skill, then:
  - write `messaging`'s three files, as the skill shapes them. `ARCHITECTURE.md` covers the messages, a Business
    message's run (body, drain, reply), the causing command, the continuations, the errors, the refused `Await`, the
    runtime interface and `ManualRuntime`, and how another module's tests reach it (`"compile->compile;test->test"`).
    Under *Subject to change*: unused until #388, and the Pekko runtime is #387;
  - add `messaging` to `base/README.md` and, as a main module, to `module-overview.md`, its table and its diagram
    (`messaging --> common`);
  - in `AGENTS.md`, *Symbol tool targets*, add `messaging` to the modules that `app` doesn't depend on, until #388;
  - check the ScalaDoc of every public identifier, `ManualRuntime`'s included, against `doc-conventions.md`.

- [ ] **Step 5: Lint.** `sbtn fixLint`. Fix what it reports by hand and rerun until it passes.

- [ ] **Step 6: Commit** `[#90/#386] Document the messaging module`, plus the lint fixes if any.

- [ ] **Step 7: Hand over.** Don't push or open a PR without asking the user; when they ask, open the PR onto `main`
  with the `contributing` skill, closing #386.
