# ADR 0065: What an outside review of the tools got right (2026-11-21)

Status: accepted, 2026-11-21. Decided by Claude. Jeremiah asked for the review to be treated as suggestions:
check each claim against the code and the traces, then build what holds up.

## Context

Another AI ranked msc's tools and named four changes for the Sky Sprint task (`game-sky-sprint`), the
costliest bench task. We checked the claims against the code and against the two 2026-11-21 toolset
traces it read (Flux, `~/.msc/bench-runs/20261121-081438/attempts/game-sky-sprint/toolset-{1,2}`), and against
the 2026-11-12 Sky Sprint rerun on Gitea (`claude/bench-r9-evidence`, `sky-sprint-rerun/20261112-140352`), where
no attempt made an msc tool call.

## Claims, checked

1. **Agents read 16 to 17 files and 72k to 74k characters before their first edit.** True: 17 files and
   74,111 characters, then 16 and 71,569. Both opened `CLAUDE.md`, every class, then every test. On 2026-11-12
   all six attempts read 16 to 19 files before the first edit, 47k to 53k characters of results: `CLAUDE.md`,
   then every class and test in the template. The template has grown since (now 86k characters of text,
   with `GameThreadsTest`, `SharedRulesTest`, `Console` and `TestGames`), so 72k to 74k fits. But the
   template's `CLAUDE.md` is only 11k of that. Most of the cost is the agent opening every file, because nothing
   tells it which files a new game touches.
2. **There is no project context for an existing project.** True. `api_search` and `api_class` see only the
   platform's jars. Grep finds lines, not declarations and the tests that use them.
3. **`api_search` returns nothing on near misses.** True in the code. Every query word had to match a name,
   so "PlayerMoveEvent constructor" missed, because constructors are named after their class and no member is
   named "constructor". "Collector collect poll" missed on its one wrong word. A miss said only `no matches`.
   In the traces, 5 of 18 queries returned `no matches`: "PlayerMoveEvent constructor", "TestConnection
   receive packet messages", "PacketSendEvent SystemChatPacket", "PacketSendEvent" (Minestom has none) and
   "Collector collect poll".
4. **`dev_cycle restart=false` still deploys the thin development jar.** True. With or without a restart,
   `dev_cycle` builds `build/msc/server.jar` (thin, `-Pmsc.thin`) and copies it to the dev server. The
   standalone `build/libs/<id>-<version>.jar` the bench's player check runs comes only from `./gradlew build`,
   and the task already requires that build. A build-only mode would repeat that one Bash command, so we did not
   add one.

## Decision

Each change is its own commit, so a bench rerun can measure it alone.

- **api_search misses (3).** If no name matches every word, `api_search` lists the names that match the most
  words, still within `limit`, and says so on its first line. A query word that names a class exactly adds
  `Try api_class name=<full name>.` to the miss or the partial list. The words "constructor", "constructors"
  and "ctor" list only constructors.
- **Project context (2): built, measured, removed.** `api_search project=<folder>` listed the project's own
  declarations with `path:line` and the tests using them. Neither rerun attempt called it (below), so it went
  again rather than cost every session its schema text. If a later run shows agents asking for it, the commit
  that added it is in this PR's history.
- **Package-private supertypes (found in the rerun).** Minestom declares `Block.GOLD_BLOCK` and the other block
  constants in package-private `Blocks`, which `Block` extends. The index skipped non-public classes, so neither
  `api_class name=Block filter=GOLD_BLOCK` (even with `inherited=true`) nor `api_search` could find them: one
  rerun attempt spent 10 api calls and five shell commands unzipping the jar on it. The index now folds a
  package-private supertype's members into each public class that extends it, as Java's own lookup does.
- **minestom-game CLAUDE.md (1), first of the templates.** A "Where to start" section names the four classes a
  new game changes, the classes and tests to read only when changing what they do, and `api_search project=.`.
  The rules for names and values (3.8k characters) moved to `RULES.md`, with a three-line summary and the link
  left in their place. `RULES.md` is checked by the same doc tests as `CLAUDE.md` (`TemplateDocs.DOCS`).
  `CLAUDE.md` went from 11.3k to 8.3k characters. `paper-game` and `fabric-game` keep their rules section: the
  rerun showed no fewer reads (Results).
- **dev_cycle (4).** No new mode. The `restart` option now says `false` still deploys, and the minestom-game
  `CLAUDE.md` says only `./gradlew build` makes the standalone jar.

## Results

Two Sky Sprint toolset attempts on Flux (`~/.msc/bench-runs/20261121-181926`) with the changes above except the
package-private fix. Both passed 3/3 checks, but used 4.81M and 4.40M tokens ($3.04, $2.86) against a 3.31M
median in the 2026-11-21 run ($2.59, $2.57).

- Both read every class and test before their first edit, 22 files (84k characters) and 20 (80k): the
  "Where to start" section did not change it. The template has more files to read than at the earlier run
  (`Console`, `GameThreadsTest`, `SprintServerTest`), so the counts do not compare one for one. Nobody opened
  `RULES.md`.
- Neither called `api_search project=`. One attempt made no msc call at all.
- The other made 21 `api_search`/`api_class` calls; 8 returned the closest-names list. Most of them were the
  block-constant hunt that the package-private fix answers; the misses on chat capture led to `TestConnection`
  and `Collector` in two calls, which on 2026-11-21 took four misses.

Two attempts on a task whose cost varies by 1.3M between runs measure little. Keep the closest-names fallback,
the shorter guide and the package-private fix, which are cheap and correct; leave the other templates' guides
as they are, since a "where to start" note did not change how agents read.
