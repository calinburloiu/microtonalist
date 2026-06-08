# License Headers

Every source file in this repository starts with the Apache 2.0 license header. This document describes the header
format, how the headers are kept out of agent context at read time, and how they are added to new files and enforced.

## Header format

The header is a ~15-line block followed by a single blank line before the code. The comment style depends on the file
type:

- **Block comment** (`.scala`, `.java`, `.js`, `.css`, `.sbt`):

  ```scala
  /*
   * Copyright <year> Calin-Andrei Burloiu
   *
   *    Licensed under the Apache License, Version 2.0 (the "License");
   *    ...
   *    limitations under the License.
   */

  package ...
  ```

- **XML / HTML comment** (`.xml`, `.fxml`, `.html`): the same text inside `<!-- ... -->`.
- **Line comment** (`.py`, `.properties`, `.sh`/`.bash`): the same text with each line prefixed by `#` (after the
  shebang, for scripts).

The `Copyright <year>` line varies by the file's creation year (2020/2021/2025/2026 are all present in the tree); the
checks below do not require a specific year.

## Keeping the header out of agent context (read-skip hook)

The header is pure boilerplate that costs tokens every time a coding agent reads a file, yet carries nothing the agent
needs. A committed Claude Code hook hides it at read time **without deleting it from disk and without renumbering code
lines**:

- **Hook script:** [`.claude/hooks/license-header-read-skip.sh`](../../.claude/hooks/license-header-read-skip.sh)
- **Registration:** [`.claude/settings.json`](../../.claude/settings.json), a `PreToolUse` hook on the `Read` tool.

It is a `PreToolUse` hook because — unlike `PostToolUse`, which is observational and cannot modify a tool's output —
`PreToolUse` can rewrite a tool's **input** via the `updatedInput` field. The hook detects the header (it looks for the
`Licensed under the Apache License` marker in the first lines) and sets the `Read` tool's `offset` so the read starts at
the first line of actual code. Because `Read` with an `offset` omits the skipped lines rather than renumbering them,
**real line numbers are preserved**: a Scala file appears to start at `package` on line 17, and line 42 is still line
42.

The hook is conservative and **fails open** — on any non-match, missing `jq`, or unexpected input it exits 0 with no
output, so the read runs unchanged. Specifically it passes the file through untouched when:

- the caller already set `offset` or `limit` (a request for a specific range);
- the file is missing, unreadable, or has no Apache marker in its first lines;
- **line 1 is a shebang (`#!…`)** — a single `offset` cannot keep the shebang while skipping a header that starts on
  line 2, and a shebang must never be dropped, so these few script files are read with their header visible.

### Escape hatch

To see the full file including the header, `Read` it with an explicit **`offset: 1`** (the hook treats an explicit
range as "the caller wants exactly this" and passes it through). `Edit`/`Write` are unaffected — the header still exists
on disk, so string matches near the top of a file still work; re-`Read` with `offset: 1` first if you need to see those
lines.

> **Out of scope:** reads done through Bash (`cat`, `head`, …) are not rewritten — agents are told to prefer the `Read`
> tool. The note in [`AGENTS.md`](../../AGENTS.md) covers the rest.

## Adding headers to new files (`addlicense`)

New files get their header automatically via Google's [`addlicense`](https://github.com/google/addlicense), driven by a
custom template so the wording matches the existing headers.

- **Template:** [`.license-header.tmpl`](../../.license-header.tmpl) — a Go `text/template` with `{{.Year}}` and
  `{{.Holder}}` placeholders. `addlicense` wraps it in the right comment style per file extension. For block-comment
  files (`.scala` etc.) the result is **byte-for-byte identical** to the existing headers; for `#`- and XML-style files
  it is equivalent but does not reproduce IDE-specific cosmetic details (the leading blank `#` line, or the ` ~ ` XML
  continuation prefix).

### Local git hook

[`.githooks/pre-commit`](../../.githooks/pre-commit) runs `addlicense` in **add mode** over the staged in-scope files
and re-stages any it modified, so new files are headered on commit. Existing files (which already contain a `Copyright`
line) are left untouched. Enable it once per clone:

```bash
git config core.hooksPath .githooks
```

If `addlicense` is not on your `PATH`, the hook skips quietly and lets CI catch any omissions. Install it with:

```bash
go install github.com/google/addlicense@latest   # needs Go; binary lands in "$(go env GOPATH)/bin"
```

### CI enforcement

The `license-headers` job in [`.github/workflows/scala.yml`](../../.github/workflows/scala.yml) installs `addlicense`
and runs `addlicense -check` over the in-scope tracked files, failing the build if any lack a header. This covers
contributors who did not enable the local hook.

## In-scope file types

`addlicense` is run over: `.scala`, `.java`, `.py`, `.sh`/`.bash`, `.html`, `.xml`, `.js`, `.css`, `.sbt`,
`.properties`. Notes and carve-outs:

- **`-check` (CI)** is content-based (it looks for a `Copyright` line), so it enforces headers on **all** of the above,
  including `.sbt`.
- **`.fxml` is not supported by `addlicense`** — it silently skips any extension it doesn't recognise (both `-check` and
  add mode are no-ops for `.fxml`). Add `.fxml` headers by hand; CI does not enforce them.
- **Add mode (git hook)** excludes `.sbt`: `addlicense` does not know its comment style and would insert a wrong (`#`)
  header. There is one `.sbt` file (`build.sbt`, already headered); add headers to any new `.sbt` files by hand.
- **Test-resource fixtures** under `**/tests/resources/**` are ignored (via `-ignore`): e.g.
  `.claude/mcp/.../scoverage-sample.xml` must stay byte-exact valid XML (a comment cannot precede its `<?xml …?>`
  declaration).
- The header-less Python tooling under [`.claude/mcp/`](../../.claude/mcp/) **is** in scope; `addlicense` inserts the
  `#` header above the module docstring, which remains the module's first statement.
- Generated / build-output directories (`target*`) are never committed, so they are not matched by `git ls-files`.

## Verifying locally

```bash
ADDLICENSE="$(go env GOPATH)/bin/addlicense"
git ls-files '*.scala' '*.java' '*.py' '*.sh' '*.bash' '*.html' '*.xml' '*.js' '*.css' '*.sbt' '*.properties' \
  | xargs "$ADDLICENSE" -check -ignore '**/tests/resources/**' -f .license-header.tmpl -c "Calin-Andrei Burloiu"
```

A clean exit means every in-scope file has a header.
