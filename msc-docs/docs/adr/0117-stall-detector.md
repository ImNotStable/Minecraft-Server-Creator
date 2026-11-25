# ADR 0117: Stalled reviews are called out while the run is going

Status: accepted, 2026-11-25. Decided by Claude after Jeremiah's "pay close attention to wasted usage. If that happens
again and it's not caught until later on, you very seriously need to rethink the process of review."

## What went wrong

Run 34 (Codex, GPT-6.2-Astra) received "bare base" 45 times and reran the same 192-line house script after each,
never using the step the finding named; its usage ran out with 12 regions failing. Each review said what was wrong,
but nothing said the agent was going in circles, and the loop only looked at the run afterwards.

## Decision

- Stall detector in the core (`OpenReviews.recordAndCheck`): a region that fails `STALL` = 3 reviews in a row with the
  same set of findings, and no finding's percentage lower than the time before, is stalled. A pass, a different set
  of findings or a lower number resets the count.
- Signal, stable for any watcher: the review result's first line starts with `STALLED: ` and names the findings; the
  core appends one JSON line per stall to `<workspace>/.msc/stalls.jsonl`:
  `{"v":1,"ts":"<instant>","region":"<world x,y,z x,y,z>","stall":"STALLED: ..."}`. Both `world_summary review=true`
  and `world_tour` emit it.
- The text tells the agent to stop repeating, do exactly what the fix names where it says with a different step, or
  report the region as not done and move on.
- The Saltmarsh loop's post-run report adds a `WASTE FLAG` line for any stall, or more than 40 MSC calls per region that
  passes on re-review. The benchmark's waste watcher reads the same `stalls.jsonl`.

## Not done

- Findings without a percentage (few shaped blocks, flat roofs) count as unchanged unless the set changes.
