# `base` module group

Infrastructure that the other groups build on: shared utilities, and the event bus with its threading model. It holds
no musical concepts.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`common`](common/docs/ARCHITECTURE.md) | Shared utilities, such as the `Plugin` extension point | `org.calinburloiu.music.microtonalist.common` |
| [`businessync`](businessync/docs/ARCHITECTURE.md) | Event bus and threading: domain logic on the Business Thread, GUI updates on the UI Thread | `org.calinburloiu.businessync` |
| `common-test-utils` | Test helpers shared by the tests of several modules, such as `LogCapture` | `org.calinburloiu.music.microtonalist.common` |
