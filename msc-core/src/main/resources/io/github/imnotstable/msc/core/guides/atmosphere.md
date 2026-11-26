ATMOSPHERE GUIDE: what makes a place feel alive once it looks right. Sound, movement, light and sky are yours to choose
for each place's story; MSC has no ambience presets, sound lists or particle recipes (ADR 0106). Do this after a build
passes its review, in the same calls as its last detail fixes: it costs a few blocks and one plugin class, not a pass.

DONE MEANS
- Each region has a mood you can say in a few words (a damp harbour at dusk, a forge's dry heat, a still crypt), and
  its sound, movement, light and sky all say the same thing.
- world_summary review=true prints "atmosphere: N sounding and M moving blocks, K light kinds". A build of 300+ blocks
  with nothing sounding or moving fails as "still"; 8+ lights all of one kind fail as "one light".
- Renders show no sound, particles or weather. In the report, give each region one line: what a visitor hears, what
  moves, the light, and the sky (time, weather, biome).

PLAN (add one line to the build's plan)
- Mood, time of day, weather, two or three things you hear, what moves, the light's colour and where it is brightest.
  Neighbouring regions should differ in at least two of these, so walking between them is felt.

LIGHT AND MOOD
- Light by use, as people would: fire where they cook and warm up, candles on tables and altars, lanterns at doors and
  along paths, glowing plants where nature glows. One fixture everywhere reads as a placeholder.
- Colour is mood. Fire, torches, lanterns, candles, glowstone and shroomlight are warm; soul fire, soul lanterns, sea
  lanterns, end rods and some froglights are cold. Pick one key colour per region and a contrasting accent at its
  focal point.
- Pools of light and shadow, not even brightness: brightest at the focal point and the doors, dimmer at the edges,
  lights at several heights (floor, table, wall, hanging). A light within 6 blocks of every floor keeps mobs out (the
  dark rooms and dark streets checks); hidden light[level=N] fills that need but flattens the mood, so use it sparingly.
- Dark on purpose (a crypt, a mine) is mood too: keep the floor just lit enough, or stop spawning there in code.

SOUND
- Blocks that sound by themselves: campfires and fire crackle, lava pops, flowing water runs (a stream, a fountain's
  spill, a fall: water with a level, not a still pool), lit furnaces, smokers and blast furnaces, bubble columns,
  nether portals, firefly bushes, dry grass. Put each where the story makes that sound, not as decoration.
- Living things: animals in pens, villagers at work. entity still=true is silent; leave it off where a creature should
  be heard, and fence it in instead.
- Code (an ambient loop): a few spots per region, each with its own sound you chose, played at the spot, not at the
  player (Audience.playSound(sound, x, y, z) for players within range), at random gaps (5-20 s), quiet (volume
  0.2-0.6) and with pitch varied a little each time so it never repeats exactly. Your own sounds go in the pack (pack
  guide, type=sound, mono .ogg so they fall off with distance).
- Silence is part of it: not every spot sounds, and music (biome or code) leaves gaps between tracks.

MOVEMENT
- Blocks that move by themselves: torch flames and smoke, campfire smoke (rising from a chimney top is a lived-in
  house; a hay bale under it sends a tall column), candles, end rods, spore blossoms, crying obsidian drips, cherry
  leaves falling, lit furnaces, fire, lava.
- Code: particles in the same ambient loop, few and spread out (1-5 a second per spot, a wide offset, slow): motes in
  a library's light, embers over a forge, spray at a waterfall's foot, fireflies over a pond at night. Spawn only for
  players within range; the server sends every one of them.
- Things that move: water that flows, banners, mobs that walk, doors and lights a plugin opens or turns on as players
  pass.

SKY, WEATHER AND TIME
- A world's weather and time: server_command with the time and weather commands, and this version's game rules for
  the day and weather cycles turned off to keep them (26.x renamed many rules; check a name with server_command
  gamerule <name> before you rely on it). Pick them for the place: a lighthouse at dusk, a storm over a wreck.
- Per region in one world: set each player's own time and weather as they enter (Paper: setPlayerTime, setPlayerWeather)
  and reset it as they leave.
- The biome is the cheapest whole-region ambience: its sky, fog and water colours, its ambient particles, sounds and
  music, rain or snow, with no code. build_batch biome paints one over a box. Pick a game biome whose effects fit, or
  write your own in a datapack (data/<ns>/worldgen/biome/<name>.json, restart to load it) after reading this game
  version's biome format in the game's own data: the server jar's data generator writes every vanilla biome file
  (java -DbundlerMainClass=net.minecraft.data.Main -jar server.jar --server, then generated/data/minecraft/worldgen/biome).

CHECK
- world_summary review=true: the atmosphere line and its "still" and "one light" fixes.
- world_tour at eye height, at dusk or night (time set before the tour) where the mood depends on light: dark corners,
  glare, and whether the focal point is the brightest thing in view.
- Join in game (or ask the person) to hear it: an ambient loop that plays too often or too loud is worse than none.
