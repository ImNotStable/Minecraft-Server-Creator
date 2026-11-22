# ADR 0067: A quality bar any agent reads, and numbers to check builds against

Status: accepted, 2026-11-22. Decided by Claude. Jeremiah tested MSC with Google's Antigravity (Gemini 4 Argon,
medium) on "The Shattered Meridian", a 37 KB server design, and found the result plain: boxy builds on a flat
grass plane, generic textures, a basic chest menu, plain scoreboard and bars. He asked why, and for changes so
agents (Claude or not) go above and beyond while spending fewer tokens.

## Context: what the agent did

From its transcript (`~/.gemini/antigravity/brain/a1f7c3ff-.../transcript_full.jsonl`, 320 steps, 891k input and
125k output tokens) and MSC's own log (`.msc/tool-calls.jsonl`, 113 calls in 25 minutes):

1. **It ran an 11-day-old core.** The tool list it got was the one from before ADR 0019 (`build_structure`,
   `build_fill`, `texture_generate`, `msc_stats`, `pack_build`) with the old two-line instructions: no
   `build_batch` mixes and shapes, no `player_test`, no `msc_workspace`. Its setup ran `installMsc` at 19:52:39,
   which made a fresh install, but `~/.msc/core/lib` still held a flat install from 2026-11-11 (before ADR 0021).
   The launcher stamps an install it sees for the first time "installed now"; it first saw the flat one three
   seconds later, so the old core outranked the new one, and keeps doing so.
2. **It read the instructions and every tool schema, then the scaffold's `CLAUDE.md`.** Antigravity caches an MCP
   server's `instructions` and tool definitions as files and the model read them all before starting. It never
   looked for an `AGENTS.md` or `GEMINI.md`. The MCP instructions are the one guide every client reads.
3. **It built the world in plugin code.** 774 lines of `WorldArchitect.java` place blocks in nested loops, run
   once by `/meridian buildworld all`. Only one `build_structure` (a 7-wide gate) and seven `build_fill`s (flat
   continents of stone and grass) went through the build tools. Code builds are never seen while written, so
   they come out as boxes and cylinders.
4. **It rendered but did not critique.** Nine `world_render`s, read back as images, which it called "impressive
   detail" and "exceptional"; the only change after them was one water fill. Nothing gave it a measure to
   disagree with its own impression.
5. **Its textures were presets or hasty drawings.** Four `texture_generate` presets (the stock gem and ingot
   shapes, recoloured) and eight hand-drawn 16x16 items with 5-7 flat colours, one tone each, never checked with
   `texture_view`. Four draws failed on a 17-pixel row, fixed one row per retry.
6. **Its UX was what it had to write itself.** A chest menu holder, a sidebar, a boss bar for events, all
   hand-rolled with no sound or layout conventions (the standard library of ADR 0066 did not exist yet).
7. **It also built inside `.agents/plugins/msc`**, the folder Antigravity started the server in, and copied the
   project out at the end. Current cores have `msc_workspace` and say "Wrong folder: msc_workspace" in the
   instructions, so this needs no new change.

## Decision

1. **The launcher never stamps the flat install.** `MscLauncher.flat` ranks `~/.msc/core/lib` by its core jar's
   time, unless `installMsc` wrote its time (it records `source`). A fresh install now always beats the leftover
   flat one, and the core's existing `LegacyInstall` refresh then updates the flat copy.
2. **The quality bar is in the MCP instructions**, the text every client reads: build in the world with
   `build_batch`, not from plugin code; read a guide before building, texturing or making menus; a build is done
   only after `world_render` and `world_summary review=true` and fixing what they show. Presets are described as
   drafts, rows as the way to draw a signature texture.
3. **Guides on demand: `msc_status guide=build|textures|ux`.** Three short guides in msc-core's resources
   (about 4.2k, 2.4k and 2.3k characters) carry the know-how: the plan, block-out, detail, render-and-review loop;
   60/30/10 palettes, gradients and seven theme palettes as starting points; a detail checklist (depth, shaped
   blocks, roofs, props, nature, age, light); pixel-art ramps, hue-shifted shading, materials, sets; feedback
   channels (sound, action bar, title, boss bar, sidebar), menu layout, first-join flow, all using the standard
   library. They cost nothing until called; a parameter on an existing tool, not a new tool, keeps the tool list
   at 27. `GuidesTest` checks every tool a guide names exists.
4. **`world_summary review=true` judges a build by numbers.** `BuildReview` reads the region (bottom layers 90%+
   full count as ground and are left out) and reports block types and the top shares, detail blocks (stairs,
   slabs, walls, fences, panes, trapdoors, props, plants, lights), flat wall faces (a face whose 3x3 patch is one
   block with no depth change), shaped roof share and lights, then lists what to fix first in plain steps. When
   nothing is below the bar it says so and sends the agent back to the render for silhouette, story and scale.
   The bars (6+ types, top block at most 50%, 12%+ detail, under 45% flat faces, 15%+ shaped roof once a build is
   6+ high) are rules of thumb from hand-built Minecraft architecture; the ground rule keeps a superflat floor from
   counting. It needs no agent change: the core reads blocks as `path` and `diff` do.
5. **Texture drawings get shading warnings.** A 16+ wide drawing with 64+ opaque pixels and fewer than 5 colours,
   or one colour over 40% of it, gets a warning saying how to shade it; a stock `kind=` shape says it is the same
   as every other one and how to start a drawn one from it. A drawing with rows of the wrong width now names every
   odd row at once and takes the width most rows share.
6. **The tool list limit goes from 16,400 to 16,600 characters** for `guide=` and `review=` (now 16,492).

Plugin UX itself (menus, items, sidebar, particles) is the standard library's (ADR 0066); this ADR only sets
the bar and points to it. Asks for it (boss bar, action bar and title helpers, click sounds, menu themes) went to
that work.

## Not done

- No `AGENTS.md` or `GEMINI.md` in templates: the transcript shows the agent reading the MCP instructions and
  `CLAUDE.md`, not those files. Add one if an agent is seen skipping `CLAUDE.md`.
- No image preview in `texture_view`: its rows are readable as text and an image costs tokens on every call.
- No automatic critique of renders by a second model: the review numbers do the disagreeing at no model cost.
- Whether Gemini follows the guides as well as Claude does is unmeasured until the next Antigravity run.
