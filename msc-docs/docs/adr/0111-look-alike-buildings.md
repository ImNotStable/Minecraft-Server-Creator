# ADR 0111: Look-alike buildings fail a town review

Status: accepted, 2026-11-25. Decided by Claude, in the Saltmarsh Reach loop (ADR 0110).

## Why

On the build-only brief, Codex on GPT-6.2-Astra now passes every review it runs (run 23: 14 of 14, run 25: 19 of 19,
run 26: 19 of 21), touring each building. The brief asks for six houses "no two alike", and the tours show tidy but
similar houses: the same pale wall, dark frame and gable, a little larger or smaller. The reviews judge each building
on its own and have nothing to say about a street of copies.

## Decision

A town-sized review (ADR 0097) compares its buildings: three or more with the same footprint, height (each within a
block) and the same two main materials fail as "look-alike buildings", with the shared materials and the first one's
box, and ask for different floor plans, storeys, roof shapes and materials. Two alike are allowed (a pair of cottages
is normal); three read as copies.

## Not done

- Roof shape and window rhythm are not compared; two houses with different materials but the same plan pass.
