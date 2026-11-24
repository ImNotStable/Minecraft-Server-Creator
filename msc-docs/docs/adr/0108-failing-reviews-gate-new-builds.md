# ADR 0108: Three failing reviews gate new builds

Status: accepted, 2026-11-24. Decided by Claude, in the Shattered Meridian loop; Jeremiah asked for review feedback
that pushes agents to fix findings before moving on, within the from-scratch rule (ADR 0106).

## Why

Gemini runs 9-15 fixed nearly every review they ran (run 15: 18 of 18 passing). Codex runs on GPT-6.2-Sol (13, 14,
17, 18) read every failing review, in `world_summary`, at the top of `dev_cycle` and as a count after each
`build_batch` (ADR 0105), and went on to the next build: 1 of 14, 0 of 14, 2 of 10 and 0 of 3 reviews passing at the
end. Advice did not change what they did; their reports at least admitted it.

## Decision

While three or more reviews are failing (`OpenReviews`, including "edited since it passed"), `build_batch` refuses a
build whose box touches none of the failing regions, and lists them: "nothing placed: N reviews still fail, so no new
build starts until they are fixed". Builds inside a failing region go ahead, since that is the fix, as do dry runs,
reviews, renders, tours and every non-building tool. A passing review of the same region removes it from the count.

## Not done

- A build that cannot be fixed (a deliberate ruin) has no way past the gate except passing its review; revisit if a
  run shows that.
