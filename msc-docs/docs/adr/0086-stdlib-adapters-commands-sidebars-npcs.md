# ADR 0086: Commands, sidebars, NPCs, recipes, permissions and Vault in the stdlib adapters

Status: accepted, 2026-11-23. Decided by Claude, finishing the standard library half of Jeremiah's gap list: "commands
and sidebars exist only on Paper", "no NPCs", "no permissions, loot tables or recipes", "no Vault bridge for economy",
"Forge and NeoForge are nearly empty". Verified against main first: Forge and NeoForge were not empty (they compile
the shared msc-stdlib-vanilla code, as Fabric does) and Minestom already had sidebars (`Boards`); what was missing was
commands everywhere but Paper, sidebars on the mod loaders, and everything below.

## Decision

msc-stdlib 2.5.0:

- **`cmd.CommandTree`** (core): the shape of Paper's `Cmd` (subcommands and aliases, permission nodes, player-only,
  cooldowns, usage and help that lists only what the sender may run, tab completion) without a platform. Adapters
  register a tree with the server and pass each run in as a `CommandTree.Source`:
  - Fabric, Forge, NeoForge (`Cmd`, shared vanilla code): a Brigadier literal with a greedy argument and suggestions,
    aliases as redirects, added whenever the game builds its dispatcher (each loader's command registration event, so
    `/reload` keeps them). Nodes go through `Permissions` when given, else need an operator.
  - Minestom (`Cmd`): a Minestom command with a string-array argument; nodes need `Permissions` (Minestom has none).
  - Velocity (`Cmd`): a `SimpleCommand`; nodes are Velocity's own (a permission plugin grants them).
- **`Sidebar`** on the mod loaders: per-player, up to 15 lines of any text without score numbers, sent as packets so
  the world's scoreboard is untouched; refreshed on a timer if asked; hidden on leave.
- **`Npcs`** on Paper, the mod loaders and Minestom: a mob that stands still, cannot be hurt, keeps a name, turns its
  head to the nearest player, and runs code or a `Dialogue` on a right-click; dialogue choices are clickable chat. The
  mod loaders gained an entity interaction hook and the `/npcchoice` command the chat lines run.
- **`Recipes`** on Paper and the mod loaders: write a `Datapack` into the world's `datapacks` folder and reload.
- **`PaperPermissions`**: `Permissions` fed into Bukkit through a permission attachment per player, refreshed on join,
  after changes, and every minute for timed nodes.
- **`VaultBridge`**: registers an `Economy` with Vault so other plugins use it. Vault is not a compile dependency:
  the bridge is a `java.lang.reflect.Proxy` of Vault's interface, built only when Vault is installed, answering by
  method name; bank calls answer NOT_IMPLEMENTED. This is the one third-party bridge here; the plugin survey's other
  bridges (FAWE, FancyNpcs, BetterModel) stay with that work.

## Consequences

- A generated mod gets the same command, sidebar and NPC tools a Paper plugin has.
- Not run on live servers in this change: covered by MockBukkit (permissions, Vault answers), Fabric's test loader
  (commands in a real dispatcher) and compilation against each platform.
