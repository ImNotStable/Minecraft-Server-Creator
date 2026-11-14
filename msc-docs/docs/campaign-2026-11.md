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

Updated at each milestone. The tracking issue (#74) has the per-round log. Rounds run is the highest round number
logged for the piece; agent-paper, agent-mods and agent-minestom also had one combined re-confirm round (round 8,
clean) after the shared agent code changed.

| Piece | Rounds run | Clean in a row | Issues filed | Closed | Status |
|---|---|---|---|---|---|
| core-tools | 8 | 0 | 20 | 17 | round 8 found #338, #339, #340 (open) |
| core-server | 9 | 0 | 19 | 17 | round 9 found #336, #337 (open) |
| core-world | 10 | 2 | 16 | 16 | done (rounds 9 and 10 clean) |
| textures | 6 | 2 | 13 | 13 | done (two clean rounds) |
| protocol | 10 | 0 | 12 | 11 | round 9 clean, round 10 found #341 (open) |
| agent-paper | 4 + combined | 4 | 4 | 4 | done (two clean rounds; re-confirmed in rounds 4 and combined 8) |
| agent-mods | 5 + combined | 4 | 5 | 5 | done (two clean rounds; re-confirmed in rounds 5 and combined 8) |
| agent-minestom | 6 + combined | 3 | 10 | 10 | done (two clean rounds; re-confirmed in combined 8) |
| bot | 9 | 1 | 18 | 18 | round 9 clean, one more clean round to go |
| renderer | 9 | 0 | 15 | 12 | round 9 found #342, #343, #344 (open) |
| templates | 9 | 1 | 8 | 8 | round 9 clean, one more clean round to go |
| bench | 9 | 0 | 18 | 17 | round 9 found #335 (open) |
| build | 8 | 2 | 14 | 14 | done (rounds 7 and 8 clean) |
| cross | 9 | 0 | 19 | 17 | round 9 found #333, #334 (fix in review) |

Done (two clean rounds in a row): agent-paper, agent-mods, agent-minestom, textures, build, and core-world (its round
10 came back clean at 20:08 UTC, after protocol and core-world had each been clean once in round 9). Clean once:
bot, templates. The round 8 sweep still found 15 confirmed defects in eight pieces (core-tools' round 8, logged
later, found three more), so the 8-round cap was lifted
(ADR 0029 amendment): the remaining pieces get confirm rounds until they are clean twice in a row or the campaign
ends.

Totals: 191 campaign issues filed, 179 closed, 12 open (#333 to #344); 74 campaign fix PRs merged (plus the two
platform matrix reports, #170 and #255). Updated 2026-11-13 20:12 UTC, on main dd5724d.

Platform matrix (PC): baseline run merged in #170 (all 7 platforms, 14 cells pass). Second run at 113058b merged in
#255: every cell passes the baseline steps and the earlier fixes hold; it filed #253 (Paper and Purpur: a `/kill`
in the nether reports no death or respawn) and #254 (Forge and Quilt still warn on a clean start), both since fixed (#264, #269). `gradlew build`
and `:msc-core:e2eTest` pass on Windows.
Bench: rerun pending.

## Lessons

- **The root `.gitignore` hides directories named `build/` and `*.log` files**, including a Java package called
  `build` (#267). A fix that compiled in its agent's worktree failed on a fresh clone. Fix agents now check
  `git status --short --ignored` and run the final build from a clean detached worktree of their pushed commit.
- **Agents share one machine**: killing Gradle or Java processes by name stops other agents' builds and tests too.
  Agents kill only the process ids or process groups they started themselves; no `pkill` or `killall`.
- **Timing tests flake under load**: a test that passes alone can fail while other agents build. New tests
  use no fixed sleeps, tight deadlines or fixed ports, assert on state rather than timing, and are run 50 times in a
  loop with a CPU-burning process before the fix is called done.
