# Architecture Documents Prompt

Your task is to factor out the content of the "Architecture" section from `CLAUDE.md`.
- **Keep**. Crucial architecture information will still need to be loaded in context for each session. But we still want to declutter `CLAUDE.md`, so we are going to only leave a phrase in that section and  use an `@import` from a new file `docs/agents/architecture.md` with the crucial information.
- **Move**. Context-specific information will not be loaded anymore for each session. We already have a scaffold mechanism for loading architecture documents via subdirectory `CLAUDE.md` files. Check each module `CLAUDE.md` file and how each imports a corresponding document from `docs/architecture/$MODULE/`. Those imported documents are placeholders right now, but you task is to add details to them.

The sections below details what we want to **keep** in context for each session and what we want to **move** to an architecture document.

Architecture documents from `docs/architecture/` are meant to be consumed by both humans and agents. 

You may add generic details for humans to `docs/architecture/README.md`, including things that are currently in `CLAUDE.md`.

Section "Strategy" bellow describes how should this task be implemented.

## Keep

Keep the following subsections:
- "Module Overview"
- "Key Domain Concepts"
- "Data Flow"

## Move

- "Format / Serialization" subsection content goes to `docs/architecture/format/`.
- "Threading Model (Businessync)" subsection goes to `docs/architecture/businessync/`.
- "Application Entry Point" subsection content goes to `docs/architecture/app/`.

## Strategy

Use `/superpowers:brainstorming` skill. Use the current branch. When finishing the work, an issue should be created and a PR.

Spawn a subagent with the same model and effort for each module. Each should investigate the architecture of its module by exploring its code and ScalaDocs. It may use Metals MCP to understand relations between classes. It should write architectural information in its module docs directory (`docs/architecture/$MODULE/`) in parallel; there shouldn't be any write conflicts because they use different directories.

When all subagents finish, the main agent ensures that module architecture outputs are consistent and make sense as a whole. It can then update the root `docs/architecture/README.md` for humans and `docs/agents/architecture.md` for agents.

All agents may study the open issues with milestone `Architecture` where appropriate to understand how the architecture is planned to change in the future. They may add details about this stating when some things are temporary and subject to change. The following Wiki pages can also be examined where applicable: https://github.com/calinburloiu/microtonalist/wiki/Threading-Model, https://github.com/calinburloiu/microtonalist/wiki/End%E2%80%90to%E2%80%90end-Flows.

In `CLAUDE.md`, the "Coding Workflow" section needs to updated (see the bullet with "TODO"). We want the coding agent to have a task before coding that explores the architecture documents that are strictly relevant for the prompt. Either `CLAUDE.md` or the imported `docs/agents/architecture.md` should inform the agent how architecture documents are organized to accomplish this initial task.
