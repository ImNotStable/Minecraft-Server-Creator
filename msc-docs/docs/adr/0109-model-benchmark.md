# ADR 0109: Ten-model benchmark, improved on the tenth

Status: accepted, 2026-11-24. Decided by Claude; Jeremiah approved the plan (rank by AA-3D, MineBench and T3Bench,
top ten, baseline, improve MSC on the tenth, re-check the rest, keep it cheap).

## Why

MSC is tuned on whichever agent a playtest happened to use (mostly Gemini 4 Neon and GPT-6.2 Sol). Jeremiah wants
it to work for every strong model, and the weakest of the strong ones shows the gaps most clearly.

## Decision

- Ranking: mean of each model's place on AA-3D and MineBench, models on both boards only, ties to the better
  MineBench place. T3Bench ranks text-to-3D generation methods, not language models, so it gives no model a rank and
  is left out. List and numbers: [model-benchmark-2026-11.md](../model-benchmark-2026-11.md).
- The test subject is the tenth, GPT-6.1 Sol (it won the 9.5 tie with Gemini 4 Hydrogen on MineBench). Hydrogen
  runs as an eleventh baseline model (Jeremiah, 2026-11-24), since only the tie-break left it out.
- One small build-only brief (a fishing hamlet with a hologram and NPCs, no plugin code), medium effort for every model, up to two runs at once, through the CLIs Flux already
  has (Codex, Antigravity, Claude Code): no paid API use.
- A fixed 100-point score (reviews 40, brief 28, land 10, looks 12, honesty 10), written down before the first run.
- Benchmark runs use their own folders, the core built from this branch run from its own folder (not installed into
  `~/.msc/core`, which the Shattered Meridian loop picks its core from), and Gemini runs only while that loop is on
  Codex, so the two never share a folder, a core or Gemini quota.
- Improvements follow ADR 0106: guides, review checks and gates, never prefabs.

## Changes from the baseline

- Dock posts standing in water no longer count toward bare base: water beside a post hides its base line, and Opus
  5.5's dock failed at 46% however much seagrass it added.
- Builds never reviewed are listed with the failing reviews (build results, msc_status, dev_cycle): a built area of
  150+ placed blocks, merged with builds within 4 blocks, until a review covers its centre. Sonnet 5.5 finished
  without ever reviewing its dock.
- A text display's text written as a quoted JSON string becomes a component, like CustomName since ADR 0093:
  Sonnet 5.5's hologram showed its raw JSON.

## Not done

- build_undo does not drop a never-reviewed area: reviewing the spot clears it.

- One run per model is noisy; a model whose score moves by less than about 10 points is treated as unchanged.
