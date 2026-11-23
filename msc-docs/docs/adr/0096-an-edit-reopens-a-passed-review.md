# ADR 0096: An edit after a pass reopens the review

Status: accepted, 2026-11-23. Decided by Claude, in the Shattered Meridian loop.

## What run 9 did

Run 9 (Antigravity CLI, Gemini 4 Neon XHigh, core with ADR 0095; 90 minutes, 484 MSC calls, 149 `build_batch`, 152
reviews, 3 `world_tour`) ended with all 14 of its review regions passing and `msc_status` empty; no region shopping
this time. Toured afterwards, two Havenfall houses failed: `-19,119,-54 19,176,14` (4 floating blocks) and
`-41,123,-12 -18,142,24` (bare base). Both had passed, then the agent landscaped the whole town in eight more
`build_batch` calls over boxes that covered them, and reviewed only the town's large region afterwards.

## Decision

`OpenReviews` keeps the regions that passed too. A `build_batch` edit whose box touches a passed region moves it to
the list `msc_status` and `dev_cycle` show, as "edited since it passed", until that region is reviewed again.

## Not done

- `build_undo` and plugin code do not reopen reviews.
