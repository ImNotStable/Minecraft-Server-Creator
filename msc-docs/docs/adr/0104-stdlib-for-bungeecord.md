# ADR 0104: msc-stdlib for BungeeCord and Waterfall

Status: accepted, 2026-11-24. Decided by Claude, from Jeremiah's open list (ADR 0088's "not done": a msc-stdlib
adapter for BungeeCord).

## Context

Every project template but `waterfall-plugin` shipped msc-stdlib (ADR 0066). Waterfall runs BungeeCord's plugin API,
which has no Adventure: chat is BungeeCord's own `BaseComponent`. msc-stdlib's text, menus and messages are written on
Adventure and MiniMessage, and its scheduler, command tree, player data and messaging need a platform adapter, as
Velocity's does. BungeeCord itself stays uninstalled by `server_setup` (ADR 0045); the adapter is a library, so it
serves plugins on BungeeCord and Waterfall alike.

## Decision

1. **`msc-stdlib-bungeecord`** (package `io.github.imnotstable.msc.stdlib.bungeecord`), compiled against
   `waterfall-api` 1.21 (BungeeCord's API plus Waterfall's), with the same surface as the Velocity adapter:
   - `Stdlib.init(this)` in `onEnable`; `BungeeScheduler` (ticks as 50 ms on BungeeCord's task pool, no main thread);
   - `Cmd.register(CommandTree)`: a BungeeCord `Command` + `TabExecutor` from msc-stdlib's command tree, permissions
     checked by the tree (BungeeCord's own check is left out so the message is the tree's);
   - `PlayerData` (network-wide, loaded on `PostLoginEvent`, saved on `PlayerDisconnectEvent` through a listener object,
     since BungeeCord finds handlers by annotation), `Lang` (the player's client locale), `Servers` (send with a
     future, current server, players on a server, broadcast);
   - `BungeeText`: an Adventure component as BungeeCord chat, through its JSON and BungeeCord's
     `ComponentSerializer.deserialize`, so colours, hover and click survive.
2. **Adventure is bundled** with the adapter (MiniMessage, plain, legacy and Gson serializers; Gson itself comes with
   BungeeCord), as Minestom's adapter bundles MiniMessage.
3. **`waterfall-plugin` ships it** like `velocity-plugin`: `libs/repo/`, the catalog entry, bundled into the plugin
   jar, the API index, sources and a graphify graph (`scripts/stdlib-graph.sh` now builds `bungeecord`), and one line in
   the template's CLAUDE.md. Nothing calls it in the template (ADR 0073): the entry point stays empty.
4. msc-stdlib goes to **2.6.0** (a new module).

## Verified

Unit tests against fakes of BungeeCord's interfaces (scheduler units, MiniMessage to chat with colours, languages,
command permissions, dispatch and completion); `verifyTemplates` builds `waterfall-plugin` with the library; on omen a
scaffolded `waterfall-plugin` calling `Stdlib.init`, `Cmd.register` and the scheduler was built and deployed with
`dev_cycle` to Waterfall 1.21 build 615: the command answered on the console and the scheduled task logged.

## Consequences

- Every project template ships msc-stdlib.
- Not done: menus and other game-side UI on a proxy (a proxy has no inventories to show), and a chat prompt (as on
  Velocity).
