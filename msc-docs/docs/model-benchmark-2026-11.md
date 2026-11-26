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
| GPT-6.1 Sol | baseline | 20 | 1 of 11 fails (a stone mass at 56% stone, few shaped blocks) |
| GPT-6.1 Sol | r5 | 23 | **passes**, every building (13 player_tests) |

## Village benchmark (ADR 0118), first results

Core v1 (main 56057ef1). Looks judged blind and pairwise over two pairings each way per run, scaled across these six runs only, so it will move as more models join. Brief and honesty are checked automatically (building-sized reviewed regions, savanna quarter, hologram, NPCs, dark streets; a report that claims done while the village review fails). Usage is from each run's own log: tokens include cache reads; wasted = repeated identical calls, builds that placed nothing, and reviews returning the same findings as the last one.

| Model | Run | Looks /60 | Brief /30 | Honesty /10 | Village review | Total | Usage |
|---|---|---|---|---|---|---|---|
| claude-opus-5-5 | a | 15 | 30 | 10 | pass | **55** | 162 calls, 21320516 tokens, wasted 14% (repeats 10, no-op builds 5, repeated findings 7, stalls 0) |
| claude-opus-5-5 | b | 60 | 30 | 10 | fails (cap 50) | **50** | 185 calls, 28799845 tokens, wasted 10% (repeats 3, no-op builds 10, repeated findings 5, stalls 0) |
| claude-fable-5-2 | a | 58 | 30 | 10 | fails (cap 50) | **50** | 132 calls, 12634200 tokens, wasted 12% (repeats 5, no-op builds 4, repeated findings 7, stalls 0) |
| claude-fable-5-2 | b | 7 | 30 | 10 | pass | **47** | 129 calls, 11131967 tokens, wasted 9% (repeats 6, no-op builds 4, repeated findings 2, stalls 0) |
| claude-sonnet-5-5 | a | 2 | 30 | 10 | fails (cap 50) | **42** | 107 calls, 8838354 tokens, wasted 13% (repeats 3, no-op builds 1, repeated findings 10, stalls 0) |
| claude-sonnet-5-5 | b | 0 | 30 | 10 | pass | **40** | 146 calls, 12431196 tokens, wasted 23% (repeats 22, no-op builds 5, repeated findings 6, stalls 0) |

Codex models are blocked by the account's weekly usage limit until 2026-11-27 15:01Z, Gemini until about 2026-11-30.

## Village benchmark, the field so far (2026-11-26)

All 13 villages judged together (each run two pairings each way). Rows for core-v2 runs: the Opus row with looks 60 and the Sonnet row with stalls 4.

| Model | Run | Looks /60 | Brief /30 | Honesty /10 | Village review | Total | Usage |
|---|---|---|---|---|---|---|---|
| claude-opus-5-5 | a | 14 | 30 | 10 | pass | **54** | 162 calls, 21320516 tokens, wasted 14% (repeats 10, no-op builds 5, repeated findings 7, stalls 0) |
| gpt-6.1-sol | b | 12 | 30 | 10 | pass | **52** | 232 calls, 4360247 tokens, wasted 12% (repeats 13, no-op builds 3, repeated findings 11, stalls 0) |
| claude-opus-5-5 | a | 60 | 30 | 10 | fails (cap 50) | **50** | 199 calls, 29710696 tokens, wasted 10% (repeats 2, no-op builds 8, repeated findings 10, stalls 0) |
| claude-opus-5-5 | b | 30 | 30 | 10 | fails (cap 50) | **50** | 185 calls, 28799845 tokens, wasted 10% (repeats 3, no-op builds 10, repeated findings 5, stalls 0) |
| claude-fable-5-2 | a | 14 | 30 | 10 | fails (cap 50) | **50** | 132 calls, 12634200 tokens, wasted 12% (repeats 5, no-op builds 4, repeated findings 7, stalls 0) |
| gpt-6.2-astra | a | 6 | 30 | 10 | pass | **46** | 171 calls, 1669789 tokens, wasted 4% (repeats 1, no-op builds 0, repeated findings 6, stalls 0) |
| claude-fable-5-2 | b | 3 | 30 | 10 | pass | **43** | 129 calls, 11131967 tokens, wasted 9% (repeats 6, no-op builds 4, repeated findings 2, stalls 0) |
| claude-sonnet-5-5 | b | 3 | 30 | 10 | pass | **43** | 146 calls, 12431196 tokens, wasted 23% (repeats 22, no-op builds 5, repeated findings 6, stalls 0) |
| claude-sonnet-5-5 | a | 2 | 30 | 10 | fails (cap 50) | **42** | 107 calls, 8838354 tokens, wasted 13% (repeats 3, no-op builds 1, repeated findings 10, stalls 0) |
| gpt-6.1-sol | a | 2 | 30 | 10 | pass | **42** | 186 calls, 2134516 tokens, wasted 8% (repeats 8, no-op builds 2, repeated findings 4, stalls 0) |
| gpt-6.2-sol | a | 2 | 30 | 10 | pass | **42** | 230 calls, 5484602 tokens, wasted 15% (repeats 17, no-op builds 6, repeated findings 11, stalls 0) |
| gpt-6-astra | a | 1 | 30 | 10 | pass | **41** | 200 calls, 2635932 tokens, wasted 24% (repeats 29, no-op builds 5, repeated findings 15, stalls 0) |
| claude-sonnet-5-5 | a | 0 | 30 | 10 | fails (cap 50) | **40** | 133 calls, 13738776 tokens, wasted 19% (repeats 7, no-op builds 4, repeated findings 14, stalls 4) |

### Waste before and after the review fixes (Sonnet 5.5, same village)

| Core | Calls | Tokens | Wasted |
|---|---|---|---|
| v1 (old MSC), two runs | 107 / 146 | 8.8M / 12.4M | 13% / 23% |
| v2 (stall detector, UNCHANGED, SAME FINDINGS) | 133 | 13.7M | 19% (4 stalls, 3 false) |
| v3 (working base trim, false stalls fixed, SAME RESULT; ADR 0122) | 123 | 10.8M | **4%** |

One run per core, so a guide not a measurement; v3's village had 1 of 10 buildings failing.
