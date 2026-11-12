# ADR 0026: tools.md tables are generated from the tool definitions

Status: accepted, 2026-11-12. Decided by Claude (roadmap item R8) under Jeremiah's hand-over of project decisions.

## Context

`msc-docs/docs/tools.md` was written by hand. Every round that changed a tool (R4 options, R5 `dev_cycle`, R6 texture tools) had to copy argument lists, defaults and budgets into it, and it drifted: it still had a "Not in 0.1" section listing features that have shipped, and the wiki's tool page did not list `msc_workspace`. The AI never reads `tools.md`; it reads the tool descriptions and argument schemas in `msc-core`. Those are the source of truth.

## Decisions

1. **The tool tables are generated.** `tools.md` marks each table with `<!-- tools: name name ... -->` and `<!-- /tools -->`. `io.github.imnotstable.msc.core.ToolDocs` builds a `Core` in a temp workspace, reads its registry and fills each table with one row per tool: name, arguments (required ones bare, optional ones with `?`, type, the schema's text) and the description, word for word. A `<!-- tool-list -->` marker gets the tool count and the size of the definitions.
2. **The grouping lives in the doc.** The markers name the tools, so sections and order are edited in Markdown. Every tool must be in exactly one marker and every name in a marker must be a tool, or generation fails with the names.
3. **Prose stays hand-written.** Rules, formats, platform tables, configuration, and the "Results" notes on what each result looks like sit outside the markers. Budgets are stated there only where a tool has no `maxChars` argument; the default cap is stated once in the rules.
4. **The wiki tool page stays hand-worded for users** but must list exactly the tools in code (rows starting with a tool name in backticks) and its "All N tools" must match.
5. **Two Gradle tasks in msc-core.** `toolDocs` rewrites the tables. `checkToolDocs` fails, naming the problems and the fix, when `tools.md` differs from a fresh render or the wiki page misses or invents a tool; `check` (so `build`) depends on it.

## Consequences

- A PR that changes a tool's description or arguments fails `./gradlew build` until it runs `toolDocs` and commits the result, so the doc diff shows up in review next to the code diff.
- A new tool fails the build until it is placed in a section and added to the wiki page.
- Any wording change in a description also changes the size line, so `toolDocs` is needed for those too.
- CI skips docs-only changes (ADR 0025), so a hand edit inside the markers is caught by the next code PR or `main`'s run, not by its own PR.
- The "Results" notes can still drift; they describe behaviour, which tests cover, not the tool surface.
