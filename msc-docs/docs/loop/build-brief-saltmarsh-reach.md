# Build brief: Saltmarsh Reach

A build-only commission. Build the place below in a Minecraft Paper server with MSC's tools. There is no plugin, no
gameplay code and no player testing to do: only the world and what stands in it. Everything is built from scratch,
block by block, by you.

## The place

Saltmarsh Reach is a small trading town where a river meets a cold sea, on rolling land in its own generated world.

1. **The land.** Make a world for it with `world_create`, then shape the town's ground: a river running down to the
   sea through the town, a low hill on one side, a beach and a rocky headland. Paths link every building, a stone
   bridge crosses the river, and the ground near buildings is worn, planted and lit.
2. **The harbour.** A timber quay on posts along the river mouth, a fishing hut, a net-drying rack, two moored boats
   that look like boats (hulls with curved sides, a mast and a sail or furled sail), crates and barrels.
3. **Six houses**, no two alike: different sizes, floor plans, materials and roof shapes (at least one two-storey,
   one built into the hill). Each has furnished rooms, lit at night, glazed windows, working doors, and a garden,
   yard or porch.
4. **The market square** with stalls, a well or fountain and lamp posts.
5. **The inn**, the largest building: a common room with a hearth, a kitchen, a cellar and guest rooms upstairs.
6. **The lighthouse** on the headland, tall and tapered, with a lit lamp room and a way up inside.
7. **A ruined watchtower** on the hill: broken, overgrown, but clearly once a tower.
8. **People and signs**, placed in the world with `build_batch` (no plugin code): townsfolk who stand where they
   work (the innkeeper behind the bar, a fishmonger at a stall, a harbour master on the quay, each named and dressed
   for the job), animals in yards and pens, item frames, armour stands, banners and signs on the buildings, and a
   floating text sign (a hologram) at the town entrance welcoming travellers.

## Done means

Every building, the square, the harbour and the land pass `world_summary review=true` on their own regions, every
building has been walked with `world_tour`, and `msc_status` lists no failing review. Report what you built and any
review you could not make pass, honestly.
