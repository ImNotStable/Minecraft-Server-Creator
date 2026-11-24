# ADR 0106: Everything from scratch, everywhere, and a check that keeps it so

Status: accepted, 2026-11-24. Decided on Jeremiah's rule ("the AI using MSC must create EVERYTHING from scratch").
Supersedes [ADR 0072](0072-shaded-presets-to-customise.md) and [ADR 0074](0074-example-shapes.md) (texture shapes and
patterns), the terrain styles of [ADR 0075](0075-land-and-generated-worlds.md), and the stock looks, sounds and messages of the
standard library ([ADR 0066](0066-standard-library.md) onward). Extends [ADR 0101](0101-no-prefab-boats-or-harbours.md)
and [ADR 0103](0103-no-content-presets.md) from `build_batch` to the whole repository.

## Context

ADRs 0101 and 0103 removed the prefab builds from `build_batch`. Jeremiah asked for the rule to hold in every corner
of MSC: the agent creates structures, interiors, terrain, textures, models, code and configs itself; MSC gives only
general primitives, guidance and quality review checks, never prefabs, presets, one-line builders, canned templates or
default assets. An audit of the repository found presets outside `build_batch` too:

- `texture kind=`: six stock item shapes (gem, ingot, orb, sword, pickaxe, food) and four block patterns (noise, ore,
  planks, bricks), with `accent`, `size`, `seed` and `contrast` to tune them.
- msc-stdlib (every platform): `Gui.Theme` presets (DEFAULT, DARK, GOLD, OCEAN, NATURE, DANGER), `Gui.confirm` (a
  ready-made yes/no screen), `ItemBuilder.filler()`, a default click sound, the `Sounds` enum (CLICK, SUCCESS, FAIL,
  VICTORY...), `Feedback.success/fail/info` (styled prefixes with sounds), paged menus with stock arrows and a "Page n/m"
  paper, teleport warm-ups with stock text and sounds, a stock cooldown message on custom items, a stock prompt hint,
  styled NPC dialogue choices and red command errors.
- `build_batch terrain style=hills|ridges|mesa|dunes` (named landforms) and `river`'s stock bed mix.
- The UX guide prescribed exact sounds, colours and symbols.

## Decision

1. **Textures are drawn.** `kind=` and its tuning options are gone with msc-textures' `Shapes` and `Patterns`.
   `texture` takes `rows=` with a palette, or `copy=` to recolour a texture the agent drew. Tests use a hand-drawn
   fixture.
2. **msc-stdlib 3.0.0 ships plumbing, not looks.** `Gui.Theme` stays as a record the agent fills; the presets,
   `confirm`, `filler` and the default click sound (now silent) are gone, and `ItemBuilder.pane(material)` makes an
   unnamed, tooltip-less item of the agent's choice. `Gui.paged` takes a `Gui.Nav` of the agent's buttons. `Sounds`
   only plays a sound the agent picks; `Feedback` sends the agent's text as written. `Teleports.warmup` calls back each
   second and `CustomItems` cooldowns call back when used too soon, so the agent shows its own text. Prompts send only
   the agent's question; dialogue choices and command errors are unstyled. A plain functional error ("Missing argument
   1.") stays: it carries no design.
3. **Terrain noise is math, not landforms.** `terrain noise=fbm|ridged|terraced|waves` (with `scale`, `octaves`,
   `steps`) replaces `style=`; the agent makes the landform from boxes, noises and brushes. `river` needs `bed=`.
4. **Guidance says it.** The MCP instructions open with the rule; the texture and UX guides teach principles instead of
   prescribing shapes, sounds, colours and symbols; every template's `CLAUDE.md`, the README and the wiki state it.
5. **A check keeps it.** `FromScratchTest` (msc-core's `repoChecks`, so every CI build) fails when:
   - `build_batch` gains a step outside the reviewed list of primitives, or documents `style=`, `kind=`, `room=`,
     `preset=` or `theme=` options;
   - any tool gains an option named like a preset picker (style, preset, theme, prefab, template, kind, room, shape,
     design, sample, example), except the allowlisted ones whose reason is written next to them (`project_scaffold
     template`: a bare scaffold; `model_create kind`: Minecraft's model parents; `api_search kind`: a Java type kind);
   - msc-stdlib gains a public constant or enum outside its plumbing allowlist, or a colour or decoration in code
     (MiniMessage tags, Adventure colours, vanilla formatting).
   Adding to an allowlist needs a reason in an ADR. Root `CLAUDE.md` carries the contributor rule.

## Consequences

- Agents spend more tokens drawing textures, designing menus and shaping land; that is the price of servers that do
  not all look alike (ADR 0074 measured about 2x on the texture bench tasks).
- Plugins written against msc-stdlib 2.x that used the removed presets need their own versions; scaffolded projects pin
  the version they shipped with.
- Kept as primitives, judged in review: shapes and fills, brushes, `scatter`, `trim`, `decorate`, `weather`, `river`,
  `tunnel`, `cave` (all in the agent's blocks, ADR 0103); `world_create`'s vanilla terrain (the game's own generator,
  not an MSC design); the bare templates (ADR 0073); bench task prompts (they state requirements, not designs).
- The check reads source text: a preset smuggled in under an unflagged name still needs a reviewer. The test's doc
  says what does not go into its allowlists.
