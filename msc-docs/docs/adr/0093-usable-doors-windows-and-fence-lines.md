# ADR 0093: Usable doors, glazed windows and fence lines that join

Status: accepted, 2026-11-23. Decided by Claude. Jeremiah walked the sixth Antigravity run of "The Shattered
Meridian" (ADR 0084) in game and found a plank house whose doors showed only their bottom half, with the floor inside
level with the door's top so no one could walk in, window holes with no glass, and a fence of separate posts. Every
review of that area had passed.

## Why MSC let them through

1. **Half doors.** The agent wrote `set ... spruce_door[facing=east,half=lower,...]` (it says every state, as ADR 0077
   asks) and laid the floor one block higher. Joins only paired a door that left `half` out, and when the block above
   was not free it left the door as one half without a word. The review never looked at doors. In the world: the
   customs office door at -263,66,-226 is a lower half under a cobblestone stair, with the plank floor at y 67.
2. **Blocked doorways.** Nothing compared a door with the floor beside it.
3. **Open windows.** The review counts shaped blocks; a wall with empty holes scores the same as a glazed one.
4. **Fence posts.** Joins connects a new fence to what is beside it but never turned the fence already in the world
   to meet it, so a line built over two calls ends in posts that face away from each other. (The screenshots were not
   readable from here; this is the cause found in the code, and the likely one.)

## Decisions

- `build_batch` checks every door, with its states given or not: its other half must be planned, already in the
  world, or free to add (then it is added, with the same facing and hinge). Otherwise nothing is placed and the error
  names the door and what is in the way.
- `world_summary review=true` reports, before the style numbers: **half doors** (a door half without its partner),
  **blocked doors** (a full block in front of or behind a door's bottom half), and **open holes** (air through a
  1-thick wall under a lintel) when there are 4 or more and they outnumber panes, glass, bars and doors in the region.
  A 1-wide corridor is not a hole. Each fails the review.
- Joins turns an existing fence or pane in the world toward a new one beside it.
- The build guide says where a door's bottom goes relative to the floor and to lay floors before cutting doorways.

## Also from the 16 later screenshots

Jeremiah's further screenshots (Havenfall, 06:46-06:49) showed, besides the doors: villager names shown as raw JSON,
rooms empty and dark, a spider in town, trapdoor clusters as facades (one across a doorway), a ladder that starts in
mid-air, a cherry tree pushed through two houses, wool awnings on posts, a hedge that is a pillar of leaves, a boxy
boat with a stump mast and no sail, the harbour cut as a square pit with sheer walls, and a few one-block window bars.

- **Names as JSON.** The agent wrote `entity ... villager data={...CustomName:'{"text":"Archivist Vela","color":"gold"}'}`,
  the format before 1.21.5. Today the game reads components as SNBT and shows a quoted string as written. The
  `entity` step now drops the quotes around a JSON object or list given as `CustomName` (JSON is valid SNBT).
- **Rooms that pass empty.** Furnishings counted any shaped block under a roof, so trapdoor facades and roof stairs
  under eaves furnished every room. A furnishing and a floor block now count only inside (built blocks within 8 on
  three sides), and trapdoors no longer count.
- **Dark rooms**, new: inside floor with no light within 6 blocks, aim under 25%. Dark rooms are also where mobs spawn
  in a survival town.
- **Broken ladders**, new: a ladder with air below it.
- Left to the render and the agent's own judgement: tree canopies through walls, awnings, hedges, the boat, the harbour's
  sheer cut and trapdoor clutter. The review has no reliable number for them yet.

## Not done

- Fences placed with explicit side states are still kept as written; walls (low/tall) in the world are not re-turned.
- Doors placed by plugin code or pasted schematics are only caught by the review.
