# `ai` module group

Support for AI agents, which reach microtonalist through the Model Context Protocol (MCP).

| Module | Description | Root package |
| ------ | ----------- | ------------ |
| [`mcp`](mcp/docs/ARCHITECTURE.md) | The base of the MCP servers: builds their tools on the MCP Java SDK with play-json | `org.calinburloiu.music.microtonalist.ai.mcp` |

`mcp` is the only module that depends on the MCP Java SDK. The servers live in the areas they serve, such as the QA
server, `qa-mcp`, in the `testbench` group. [`docs/research/mcp.md`](../docs/research/mcp.md) gives the choice of the
SDK and the plan for the app's own MCP server.
