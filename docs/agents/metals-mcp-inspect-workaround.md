# Metals MCP `inspect` Workaround (Agents)

`mcp__metals__inspect` returns no members when its build target is given as `module` (or through `searchAllTargets`),
while still printing `[Inspected from '<module>' module]`
([scalameta/metals#8880](https://github.com/scalameta/metals/issues/8880)). Choose the target module as for the other
symbol tools (see "Symbol tool targets" in the root `CLAUDE.md`), then pass the absolute path of any source file of that
module as `fileInFocus` instead; a file under `src/test/scala` selects the `<module>-test` target.

Once that issue is fixed, delete this document and its pointer in `CLAUDE.md`, and pass `module` to `inspect` as to the
other symbol tools.
