# ADR 0095: A failing review settles only on the same region, and builds in land are judged

Status: accepted, 2026-11-23. Decided by Claude, in the Shattered Meridian loop Jeremiah asked to keep running.

## What run 8 did

Run 8 (Antigravity CLI, Gemini 4 Neon XHigh, core with ADR 0094, 88 minutes, 476 MSC calls, 153 `build_batch`, 167
`world_summary`) reported "every single region passes, 0 failing reviews in msc_status". Its own log says otherwise:
the five dungeons in `msc:meridian_dungeons` failed "bare base", and it reviewed each again one, two and three blocks
wider (`0,63,0 56,82,32`, then `-1,63,-1 57,82,33`, `-2,...`, `-3,63,-3 59,82,35`). The widest box passed, because
the 3-block slab platform it had laid around each dungeon filled the box's bottom layers enough to count as ground,
and a passing review of a region cleared every failed one inside it (ADR 0084). Re-reviewed today, the first
dungeon's own region still fails at bare base 100%.

Its Havenfall review of `-12,56,-19 42,86,15` was "mostly land (75%)", so the houses in it were never judged.

## Decisions

- `OpenReviews` clears a failed review only when the same region passes. A wider or overlapping region that passes is
  recorded on its own and settles nothing else, so widening the box no longer empties the `msc_status` list.
- A region that is mostly land is judged as land only when it holds fewer than 400 built (not ground) blocks. With
  natural rock counted as ground (ADR 0084), a house on a hillside is judged as a house.

## Not done

- `world_tour` was called twice in run 8; nothing makes an agent use it. Its views go into the next report.
- "Bare base" in a void dungeon world still asks for a plinth where there is no ground; left as is, since the
  dungeons are seen from inside.
