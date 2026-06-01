# Building and Compiling

This document describeds how to compile and build the project with sbt. This is primarily for human developers; coding
agents should normally compile via the Metals MCP (`mcp__metals__compile-full` / `mcp__metals__compile-module`) and only
fall back to sbt when the Metals MCP is unavailable or for a final full build / fat JAR assembly.

When the development stack is running, prefer `sbtn` over `sbt` so commands execute on the long-lived BSP server rather
than spawning a fresh `sbt` JVM. See the [Development Setup](README.md) guide and
[`../agents/dev-stack.md`](../agents/dev-stack.md). Using `sbtn` with the dev-stack also helps avoiding concurrency
issues when multiple builds would write in the same target subdirectory.

## Project layout

The repository is built using SBT 1, Scala 3, and Java 23. It is split into multiple SBT projects that act as modules,
libraries, or separate executable applications — we simply call each of those SBT projects modules. Each one is located
in the repository root. Check `build.sbt` for details. The `root` SBT project aggregates all the other projects. The
executable application is in the `app` SBT project.

## Compiling

Compiling the whole `root` SBT project:

```bash
sbtn compile
```

For small changes, it is recommended to only compile individual modules. Compiling a single module
`${MODULE}`:

```bash
sbtn "${MODULE}/compile"
```

## Building the fat JAR

Building the fat JAR for the executable application:

```bash
sbtn assembly
```

It is recommended to compile, build, or test the whole project before committing changes.
