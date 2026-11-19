# Perfection campaign, 2026-11-12 to 2026-11-14

Owner's brief (2026-11-12 23:21Z): two days of usage to push every server type to "perfection", and to find and
remove repeated, redundant and slow code. Auditors search the codebase in pieces and file GitHub issues; a piece is
done only when a new auditor finds nothing **twice**; then each piece is fixed. This file is the refined plan and the
resume point: a restarted thread reads the *State* section and the tracking issue first. Rules are in
[ADR 0029](adr/0029-perfection-campaign.md).

Base: main `650cb4d` (v0.5.0 plus #70 to #72). Ends 2026-11-14 23:00Z. State as of main `dd5724d`.

## What "perfect" means here

A word needs a test, so the campaign ends when all of these hold:

1. **Every piece has two clean audits in a row** (below), and every campaign issue is closed: fixed with a test, or
   closed as "not planned" with the reason written in the issue.
2. **The platform matrix is green on the PC** for all seven platforms (paper, purpur, fabric, quilt, neoforge, forge,
   minestom): scaffold each template of the platform, `dev_cycle`, the agent connects, `build_batch`, `world_slice`,
   `world_summary`, `world_entities`, `world_render`, `structure_save`/`paste`, undo, `player_test` (join, walk, use,
   chat, inventory), a resource pack reaches the test player, `server_stop` leaves no process. Same steps, same
   expected results, on each platform. Any cell that differs is an issue (`kind:parity`).
3. **`./gradlew build` and `:msc-core:e2eTest` are green on Linux and Windows** with `-Xlint:all -Werror` kept.
4. **The bench does not get worse**: a full run after the fixes, against `full-after-r9/20261112-091046`
   (toolset median 43 s, 192k tokens, $5.06), plus the Sky Sprint game task passing.
5. Docs match the code: `tools.md` generated, `protocol.md`, `plan.md`, README, wiki, each module README.

## Refinements to the brief

| Brief | Refinement | Why |
|---|---|---|
| Search the codebase in pieces | 14 fixed pieces (table below), about 1,000 to 4,000 lines each, plus one cross-cutting piece for duplication between modules and platform parity | An auditor that reads everything reads nothing well; duplication between modules is invisible from inside one module |
| Create GitHub issues | The auditor only reports. A second agent re-checks each finding against the code, drops what it cannot confirm, dedupes against all campaign issues (open and closed), then files | A fresh auditor always finds *something*; unverified findings cost a fix agent more than they are worth |
| Stop when a new sub-agent finds nothing twice | Two **consecutive** rounds with zero *confirmed, new* findings that meet the bar. The first plan capped this at 8 rounds per piece; the cap was lifted on 2026-11-13 (ADR 0029 amendment): a piece that is not clean twice in a row gets confirm rounds until it is, or until the campaign ends | Without a bar the loop never ends on style opinions |
| Then a sub-agent resolves each piece | Fixing starts per piece as soon as that piece closes, not after all audits. One branch and PR per piece batch (at most about 6 issues, split by theme when larger). Every fix has a test. Full `./gradlew build` before the PR | Nothing waits on the slowest piece; small PRs merge cleanly |
| (not in the brief) | After a piece's fixes merge, one more audit of that piece on the new main. It must be clean, or the loop reopens | Fixes add bugs |
| (not in the brief) | Real-server platform matrix on the PC, once at the start (baseline, files issues) and once after the fixes | Reading code does not prove a Forge server starts |
| (not in the brief) | Release v0.6.0 and a bench rerun at the end | A measured end state |

## The bar for a finding

A finding is filed only if it names file and line, and is one of:

- `kind:bug`: wrong result, crash, hang, leak, race, data loss, or an unhandled input a caller can really send. With
  the inputs that trigger it.
- `kind:perf`: work that grows with input where it need not (quadratic scans, per-block allocations or packets,
  blocking the server thread, repeated IO, missing caches on hot paths), or wasted tokens/turns in a tool result.
  With the path that makes it hot.
- `kind:dup`: the same logic in two or more places (10+ lines or a rule that must stay in sync), or code that is
  dead, unreachable or replaced.
- `kind:parity`: a tool or option that behaves differently, or is missing, on one platform without a documented
  reason.
- `kind:test`: behaviour with no test where a bug would ship unseen; a flaky or slow test.
- `kind:docs`: a doc that states something the code does not do.

Not filed: naming and formatting taste, "could be more idiomatic", speculative features, anything a doc or ADR
already records as a decided trade-off. Severity `sev:1` (breaks a user flow), `sev:2` (wrong or slow in a real
case), `sev:3` (cleanup).

## Pieces

| # | Piece label | Paths |
|---|---|---|
| 1 | `piece:core-tools` | `msc-core` packages `core` (root), `core.tools`, `core.tool`, `core.mcp`, `core.api`, `core.metrics` |
| 2 | `piece:core-server` | `msc-core` packages `core.server`, `core.agent` |
| 3 | `piece:core-world` | `msc-core` packages `core.world`, `core.building` |
| 4 | `piece:textures` | `msc-textures`, `msc-core` package `core.texture` |
| 5 | `piece:protocol` | `msc-protocol`, `msc-agent-common` |
| 6 | `piece:agent-paper` | `msc-agent-paper` (Paper and Purpur) |
| 7 | `piece:agent-mods` | `msc-agent-vanilla`, `msc-agent-fabric` (Fabric and Quilt), `msc-agent-neoforge`, `msc-agent-forge` |
| 8 | `piece:agent-minestom` | `msc-agent-minestom` |
| 9 | `piece:bot` | `msc-bot` |
| 10 | `piece:renderer` | `msc-renderer` |
| 11 | `piece:templates` | `msc-templates` (generator and all nine templates) |
| 12 | `piece:bench` | `msc-bench` |
| 13 | `piece:build` | `build-logic`, `gradle/`, root Gradle files, `.github/`, `msc-plugin`, `msc-docs`, `docs/wiki`, README, TERMS |
| 14 | `piece:cross` | Whole repo: duplication between modules, the per-platform parity of every op and tool, dead code, test suite time |

Tests of a module belong to its piece. Every campaign issue carries `campaign`, one `piece:*`, one `kind:*`, one
`sev:*`.

## Phases

| Phase | When (UTC) | What |
|---|---|---|
| 0 Plan | 11-12 23:20 to 11-13 00:30 | This file, ADR 0029, labels, tracking issue, cloud build working |
| 1 Audit | 11-13 00:30 to about 10:00 | All 14 pieces in parallel, round after round. PC baseline matrix in parallel |
| 2 Fix | rolling, 11-13 03:00 to 11-14 12:00 | Per piece once closed. `sev:1` first. Integrator thread merges after a local build |
| 3 Re-audit | rolling, to 11-14 16:00 | One clean pass per fixed piece on new main |
| 4 Prove | 11-14 12:00 to 21:00 | PC matrix second run on all seven platforms, Windows build, bench full run and Sky Sprint, v0.6.0 |
| 5 Report | 11-14 21:00 to 23:00 | State section final, roadmap refreshed with what was cut |

If time runs short, cut from the bottom: `sev:3` fixes, then `kind:docs`, then re-audits of pieces whose fixes were
`sev:3` only. Never cut: `sev:1` fixes, the second PC matrix run.

## How the work runs

- **Audit round**: auditor (fresh, read-only, told which issues already exist) then verifier-filer (fresh; confirms,
  dedupes, files, comments the round result on the tracking issue). Auditors may run tests to prove a finding.
- **Fix batch**: a fix agent in its own git worktree takes a piece's issues, writes the failing test first, fixes,
  runs the module tests then `./gradlew build`, opens a draft PR that says `Fixes #n` per issue. An issue it finds
  to be wrong is closed as "not planned" with the evidence. Shared files (ADR 0029) may be changed by fix agents;
  the `piece:build` batch goes last among batches that touch them.
- **Merge**: the integrator thread, after a full local build on top of main (GitHub Actions is billing-blocked).
- **PC**: real servers for all platforms run on Jeremiah's PC through Remote Control. graphify and ponytail exist
  only there; cloud auditors work without them and the PC thread uses them for the cross-cutting piece.
- **Restart after a usage limit**: read *State* below and the tracking issue, list open `campaign` issues and open
  PRs, continue the first unfinished phase. Audit rounds already commented on the tracking issue are not repeated.

## State

Final state at the end of the campaign (2026-11-14 23:00 UTC). The tracking issue (#74) has the per-round log.
A piece is done when two fresh audits in a row confirmed nothing new.

| Piece | Issues filed | Closed | Status |
|---|---|---|---|
| core-tools | 46 + 9 found after the campaign | 46 + 9 fixed (Gitea PR #9) | done 2026-11-19: ten lean rounds on the code changed since the campaign (one auditor each, findings verified by hand, ADR 0056), rounds 9 and 10 clean |
| core-server | 42 | 42 | not done: round 28 clean, round 29 found #533 (fixed, not audited again) |
| core-world | 16 | 16 | done |
| textures | 14 | 13 | done; #539 (a timing limit in one test) was filed afterwards and is open |
| protocol | 13 | 13 | done |
| agent-paper | 4 | 4 | done |
| agent-mods | 5 | 5 | done |
| agent-minestom | 10 | 10 | done |
| bot | 21 | 21 | done |
| renderer | 15 | 15 | done |
| templates | 22 | 22 | done (rounds 23 and 24 clean) |
| bench | 42 | 42 | not done: round 29 clean, rounds 30 to 32 found #525, #529, #535 (fixed, not audited again) |
| build | 14 | 14 | done |
| cross | 40 | 40 | done (rounds 21 and 22 clean) |

Eleven of the fourteen pieces are done. The three that are not (core-tools, core-server, bench) are the largest
and the ones each fix kept changing: every confirm round there read the code the last fix had just added and found
the next, smaller defect in it. In the last ninety minutes these three had fourteen more rounds and ten more fix
PRs (#520 to #538); every finding was sev 2 or sev 3 and every one was fixed, but none of the three reached two
clean rounds in a row, and the last fix of each was not audited again. Their next step is one fresh round each.
An issue can carry two piece labels, so the rows add up to more than the total.

Totals: 305 campaign issues filed, 304 closed, 1 open (#539); 157 campaign fix PRs merged (plus the two
platform matrix reports, #170 and #255). Updated 2026-11-14 23:00 UTC.

From 2026-11-14 11:50 UTC the campaign ran in the cloud only (the PC was out of use) and with about five agents
at a time. GitHub Actions has been billing-blocked since 2026-11-12, so every merge was checked with a full local
build by the integrator thread, and main was rebuilt independently several times.

Left for a PC session: a third platform matrix run, RunIsolation with Windows paths (#328), the Windows process
tree kill, the pid and lock files and AtomicFile replace-over-open on Windows, Reset on a Windows junction, RCON
against a real Paper server, the full bench and Sky Sprint rerun, and the v0.6.0 release build.

Platform matrix (PC): baseline run merged in #170 (all 7 platforms, 14 cells pass). Second run at 113058b merged in
#255: every cell passes the baseline steps and the earlier fixes hold; it filed #253 (Paper and Purpur: a `/kill`
in the nether reports no death or respawn) and #254 (Forge and Quilt still warn on a clean start), both since fixed (#264, #269). `gradlew build`
and `:msc-core:e2eTest` pass on Windows.
Bench: rerun pending (needs the PC). Last valid full run: full-after-r9/20261112-091046.

## Lessons

- **The root `.gitignore` hides directories named `build/` and `*.log` files**, including a Java package called
  `build` (#267). A fix that compiled in its agent's worktree failed on a fresh clone. Fix agents now check
  `git status --short --ignored` and run the final build from a clean detached worktree of their pushed commit.
- **Agents share one machine**: killing Gradle or Java processes by name stops other agents' builds and tests too.
  Agents kill only the process ids or process groups they started themselves; no `pkill` or `killall`.
- **Timing tests flake under load**: a test that passes alone can fail while other agents build. New tests
  use no fixed sleeps, tight deadlines or fixed ports, assert on state rather than timing, and are run 50 times in a
  loop with a CPU-burning process before the fix is called done.
- **Fix the class, not the case**: in core-tools, bench, templates and core-server, a fix for one finding was the
  next round's finding more than once (result cuts #473, #487, #499; the rule-change doc #481, #491, #498; server
  ownership #482, #490). The rounds stopped finding things when the fix made the design smaller: one helper or
  entry point that every caller must use, and a property or table test over every caller.
- **Docs promise only what a test proves**: a scaffold's CLAUDE.md is followed word for word by its reader. Every
  universal sentence in it ("no other test needs a change") was false for some input until a test applied the
  doc's own steps at awkward values.
- **Test through the real transport**: the bad-input tests called the tool registry directly, and the MCP SDK was
  answering those calls itself before the registry saw them (#510). An audit that drove every tool over MCP found
  it in one round.
- **A fast "merged" answer deserves a second build**: main was rebuilt from a clean worktree at intervals,
  independently of the integrator, and passed each time.
