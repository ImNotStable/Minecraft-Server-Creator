# ADR 0113: A tougher benchmark, judged blind on looks

Status: accepted, 2026-11-25. Decided by Claude; Jeremiah asked for much tougher benchmarks with stricter, more
consistent requirements, blind judging, and low usage, and left the design to Claude.

## Why

On ADR 0109's hamlet, nine of eleven models scored 93 or more: the score was mostly "do the reviews pass", and they
did. Jeremiah's goal is the player's first look at a server: builds that read as player-made keep players, poor ones
drive them away. So looks has to carry the score, judged by someone who does not know which model built what.

## Decision

- **Task:** `brief-village.md` (in `D:\Minecraft Servers\MSC Bench`). One fixed seed (20261125) and site: a village on
  the hilly plains around -100,-110 and a farm quarter in the savanna around -24,-66, joined by a road. At least eight
  buildings that clearly differ (town hall, smithy, inn with a furnished upstairs, chapel, mill, houses; barn and
  granary in the savanna), furnished and lit interiors, lit streets that follow the land, sign, hologram, four NPCs.
  Plan before building; build only, no code. Same seed and site for every model, so only the builds differ.
- **Runs:** two per model, medium effort, same pinned core, one fresh workspace each.
- **Score (100):**
  - Looks 60, blind and pairwise. Each run is shown as two images, an iso `world_render` of the whole village and a
    `world_tour` sheet of it, copied to anonymous names. A judge model picks the village a new player would rather
    stay in, A or B, and the pair is asked again in the other order so position does not decide. Each run meets about
    four others; a Bradley-Terry fit of the wins gives each run a strength, scaled 0-60 across the field. The judge is
    never one of the two builders' models (Claude Sonnet 5.5 at low effort, Claude Opus 5.5 when Sonnet built one of
    the pair).
  - Brief 30, from the world and the run's own list: eight distinct buildings (12, 1.5 each, none counted twice when
    the town review calls them look-alike, ADR 0111), the savanna farm quarter (6), sign, hologram and four NPCs that
    stay put (6), lit streets and stairs that walk (6).
  - Honesty 10: the report matches the re-review.
  - **Cap:** any failing review of the whole village, judged building by building with the same core afterwards,
    caps the total at 50. A village MSC calls unfinished is not a good first look however it renders.
- **Usage:** judge calls are short (two images and one line back); a model's second run only goes ahead when its
  first finished cleanly; runs wait for quota rather than retry.

## Not done

- A judge model shares the builders' blind spots; a human pass over the top and bottom pairs is the check on it.
- Bradley-Terry over about four pairings per run ranks coarsely; ties within a few points mean nothing.
