# ADR 0119: No wasted reviews

Status: accepted, 2026-11-26. Decided by Claude after Jeremiah asked "What are we doing about those wasted calls?"

## What went wrong

The village benchmark (ADR 0118) counted 9-23% of each run's calls as wasted: 3-22 repeated identical calls, 1-10
builds that placed nothing and 2-10 reviews that returned the same findings as the last one. Most of it was models
reviewing regions again that had not changed since their last review, and getting the whole review back each time,
which then stayed in their context for every later turn. The stall detector (ADR 0117) only speaks after three failing
reviews of changed blocks, and the benchmark's watcher only stops a run that loops; neither stops the call.

## Decision

- **Unchanged regions are not reviewed again** (`OpenReviews.review`). Each review keeps a fingerprint of the region's
  blocks (every block state in order, and whether it was judged on the dev floor). A review of the same region with the
  same fingerprint answers in a few lines:
  `UNCHANGED: no block in this region changed since your last review of it, so the answer is the same. ...` with that
  review's `verdict:` and `fix first:` lines, or `It passed and still passes ... move on.` The full review is still
  what the open-review list, the gate and the ledger get, so nothing downstream changes. The fingerprint is of the
  blocks themselves, not of MSC's own edits, so an edit by `server_command`, plugin code or a player counts too.
  Unchanged re-reviews never count toward a stall: they are not edits that failed. `world_summary review=true` and
  `world_tour` both go through it.
- **The second same-findings review speaks up.** A second failing review in a row with the same findings and no number
  better opens with `SAME FINDINGS: ...` (do what the fix names, where it says, before reviewing again); the third is
  still the stall. The watcher counted these as waste from the second.
- **Identical reads with identical answers** (`ToolRegistry`, `Repeats`): a call of a tool that only reads (world_slice,
  world_summary, world_render, world_tour, world_entities, world_heightmap, server_logs, api_class, api_search,
  texture_view) that repeats one of the last 20 calls exactly (tool and arguments) and gets the same text and image
  back, 200 characters or more, gets one line instead:
  `same result as your identical <tool> call N calls ago: nothing it reads changed since ...`. Only an answer that is
  byte for byte the same is replaced, so nothing new is ever hidden; 20 calls keeps the first answer within what the
  agent still has in context. Errors are never replaced, nor are tools that act
  (dev_cycle, pack, server_setup...): they can give the same text after doing their work again, and the first try
  replaced those too, which 15 core tests caught.
- **No-op builds** (`Builder`): a `build_batch` whose blocks were all already in place answers
  `edit eN: nothing changed: ... Running it again does the same` and no longer reopens a passed review it touches as
  "edited since it passed", which used to ask for one more review that could only say the same.
- The build guide says to review once per round of edits and names the three short answers, so a model knows what
  they mean the first time it meets one.

No preset, template or canned content is added: these are answers about the agent's own calls (from-scratch rule,
ADR 0106).

## Measured

See "Replay on the logged runs" below.

## Not done

- A region reviewed again with different corners is reviewed in full: only the same region settles a review
  (ADR 0095), so a cache keyed by blocks rather than corners would let widened regions through.
- The core cannot refuse a call before it runs; the short answers make a wasted call cheap and say why, they do not
  make it impossible.
