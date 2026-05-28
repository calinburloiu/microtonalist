# Agent rules improvements

# Prompt

Your task is to improve agents instructions from this repository, like `CLAUDE.md` files, rules and documentation.

The main issue today is that the root `CLAUDE.md` file became too large and exhaustively covers every use case. We want to break it in multiple files. Some current instructions are meant for both agents and humans, so they need to stay in a common location. Others should not usually be used by agents, for example, instructions for building with sbt are usually used by humans and agents should **only** use them if Metals MCP is not available.

There are multiple kinds of changes we need to make:

- **Rules**. Cross-cutting instructions for file name patterns.
- **Subdirectory-based instructions**. Mainly used for module-specific instructions.
- **Breaking root `CLAUDE.md`**. Separate files for various topics not covered in the bullets above.

## Subdirectory-based instructions

Subdirectory-based `CLAUDE.md` files point to specific instructions for those subdirectories.

For each module `$MODULE` (defined in sbt as a project), we want its `CLAUDE.md` file to import a markdown document with its architecture from `docs/architecture/$MODULE/`.

## Breaking root `CLAUDE.md`

Locations where things can be moved:
- All markdown files that should be consumed by agents and are not rules should be in `docs/agents/`.
- All markdown files about the development process and workflow that should be consumed by humans, and only optionally, if really required, by agents, are in `docs/development/`. The `README.md` here serves as an introduction and table of contents, make sure you keep it up to date.
- All markdown files about the codebase architecture are in `docs/architecture/`. It can be consumed by both humans and agents. The `README.md` here serves as an introduction and table of contents, make sure you keep it up to date. Each module gets its own subdirectory here. As mentioned, module-local `CLAUDE.md` files link here.

Split it in multiple files:
- From section "sbt invocations: prefer the BSP server via `sbtn`", from "Once-per-session check" only keep in `CLAUDE.md` the step to "Detect the running stack". Instruct to go to a separate file with next steps if the running stack is not running. Extract the removed steps to that file. The information about stopping the dev-stack might also go there.
- Since agents should normally use Metals MCP for compiling, the "Compiling" section should be moved to the human instructions directory, `docs/development/`. I link to that document can be added such that the agent knows where to go if Metals MCP is not available, but it should normally be loaded in context.
- Move the contents from "Coding Conventions" section to two separate files in `docs/development/`. `CLAUDE.md` should `@import` them and ask the agent to follow them when writing code. Currently, the section mixes test conventions and production code conventions. We would like to separate them in:
    - `coding-conventions.md` file contains general (mainly production code) coding conventions for Scala.
    - `test-conventions.md` file contains conventions for Scala test files.
        * I think that it makes sense to move lines 150-168 from "Test" section to this file, but we can discuss this.
        * Subsection "Shared test utilities" is currently misplaced under "Coverage". It seems to me that it should be in `test-conventions.md`.
- Move the "Test" section to its own agents file in `docs/agents/test.md`. Create a similar file for human developers in `docs/development/test.md`. It's find to have some duplication, they have different audience.
- Move the contents of "Coverage" section to `docs/agents/coverage.md`. Note that we already have a dedicated file for human developers.
