# ADR 0123: Next directions after the village benchmark

Status: accepted, 2026-11-27. Decided by Claude; Jeremiah asked for a fresh brainstorm, decided without questions.
Everything from the 2026-11-25 brainstorm is built (ADRs 0113, 0114, 0115, 0118). The Codex-vs-Claude token gap is
investigated in its own thread and left out here.

## What the 18 judged villages show

From `model-benchmark-2026-11.md`, "all non-Gemini runs":

- **Reviews and looks pull apart.** 3 of the 5 best-looking villages (looks 60, 40, 18) fail the final village
  review and are capped at 50. The 12 passing villages have a median looks score of 4.5 (of 60). A village a player would stay
  in is not finishing; a village that finishes looks plain.
- **The same model swings more than models differ.** Opus 5.5 scored 18, 20 and 60 on looks; Fable 5.2 8 and 40;
  GPT-6 Astra 2 and 23. Two runs per model cannot rank models, and MSC's biggest lever is making the good run the
  usual one.
- **Brief and honesty are saturated.** All 18 runs scored 30/30 and 10/10, so only looks and the cap separate runs.
- **One judge family.** Every judge is a Claude model, and the top-looking villages are Claude builds. That may be
  real or a shared taste; nothing checks it yet.

## Decision: four directions, in order

1. **Make ambitious builds finish.** For each capped village (Opus v2-a, Fable v1-a, Opus v1-b), list which
   findings still fail at the end and why: does the fix the finding names change what it counts at village scale
   (ADR 0122's first rule), did the run run out of turns, or is the finding wrong for that build. Fix the tool or
   the check, not the wording. Measured by: those three worlds re-reviewed on the new core, and the next runs' cap
   rate. Cheapest win: Opus v2-a goes from 50 to 100 if its failures are tool or check errors.
2. **Calibrate the reviews against the blind judge.** For every check, compare its findings across the 18 villages
   with the judged wins: a check whose findings do not predict losing looks gets loosened or removed (a failing
   review that does not hurt looks only costs calls); traits that lose pairings but no check sees become new
   general checks. Likely candidates are settlement-scale, since today's checks are per building: height variety
   along a street, a focal point, buildings facing the street, spacing, roads that follow the land. Offline
   analysis of saved worlds and verdicts, no model calls. Checks stay general measurements, never targets copied
   from a winning village (ADR 0106).
3. **Make the good run the usual run.** Diff the best and worst run of the same model (Opus 60 vs 18, GPT-6 Astra
   23 vs 2): plan detail, calls per building, whether it looked at its own render before calling it done, where the
   time went. Where the weak run skipped a step the strong one took, strengthen the build guide or the plan check
   (ADR 0113) so every run takes it. Guidance only; no generators.
4. **Sharpen the benchmark.** Re-weight the score so saturated parts stop padding it (looks 70, brief 20 with
   stricter items such as furnished upstairs and building-to-street fit, honesty 10); spend judge calls where they
   matter (extra pairings between runs within ten strength points); a third run when a model's two runs differ by
   more than 20; and once per field, a non-Claude judge (GPT-6.2 Astra) on the same pairs to test the Claude
   judge's taste. Judge calls stay two images and one line back.

## Not now

- New platforms, tools or texture models: none of the findings point at them.
- Gemini runs: they wait for quota, as before.
- A human vote: ADR 0118 keeps one human pass over the top and bottom pairs as the check on the judge.

## Consequences

Direction 1 and 2 share the saved worlds and verdicts on the PC (`D:\Minecraft Servers\MSC Bench`), so one thread
does both, then 3. Direction 4 changes only the benchmark scripts and can run beside them.
