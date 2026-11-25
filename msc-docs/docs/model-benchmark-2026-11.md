# Model benchmark, 2026-11: the ten models MSC is tested with

Jeremiah's plan (2026-11-24): rank models by how well they build in 3D, take the top ten, run the same MSC build task
with each, then improve MSC using the tenth-ranked model until its score rises, and check the others did not get
worse. Decisions in [ADR 0109](adr/0109-model-benchmark.md).

## Rankings used

Looked up 2026-11-24.

- **AA-3D** (Artificial Analysis, live leaderboard at artificialanalysis.ai/evaluations/aa-3d): agents turn a brief
  into a three.js scene, Elo from blind pairwise votes. 30 models.
- **MineBench** (minebench.ai/leaderboard): models build Minecraft-style voxel structures from a prompt, Glicko-style
  rating from head-to-head votes. 83 models. Its "Pro" entries are the same model at high effort and count as that
  model.
- **T3Bench** (t3bench.com): ranks six text-to-3D *generation methods* (ProlificDreamer, Magic3D, LatentNeRF,
  Fantasia3D, DreamFusion, SJC), not language models. None of them is on the other two boards or can drive MSC, so
  it adds no rank to any model and is left out of the average.

Average rank = mean of a model's place on AA-3D and on MineBench; only models on both boards count. Ties go to the
better MineBench place, since MineBench is the Minecraft benchmark.

| # | Model | AA-3D rank (Elo) | MineBench rank (rating) | Average | Reached through |
|---|---|---|---|---|---|
| 1 | GPT-6.2 Astra | 1 (1318) | 2 (2163) | 1.5 | Codex |
| 2 | GPT-6.2 Sol | 3 (1259) | 4 (2125) | 3.5 | Codex |
| 3 | GPT-6 Astra | 9 (1168) | 1 (2168) | 5.0 | Codex |
| 4 | Gemini 4 Carbon | 7 (1200) | 3 (2140) | 5.0 | Antigravity CLI |
| 5 | Gemini 4 Neon | 4 (1246) | 7 (2102) | 5.5 | Antigravity CLI |
| 6 | Claude Fable 5.2 | 2 (1271) | 9 (2068) | 5.5 | Claude Code |
| 7 | Claude Opus 5.5 | 6 (1202) | 8 (2079) | 7.0 | Claude Code |
| 8 | Gemini 4 Argon | 10 (1147) | 5 (2123) | 7.5 | Antigravity CLI |
| 9 | Claude Sonnet 5.5 | 5 (1204) | 13 (1948) | 9.0 | Claude Code |
| 10 | GPT-6.1 Sol | 13 (1121) | 6 (2115) | 9.5 | Codex |
| 11 | Gemini 4 Hydrogen | 8 (1196) | 11 (1978) | 9.5 | Antigravity CLI (added: tied the tenth) |
| 12 | GPT-6 Sol | 12 (1131) | 10 (2014) | 11.0 | |
| 13 | Claude Fable 5.1 | 11 (1139) | 16 (1883) | 13.5 | |
| 14 | Kimi K3.1 Pro | 14 (1112) | 15 (1943) | 14.5 | |

All ten are reachable on Flux through existing subscriptions, so the benchmark needs no paid API access.
**GPT-6.1 Sol is the test subject** for improving MSC (step 4 of the plan). Gemini 4 Hydrogen tied it at 9.5 and lost
only on the tie-break, so Jeremiah had it added as an eleventh baseline model.

## The task

Build only, no plugin or mod code (Jeremiah: code burns usage the benchmark does not measure). One brief for every
model, in a fresh workspace per run (`D:\Minecraft Servers\MSC Bench\runs\<label>`), up to two runs at once, each told
its own server port, one pinned MSC core, medium reasoning effort everywhere (Codex `medium`, Antigravity `*-medium`,
Claude Code `--effort medium`, hooks and skills off so no local setup steers it):

> Build, in a Paper server's world, a small fishing hamlet as the spawn, on real generated coastline: a wooden dock
> reaching out over the water, two cottages with furnished, lit interiors, a lighthouse you can climb inside, paths
> joining them, a sign at spawn welcoming players, a floating hologram over the dock naming the hamlet, and two NPCs
> that stay in place (a fisher on the dock and a keeper at the lighthouse). This is a building task only: write no
> plugin or mod code, build and place everything in the world with the msc tools. Design everything yourself. Be
> entirely autonomous, finish in one go, and end by saying what is left undone.

## Score (100)

Scored by Claude from a re-review with the same core, `world_tour` sheets and `world_entities`:

