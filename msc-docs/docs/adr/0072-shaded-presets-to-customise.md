# ADR 0072: Shaded texture presets to customise, and why Sky Sprint needed no msc tools

Status: accepted, 2026-11-22. Decided by Claude, on Jeremiah's ask for both quality and efficiency. Superseded by [ADR 0106](0106-from-scratch.md): the shapes are gone.

## Context

The 0.7.0 bench run (`~/.msc/bench-runs/20261122-003103`, all 9 tasks, toolset ×2, 18/18 passed, $7.90) used 27%
fewer tokens per attempt than the 11-21 full run (443k against 610k). Two things stood out:

1. **Textures got 2-3 times slower** (ruby item 88 s to 210 s median, resource pack 53 s to 173 s), with tokens
   about the same. In 3 of 4 attempts Claude drew the 16x16 ruby from scratch with `rows=`, spending 9-12k thinking
   tokens on 256 pixels, because ADR 0068 called `kind=` presets "a draft at most" and warned after one. Only one
   attempt used `kind=gem`, in 46 s.
2. **Sky Sprint called no msc tool** (2.89M tokens median, down from 3.31M; 1,447 s). The init event shows `msc`
   connected with every tool. The agent read the scaffold's `CLAUDE.md`, the template's 16 source and test files,
   and built with `./gradlew build`, as the bench preamble and `CLAUDE.md` say. The 11-21 rerun made 9 `api_search`
   calls; this time the template code already showed every Minestom call the task needs, so there was nothing to look
   up. That is the toolset working as meant (look up only what you do not know), not a fault. The extra time is one
   attempt digging through test XML with `grep` after a failing `./gradlew test`.

## Decision

1. **Presets are real shaded starts.** A `kind=` shape is drawn from a 7-shade ramp: the darkest is the outline and
   the body gets 5 distinct tones (deep shadow, shadow, base, light, glint). Before, a 5-shade ramp mapped deep
   shadow and shadow to the same colour, so a gem had 4 body tones. The requested colour stays the base.
2. **Customise, do not redraw.** The texture result already prints the preset's rows. Its note now says to make it
   yours by editing a few rows (a facet, a notch, an accent) and sending them back with `rows=`, and to draw from
   scratch only when no shape fits. The texture guide and the MCP instructions ("gives a shaded start, edit its rows
   to make it yours") say the same. The quality bar stays: signature items still end as rows, and the shading
   warnings on drawings (ADR 0068) are unchanged.
3. **Sky Sprint: no change.** Tools the agent does not need should stay unused.

## Consequences

- A preset gem now has 6 colours (outline and 5 tones) instead of 5; the bench's ruby rules (at most 8 colours,
  mostly red) still hold.
- Whether agents now edit presets instead of drawing from scratch is measured at the next bench run
  (`run --tasks texture-ruby-item,texture-resource-pack --modes toolset --repeat 2`).
- Not done: steering Minestom agents from `./gradlew test` to `dev_cycle` for failing tests. The bench preamble names
  `./gradlew build`, and one slow attempt is not enough to change both.
