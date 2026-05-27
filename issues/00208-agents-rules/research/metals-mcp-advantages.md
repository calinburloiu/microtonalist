If Metals MCP weren't available, here are CLI alternatives for the same code-intelligence tasks:

Symbol / text search (replacing mcp__metals__glob-search, inspect, get-usages)

- rg (ripgrep) — fast recursive regex search; the modern default over grep -r
- grep -rn --include='*.scala' — portable fallback
- git grep — fast, respects .gitignore, only searches tracked files

File discovery (replacing glob-search by filename)

- fd — fast, ergonomic find replacement
- find . -name '*.scala' — portable fallback
- git ls-files '*.scala' — limits to tracked files

Find usages / references (replacing get-usages)

- rg -w SymbolName — word-boundary search across the repo
- git grep -w SymbolName
- Neither is semantic — they'll match comments, strings, and unrelated identifiers with the same name, which is
  exactly what Metals avoids.

Read source of library/JDK symbols (replacing get-source)

- coursier fetch --sources <dep> then unzip the -sources.jar
- unzip -p ~/.cache/coursier/.../foo-sources.jar path/to/Foo.scala
- For JDK sources: read from $JAVA_HOME/lib/src.zip

Docs (replacing get-docs)

- No good local CLI equivalent — fall back to WebFetch against the library's published Scaladoc/Javadoc site.

Dependency version lookup (replacing find-dep)

- cs complete-dep org.foo:bar: (Coursier CLI)
- curl against search.maven.org's REST API

Compile (replacing compile-full / compile-module)

- sbtn compile / sbtn "<project>/compile" — already the documented fallback; uses the BSP-server sbt JVM.

The big tradeoff: all the search/usage alternatives are textual, not semantic — they can't distinguish a class from a
same-named variable, follow overrides, or resolve imports. That's why CLAUDE.md prefers Metals when it's up.
