# ADR 0071: Antigravity rerun of The Shattered Meridian, and what it still needed

Status: accepted, 2026-11-22. Decided by Claude. Jeremiah asked for a second Antigravity run of "The Shattered
Meridian" (the 37 KB design of ADR 0068) on current MSC, to measure whether the quality bar of ADR 0068 (guides,
`world_summary review=true`, texture warnings) and msc-stdlib 2.3.0 make the result better.

## How it was run

- MSC: `~/.msc/repo` pulled to Gitea main (8353f5e0), `installMsc` run; the workspace plugin's `MscLauncher.java`
  replaced with the fixed one. The agent's first `msc_status` showed the core built 2026-11-22T05:07Z and the
  tool list of today (27 tools, `build_batch`, `player_test`, `msc_workspace`, the quality-bar instructions).
- Project: everything but `.agents/plugins/msc` (plugin.json, mcp_config.json, launcher) and
  `The_Shattered_Meridian.md` removed, after a zip of the whole folder
  (`D:\Minecraft Servers\The Shattered Meridian run1 backup 2026-11-21.zip`, 1,538 files).
- Agent: Antigravity's own CLI (`agy -p`), Gemini 4 Neon (XHigh), the strongest Gemini it lists. Screen control of
  the Antigravity app was refused twice, so the app was not driven; the CLI runs the same agent and keeps the same
  transcripts (`~/.gemini/antigravity-cli/brain/db456193-.../`). Prompt: the design file plus "Be entirely
  autonomous, complete it in one go", the first run's two messages in one. Headless mode denies MCP calls unless
  allowed, so it ran with `--settings '{"permissions":{"allow":["mcp(*)"]}}'` (only `mcp(*)` matches; `mcp(msc)`,
  `mcp(msc_msc)` and bare `mcp` do not).
- The model differs from the first run (Gemini 4 Argon, medium), so part of the gain is the model's.

## Result

| | Run 1 (ADR 0068) | Run 2 |
|---|---|---|
| Core | 11 days old, pre-ADR 0019 tools | current |
| Time | 23 min, then 3 more prompts from Jeremiah | 36 min, no prompt |
| Model tokens | 896k in + 7.7M cached, 125k out | 315k in + 8.8M cached, 182k out (+30k thinking) |
| MSC calls (failed) | 115 (5) | 147 (3) |
| Guides read | none existed | build, textures, ux |
| World | 774-line `WorldArchitect.java` placing blocks in loops; 1 `build_structure`, 7 `build_fill` | 42 `build_batch`, no block-placing world code; 16 `world_render`, 16 `review=true` |
| Reviews acted on | none | 2 regions re-built until they passed; 3 left with "fix first" (sky citadel 4% detail, frost region 4%, a flat roof) |
| Textures | 4 stock presets, 8 drawings of 5-7 flat colours | 17 drawn, 7-10 colour ramps, 3 animated, 0 presets; redrew all 4 that got a shading warning |
| UX | hand-rolled chest menu, sidebar, boss bar | msc-stdlib `Gui`, `Feedback`, `Sounds`, `Text`, `ItemBuilder`, `PlayerData` (23 of 32 files): 12 menus, sidebar, titles, action bar, boss bar, first-join flow, custom-model items |
| Testing | none in game | `player_test` twice: join flow, pack (sha1 ok), HUD, menus, restoration, a world event, a dungeon boss |

The renders show terrain, a hub town with roads, a harbour and labelled landmarks, a floating astral tower, a giant
tree, a drowned dungeon; the first run was a green slab in a sea. ADR 0068's bar and guides worked on a non-Claude
model: every point of its "what the agent did" list is gone.

## What it still got wrong, and the fixes

1. **Speckled walls.** The dungeon and the astral tower are random mixes of clashing blocks (deepslate with
   prismarine and copper, quartz with amethyst and gold): static, not texture. The review had no measure for it, so
   it passed them. Fix: `review=true` reports **speckled faces**, the share of wall faces whose colour (the renderer's
   own block colours, now `BlockColors.side`, public) is over 64 RGB apart from at least 3 of its 4 neighbours in the
   wall, and says "mix only near tones, put contrast in lines" above 20%. A checkerboard of near tones (stone_bricks
   with mossy_stone_bricks, 21 apart) scores 0%; the build guide's palette section says the same.
2. **A tree canopy was a "flat roof".** The giant-tree review asked for pitched stair roofs. Tops of leaves no longer
   count as roof.
3. **Three "fix first" reviews were never re-checked.** The fix list now ends "then review this region again".
4. **Unknown texture symbols came one per call** (two failed draws). Like odd-width rows in ADR 0068, every unknown
   symbol and its rows are now named at once.
5. **Every Claude Code session on Flux runs the 11-day-old core too.** Jeremiah's `~/.claude.json` still has the
   user-scope `claude mcp add msc -- java -cp ~/.msc/core/lib/* ...` line from before the plugin (ADR 0021), and the
   flat `~/.msc/core/lib` cannot be refreshed while any session holds its jars ("Could not refresh the older
   install", seen at this install). The flat install never warned: `newerInstall` looked only beside an install,
   and the versioned installs sit inside the flat folder. It now looks inside too and says to run
   `claude mcp remove msc -s user` and use the plugin. Removing the line is Jeremiah's call; this ADR does not.

## Not done

- **A runtime structure paste in msc-stdlib.** The build guide says to swap prepared states from plugin code, but
  the standard library has no `.schem` loader, so the agent wrote a 228-line NBT reader (`SchematicPaster`). That
  belongs in msc-stdlib-paper (and the other platforms), as its own piece of work.
- **The speckle and canopy rules are unmeasured on this world:** the core this session's tools reach is the stale
  one of point 5. The thresholds are rules of thumb like ADR 0068's, checked by tests on built grids.
- **Renders show unsupported glyphs as `?`** in text-display labels (the agent used `✦`); in game they are fine.
