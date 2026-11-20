# Agent efficiency: fewer tokens per task

How Claude sessions on this repo keep token use down, which tools are set up for it, and what was looked at and
left out. Decision record: [ADR 0055](adr/0055-agent-efficiency-tooling.md). This is a standing task: when a
session finds a tool or habit that saves tokens, it checks it against the rules at the end and adds a row here.

## In use

| Tool | What it does | Where it is set up |
|------|--------------|--------------------|
| ponytail | Claude Code plugin. A SessionStart hook turns on "smallest complete change" mode; `/ponytail-review`, `/ponytail-audit`, `/ponytail-debt` | Jeremiah's PC, user settings (`enabledPlugins`, marketplace `DietrichGebert/ponytail`) |
| graphify | Code graph of the repo (AST only, no API key). `graphify query`, `explain`, `path`, `affected` return a scoped subgraph instead of a pile of grep hits and whole files | CLI on the PC; graph in the main checkout's `graphify-out/` (git-excluded) |
| graphify guard hook | Before a Grep, a search command, or a Read/Glob of source, adds a short note pointing at the graph with the nodes it already has for that call. Advisory, never blocks, at most 5 notes per agent | `.claude/hooks/graphify-guard.sh`, registered in `.claude/settings.json` |
| Deferred tool loading | MCP tool schemas load only when used | User settings `ENABLE_TOOL_SEARCH` |
| MSC's own output caps | msc tools cap their results and ask for a narrower request instead of more characters | msc-core (`Budget`, `Output`) |

The guard finds the graph from a worktree too: it resolves the main checkout through `git rev-parse
--git-common-dir` and sets `GRAPHIFY_OUT`. Where graphify or the graph is missing (cloud sessions, a fresh clone)
it does nothing. From a worktree, pass the graph to the CLI yourself:

```bash
graphify query "how does the pack host stop" --graph "D:/Projects/Minecraft Server Creator/graphify-out/graph.json"
```

Refresh the graph from the main checkout after merges (about a minute, no API cost):

```bash
graphify update .
```

## Pick the model by task

From the first reel looked at for this page (a per-task model guide, November 2026). The point is that price
follows the model tier, so match the tier to the task, not to habit. As it applies here:

| Task | Model | How in this repo |
|------|-------|------------------|
| Finding code, renames, boilerplate | Haiku | `Agent` with `subagent_type: Explore` and `model: haiku`; ask graphify first |
| Building a module feature, writing tests | Sonnet | Thread sessions for routine module work |
| Planning a feature, a security change (agent link auth, pack hosting) | Opus or Fable, high effort | The session's own model; Plan subagent for a second plan |
| A bug that resists two tries | Opus, max effort | Ask Jeremiah to raise effort; never set it on your own |
| Review | Opus, in a session that did not write the code | `/code-review`, `/ponytail-review` in a fresh session |

## Habits that save the most

- Ask the graph before grepping: `graphify query` or `explain` first, then read only the lines it names (Read with
  `offset` and `limit`).
- Send wide reads to a subagent and keep only its conclusion. Don't run the same search yourself afterwards.
- Run one module's tests (`./gradlew :msc-core:test --tests '*PackHost*'`), not the whole build, until the end.
  Add `-q` when only failures matter.
- Narrow msc tool calls (a smaller world slice, `server_logs mode=triage`) rather than raising `maxChars`.
- Start a new session, or `/compact`, when the task changes; a long transcript is paid for on every turn.
- Keep CLAUDE.md short and put long how-tos in docs like this one, which a session reads only when it needs them.

## Looked at and left out

| Candidate | What it is | Why not now |
|-----------|-----------|-------------|
| "Gatekeeper" mod (second reel) | A PreToolUse hook that stops wasteful calls | Adopted in the form above: the graphify guard. A hook that blocks calls would also stop legitimate reads |
| "Claude plans with Claude" (second reel) | One session plans, another builds | Already here: plan mode, the Plan subagent, and the coordinator handing threads a plan |
| Live agent dashboard (second reel; e.g. Claude Code Agent Monitor) | A local web page fed by hooks on every tool call | The desktop app already shows each session and workflow live; another hook on every call costs time for nothing new |
| rtk, snip, chop, claude-compress | Rewrite each Bash command to compress its output | Bash only (sessions here run PowerShell too, and Read and Grep are not covered), lossy, and a third-party binary rewriting every command. msc tools already cap output. Revisit if Gradle output turns out to dominate a session's tokens |
| sqz, julius (API proxies) | Route all API traffic through a local proxy that trims it | Every prompt and secret would pass through third-party code |
| Cloudflare Code Mode (reel, 2026-11-20) | MCP server lists only `search` and `execute`; the model writes a script against a typed API that runs in a sandbox | msc's 35 tools are about 4,100 tokens and already deferred; batches already exist (`build_batch`, `dev_cycle`). A JS sandbox in the Java server is a new trust boundary for little gain. [ADR 0058](adr/0058-no-code-mode-mcp-server.md) |
| graphify's own project install (`graphify install --project`) | Same hooks plus a skill and a CLAUDE.md section | Its hooks look for `graphify-out/` in the working folder, which worktrees don't have, so they never fired here; the skill mostly covers LLM extraction, which needs an API key this PC doesn't use |

## Adding a tool

A tool goes in when it saves tokens on the work this repo does, runs on Windows and in the cloud (or is a no-op
where it can't run), sends nothing to a third party, and needs no new library in `gradle/libs.versions.toml`
unless the build itself uses it. Record it in the tables above and in ADR 0055, or a new ADR if it changes a
decision.
