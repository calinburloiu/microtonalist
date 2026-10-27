# `testbench` module group

Tooling that tests the app from outside it, as a user would: the QA MCP server and the QA driver of the `qa` agent.

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`qa-driver`](qa-driver/docs/ARCHITECTURE.md) | A Java agent in the app's JVM that reads and drives its JavaFX scene graph for the QA server | `org.calinburloiu.music.microtonalist.testbench.driver` |
| [`qa-mcp`](qa-mcp/docs/ARCHITECTURE.md) | The QA MCP server, which starts the app with virtual MIDI ports and the QA driver, and gives the `qa` agent the tools to drive it | `org.calinburloiu.music.microtonalist.testbench.qa` |

No module of the app depends on the group: the QA server reaches the app only by starting its fat JAR, with the driver
loaded through `-javaagent`. `qa-mcp` depends on `qa-driver` for the protocol between them, in the driver's `protocol`
package.

QA runs on macOS only, with SendMIDI and ReceiveMIDI, which the server runs as external programs; CI runs the modules'
tests without them. The [`qa` skill](../.claude/skills/qa/SKILL.md) says how an agent uses the server, and
[`docs/development/qa.md`](../docs/development/qa.md) how to set up a Mac and run a test plan by hand.
