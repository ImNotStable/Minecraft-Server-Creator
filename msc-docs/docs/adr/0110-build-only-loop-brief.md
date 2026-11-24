# ADR 0110: The review loop tests building only

Status: accepted, 2026-11-24. Decided by Jeremiah ("change the test from the shattered meridian to something purely
build, as the code is going to waste a lot of usage"; "This would include holograms, npcs, etc."), written up by
Claude.

## Why

Runs 5-20 of the loop gave agents the whole Shattered Meridian design: a 37 KB RPG with fourteen plugin modules.
Most of each run went to plugin code, resource packs and player tests; Codex runs spent 15-25 of their calls on
`player_test`. What the loop measures and improves is MSC's building: reviews, tours and the build guide.

## Decision

From run 21 the loop's task is [Saltmarsh Reach](../loop/build-brief-saltmarsh-reach.md), a build-only brief: a river
town on shaped land in its own generated world, with a harbour and boats, six different houses with interiors, a market
square, an inn with cellar and guest rooms, a lighthouse, a ruined watchtower, and world dressing placed with
`build_batch` (named townsfolk at their work, animals, item frames, armour stands, banners, signs and a hologram), all
from scratch. No plugin, gameplay code or player tests. Done means every region passes its review, every building is
toured, and `msc_status` lists nothing failing.

The rest of the loop stays: agents take turns (Gemini 4 Neon XHigh, Codex), each run is re-reviewed and toured
afterwards, and what the runs show turns into review checks and guide text (ADR 0106: never generators).

## Not done

- Runs 5-20 are not comparable with runs from 21 on.
