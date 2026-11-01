# MCP servers in Scala

- **Date:** 2026-10-25, at commit `cca2c673`. Versions, star counts and APIs are as of that date.
- **Servers:** first the QA MCP server of #397, over stdio. Later, one inside the app, over HTTP, through which an agent
  opens compositions, reads the tunings and changes the current tuning.

## Decision

Our MCP servers use the official [MCP Java SDK](https://github.com/modelcontextprotocol/java-sdk), on Jackson 2,
through one module of ours, `mcp`, the only one that depends on the SDK. It holds the shared JSON mapper and a small
helper that turns a tool written with play-json into an SDK tool. There is no MCP library of our own, and no model of
MCP that hides the SDK.

## Choosing the library

A library of our own, on play-json, would have to follow the MCP specification, which gets a few revisions a year: work
away from microtonal music. No library uses play-json, and none written in Scala uses Jackson, which play-json is built
on: circe, zio-json and the like parse JSON themselves.

| Library | Stars | Latest | Basis |
| ------- | ----- | ------ | ----- |
| [Java SDK](https://github.com/modelcontextprotocol/java-sdk) (official) | 3,741 | 2.1.0, 2026-10-16 | Java 17, Jackson 2 or 3, Reactor |
| [Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) (official) | 1,477 | | kotlinx.serialization, Ktor, coroutines |
| [chimp](https://github.com/softwaremill/chimp) (SoftwareMill) | 103 | 0.6.0, 2026-09-22 | Scala 3, circe, Tapir; Ox, ZIO or Pekko for streaming |
| [fast-mcp-scala](https://github.com/TJC-LP/fast-mcp-scala) | 24 | | Scala 3, ZIO, zio-json and zio-http, over the Java SDK |
| mcp-scala, indoorvivants/mcp, scala-effect-mcp | 13–37 | | Small projects |

The Java SDK, because:

- **It is maintained for the long run,** by the MCP project with the Spring AI team. It follows semantic versioning,
  deprecates an API before removing it, writes a migration guide for each major release, keeps patching the security of
  the previous release lines, and raises its dependencies' versions conservatively.
- **It conforms:** it passes the 40 server scenarios of the official conformance suite.
- **It adds no JSON library:** we already have Jackson 2, through play-json.
- **Its tool API takes generic values,** `Map` and `Object`, which play-json reaches through Jackson, with no string in
  between; see [JSON](#json).

The others:

- **chimp** is the only strong Scala candidate, but it brings a second JSON stack: each tool's input and output types
  would need circe codecs and a Tapir schema besides their play-json formats. It is pre-1.0. It derives the input
  schemas from case classes, which we write by hand instead.
- **fast-mcp-scala** wraps the Java SDK in ZIO: a second layer between us and the SDK, which can lag behind it.
- **The Kotlin SDK** brings Kotlin's standard library, coroutines and Ktor.

## Dependencies

- `io.modelcontextprotocol.sdk:mcp-core` and `mcp-json-jackson2`, at one version. Not the `mcp` bundle: it brings
  Jackson 3, whose `tools.jackson` packages would be a second Jackson next to play-json's.
- They bring Reactor, SLF4J, Jackson's annotations and networknt's JSON Schema validator. The Jakarta Servlet API is
  `provided`: only the HTTP transports need it.
- **Jackson pin.** play-json 3.0.6 brings Jackson 2.14.3: core, annotations, databind, and the jdk8 and jsr310
  datatypes. The SDK 2.1.0 needs databind 2.21.1. Conflict resolution would raise databind, core and annotations, but
  leave the datatypes at 2.14, and Jackson doesn't support mixing minor versions. So `jacksonVersion`, in
  `project/Dependencies.scala`, follows the SDK's Jackson, and `dependencyOverrides` pins every Jackson artifact to it.
  play-json 3.1.0-M10 already depends on Jackson 2.21.2. It also adds `jackson-module-scala`, which fails at startup
  next to a databind of another minor version, so the pin covers it once we upgrade.

## JSON

`JacksonMcpJsonMapper` wraps an `ObjectMapper` that we create, with play-json's `PlayJsonMapperModule` registered, so
that Jackson reads and writes `JsValue` itself. The SDK's default is a bare `new ObjectMapper()`, so ours changes
nothing else.

| SDK field | Type | From play-json |
| --------- | ---- | -------------- |
| `Tool.inputSchema` | `Map<String, Object>` | The tool's `JsObject`, converted once, when the server starts |
| `CallToolRequest.arguments` | `Map<String, Object>` | `objectMapper.convertValue(arguments, classOf[JsValue])`, through Jackson's token buffer |
| `CallToolResult.structuredContent` | `Object` | The result's `JsObject`, as it is |

MCP requires structured content to be an object, hence an `OWrites` for each result. The result also goes, as a
string, in a `TextContent` block, as the specification recommends for clients that don't read structured content.

## The `mcp` module

A module group `mcp`, with an `mcp` module, holds:

- the `ObjectMapper` and its `McpJsonMapper`;
- the helper, which builds an SDK `SyncToolSpecification` from an input `Reads`, an output `OWrites`, an input schema
  and a function. The tool reads its arguments, calls the function and writes the result, turning a failure into a
  result with `isError` set;
- the other forms of the helper that the servers need, e.g. for a tool that returns an image.

```scala
val sendMidi = PlayJsonMcp.tool[SendMidiArgs, SendMidiResult]("send_midi", "Sends MIDI to a device", sendMidiSchema) {
  args => midiPorts.send(args)
}

McpServer.sync(StdioServerTransportProvider(PlayJsonMcp.jsonMapper))
  .serverInfo("microtonalist-qa", version)
  .tools(sendMidi)
  .build()
```

- **A helper, not a wrapper.** Its signatures use the SDK's types, and each server builds its `McpServer` with the SDK.
  What the helper doesn't cover, such as resources, progress or elicitation, a server writes with the SDK directly. A
  form joins the helper once two tools need it.
- **The sync API,** `McpServer.sync`, so that Reactor's types stay inside the SDK.
- **Logic in the services.** A tool reads its arguments, calls a service of the domain, such as `TuningService`, and
  writes the result. The services are shared with the GUI, and stay if the MCP library changes.
- **Input schemas by hand.** play-json derives no JSON Schema, so each tool's schema is a `JsObject` next to its
  `Reads`. The SDK checks the schema against the JSON Schema 2020-12 meta-schema when the server is built, and each
  call's arguments against the schema before the tool runs, answering a mismatch with a tool error.

## Transports

| Server | Transport | Needs |
| ------ | --------- | ----- |
| QA, #397 | stdio, `StdioServerTransportProvider` | Nothing more. Standard output carries the protocol, so the server logs to standard error or a file, with a Logback configuration of its own. |
| The app's | Streamable HTTP, `HttpServletStreamableServerTransportProvider`, or the stateless `HttpServletStatelessServerTransport` | An embedded servlet container, such as Jetty, in a module of its own, `mcp-http`, so that only the app gets it |

The SDK's SSE transports are deprecated.

## Testing

- A tool's logic is tested through its service, as now.
- The helper's tests build the SDK's own `CallToolRequest` and call the handler that the helper built, so that a
  breaking SDK release fails them when we upgrade.
- An integration test for each server drives it through its transport. Over stdio, `StdioServerTransportProvider` also
  takes an `InputStream` and an `OutputStream`, so the test writes JSON-RPC lines to a pipe and reads the replies, with
  no process.
- Conformance to the specification is the SDK's job.

## Upgrades

- Both of the SDK's major releases so far broke its tool API. 1.0 replaced the tool handler, `tool()` and the `call`
  field, with `toolCall()` and `callHandler`, and removed the `CallToolResult` constructors. 2.0 changed
  `Tool.inputSchema` from `JsonSchema` to `Map<String, Object>`, and deprecated the builders without arguments.
- 2.2.0-M1 adds a server for MCP 2026-07-28, in `io.modelcontextprotocol.modern`: stateless, and independent of
  `McpSchema`. Moving to it changes the helper and the servers' setup, not the tools' code.
- A deprecation shows as a compiler warning, and new code adds no use of a deprecated API.
- The SDK raises a dependency's minor version only in a minor release of its own, and says so in the release notes:
  check the Jackson pin then.

## Alternatives considered

- **Our own MCP library on play-json,** in an `mcp` group: only the parts we use, i.e. JSON-RPC, `initialize`, `ping`,
  tools and stdio. But every revision of the specification would be ours to follow, and the app's HTTP server would
  extend it.
- **A layer that hides the SDK** behind a model of MCP of our own, so that the library could be swapped. With an
  official SDK a swap is unlikely, and every MCP feature would need a counterpart in the layer before a server could use
  it: our own library again. If we ever switch, the services stay, and `mcp` and the tool declarations change.
- **The SDK's `mcp` bundle, on Jackson 3:** its default, but a second Jackson next to play-json's, with no bridge to
  `JsValue`. To revisit once play-json moves to Jackson 3.

## Sources

- The Java SDK's [README](https://github.com/modelcontextprotocol/java-sdk/blob/main/README.md),
  [versioning policy](https://github.com/modelcontextprotocol/java-sdk/blob/main/VERSIONING.md),
  [dependency policy](https://github.com/modelcontextprotocol/java-sdk/blob/main/DEPENDENCY_POLICY.md),
  migration guides to [1.0](https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-1.0.md) and
  [2.0](https://github.com/modelcontextprotocol/java-sdk/blob/main/MIGRATION-2.0.md), and
  [releases](https://github.com/modelcontextprotocol/java-sdk/releases).
- The sources of the SDK 2.1.0: `McpSchema`, `McpJsonMapper`, `JacksonMcpJsonMapper` and their POMs.
- [chimp's build](https://github.com/softwaremill/chimp/blob/master/build.sbt) and
  [fast-mcp-scala's](https://github.com/TJC-LP/fast-mcp-scala/blob/main/build.mill).
- The POMs of [play-json](https://repo1.maven.org/maven2/org/playframework/play-json_3/) 3.0.6 and 3.1.0-M10, and its
  `JacksonJson.scala`.