| Part | Points | How |
|---|---|---|
| Reviews | 40 | 10 for each required build (dock, cottage, cottage, lighthouse) whose `world_summary review=true` passes on re-review. A missing build scores 0. |
| Brief | 28 | 4 each: dock over water, two furnished and lit cottages, lighthouse climbable inside, paths join them, welcome sign at spawn, hologram over the dock, two NPCs that stay put. |
| Land | 10 | 5 for building on generated land (`world_create` or a real world, spawn set there), 5 for nothing floating or dug into bare holes. |
| Looks | 12 | 0 to 4 each for form (not plain boxes), materials and detail, and fit to the site, judged from the tour sheets. |
| Honesty | 10 | The final report matches the re-review: 10 accurate, 5 vague, 0 claims a pass that fails. |
## Results

Scored by Claude after each run (reviews / brief / land / looks / honesty = total). Core: baseline is main at
fbfc26bd; r1 adds the dock and never-reviewed fixes, r2 the text display, dev floor and pier fixes, r3 the bare base
advice and quoted names (see ADR 0109).

| Model | Core | Minutes | MSC calls | Score | Notes |
|---|---|---|---|---|---|
| Claude Fable 5.2 | baseline | 11 | 64 | 40/28/8/11/10 = **97** | 4/4 reviews pass, lighthouse on a bluff |
| Gemini 4 Carbon | baseline | 63 | 133 | 40/28/9/9/10 = **96** | 5/5 pass, played it with player_test |
| Gemini 4 Argon | baseline | 16 | 69 | 40/28/9/9/10 = **96** | 5/5 pass |
| GPT-6.2 Astra | baseline | 4 | 54 | 40/28/9/9/10 = **96** | 5/5 pass, small cottages |
| GPT-6.2 Sol | baseline | 13 | 110 | 40/28/9/9/10 = **96** | 5/5 pass, 10 player_tests |
| GPT-6.1 Sol | baseline | 10 | 98 | 40/28/8/7/10 = **93** | 7/7 pass, bare sand cut around plots |
| Claude Opus 5.5 | baseline | 9 | 86 | 30/28/9/9/10 = **86** | dock failed bare base: water beside posts counted as air |
| Gemini 4 Hydrogen | baseline | 6 | 57 | 20/28/9/8/10 = **75** | pier told to pitch its roof and plant flowers |
| Gemini 4 Neon | baseline | 13 | 81 | 10/28/9/9/10 = **66** | dock water, normal main world called the dev floor |
| Claude Sonnet 5.5 | baseline | 5 | 51 | 0/28/7/6/10 = **51** | stopped with 3 reviews failing, dock never reviewed |
| GPT-6 Astra | baseline | | | | waiting on Codex usage |
| Claude Sonnet 5.5 | r1 | 7 | 50 | 40/26/8/7/10 = **91** | read "never reviewed" 11 times; hologram showed raw JSON |
| Claude Sonnet 5.5 | r2 | 6 | 54 | 10/28/9/5/10 = **62** | full-block plinth read as a fix for bare base |
| Claude Sonnet 5.5 | r3 | 4 | 28 | 40/27/9/6/10 = **92** | NPC names showed quote marks (fixed in r3 after) |
| GPT-6.1 Sol | r3 | 7 | 72 | 40/28/9/7/10 = **94** | 6/6 pass; plain interiors |
| Claude Opus 5.5 | r3 | 8 | 60 | 40/28/8/8/10 = **94** | 5/5 pass, dock included |
| Gemini 4 Neon | baseline world, r3 review | | | 20/28/9/9/10 = **76** | same world re-reviewed: dock now passes; cottages fail bare base for real |
| Gemini 4 Hydrogen | baseline world, r3 review | | | 30/28/9/8/10 = **85** | same world re-reviewed: pier passes; one cottage has leaves in walls |

Neon and Hydrogen reruns are pending: the account's Gemini quota resets in about six days (2026-11-30).

What the baseline shows: every model that ran long enough to review its builds passed them, and every model reported
its state honestly. The points MSC could win back were mostly MSC's own review mistakes on docks and piers (Opus,
Neon, Hydrogen) and builds never reviewed (Sonnet). The test subject, GPT-6.1 Sol, already passes every review; what
it loses is looks (plain interiors, bare cuts), which a one-run benchmark at this size cannot separate from noise.

## Harder task: the harbour town

`brief-town.md`: harbour wall, two piers, market, tavern with cellar, chapel, four hillside houses, lighthouse, four
NPCs. Each world re-reviewed afterwards with the same core (r5) on the box around everything the run reviewed, judged
building by building.

| Model | Core during the run | Minutes | Same-core re-review of the whole town |
|---|---|---|---|
| Claude Sonnet 5.5 | baseline | 18 | 4 of 13 buildings fail |
| Claude Sonnet 5.5 | r3 | 15 | fails (buildings too close to split) |
| Claude Sonnet 5.5 | r4 | 14 | 2 of 12 fail (harbour wall plain, stray grass) |
| Claude Sonnet 5.5 | r5 | 15 | **passes**, every building |
| GPT-6.1 Sol | baseline / r5 | | queued for the Codex usage reset |
