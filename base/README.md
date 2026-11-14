# `base` module group

Infrastructure that the other groups build on: shared utilities, and the event bus with its threading model. It holds
no musical concepts.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`common`](common/docs/ARCHITECTURE.md) | Shared utilities, such as the `Plugin` extension point | `org.calinburloiu.music.microtonalist.common` |
| [`messaging`](messaging/docs/ARCHITECTURE.md) | Messaging and threading: commands on the Business Thread, events and `Future` continuations on the Business or UI Thread | `org.calinburloiu.music.microtonalist.messaging` |
| [`messaging-pekko`](messaging-pekko/docs/ARCHITECTURE.md) | The Pekko Typed runtime of `messaging`: pinned mailboxes on named threads at a given priority, the UI mailbox, startup and shutdown | `org.calinburloiu.music.microtonalist.messaging.pekko` |
| [`businessync`](businessync/docs/ARCHITECTURE.md) | Event bus and threading: domain logic on the Business Thread, GUI updates on the UI Thread | `org.calinburloiu.businessync` |
