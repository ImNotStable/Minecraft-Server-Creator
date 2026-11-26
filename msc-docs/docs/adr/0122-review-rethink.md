# ADR 0122: Rethinking the review loop

Status: accepted, 2026-11-26. Decided by Claude; Jeremiah: if wasted usage slips through again, rethink the review
process itself.

## What the village runs showed

ADR 0117 (stall detector) and ADR 0119 (UNCHANGED answers, SAME FINDINGS) cut repeated identical calls (Sonnet 22 to 7,
Opus 10 to 2) but not tokens: Sonnet 5.5 on the new core still spent 19% of its calls on waste. Three causes:

- **The fix itself did not work.** Almost every real loop was "bare base". Sonnet trimmed one inn six times while the
  number swung 72, 67, 58, 89, 65, 66%: its walls were set one block into the ground, `trim at=base` picked the buried
  row and placed nothing. Warnings telling it to "do exactly what the fix names" could not help; the named step was
  broken. Fixed in PR #94 (ADR 0118).
- **False stalls.** 3 of Sonnet's 4 STALLED warnings came from a detector that read only percentages while floating
  counts fell 80, 20, 3 (fixed in PR #93).
- **Final sweeps.** Passing buildings were reviewed again after landscaping around them changed blocks inside their
  boxes; the fingerprint saw a change, so the full review came back word for word.

## Decision

- A review loop is first a tool question: when a check fails three times on the same build, the benchmark looks at
  whether the step its fix names actually changes what the check counts, before adding warnings or gates. A warning
  an agent cannot act on is waste too.
- A passing review whose full text equals the last review of the same region answers in a line ("SAME RESULT"), even
  when blocks inside changed. Failing reviews keep their full text and the SAME FINDINGS line, since the agent needs
  the fix.
- The benchmark watcher stops a run only when a region is stuck now (six failing reviews in a row on the same
  findings, the latest within five minutes), not on stalls that were later fixed; every run's result row carries its
  calls, tokens, repeats, no-op builds, repeated findings and stalls.

## Not done

- No new gate: ADR 0108's three-failing gate stays as it is until runs on the fixed tools show loops again.
