# ADR 0016: A headless test player for play-testing

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

The Gearfall playtest built a parkour course and a combat arena through MSC, but the AI could only read blocks back. Whether a jump is makeable, whether a button does its job, or whether a mob kills a player in two hits needed a person to join. The playtest named a test player as the most valuable missing tool.

## Decision

1. **A protocol client, not a server-side fake player.** `msc-bot` joins the dev server like a real offline-mode client, using MCProtocolLib 26.3-3 (GeyserMC, from `repo.opencollab.dev`, content-filtered to its own groups). The server runs its normal movement checks, damage, plugins and pack offer, so what the bot sees is what a player sees. A fake player made inside the Paper agent would skip the network path and would need a separate version for every platform.
2. **One tool in the core: `player_test`.** It takes a list of actions (`goto`, `forward`, `jump`, `look`, `lookat`, `use`, `attack`, `slot`, `say`, `/command`, `tp`, `entities`, `wait`) and answers with a line per action plus what happened meanwhile: chat, titles, damage, deaths, server corrections and the pack offer (downloaded and checked against its SHA-1). The bot stays joined between calls unless `leave` is set. The bot lives in its own module so the core does not depend on MCProtocolLib's packet classes outside one tool.
3. **Client-side physics is our own small simulation.** MCProtocolLib only moves packets, so `Physics` copies the vanilla player rules: input acceleration, ground friction from block slipperiness, gravity 0.08, drag 0.98, jump 0.42 plus the sprint boost, a 0.6 step, and the 0.6 x 1.8 box with vanilla's float sizes (a double 0.6 box ends a hair inside a wall at contact, and Paper then pulls the player back). Results match vanilla for walk (4.32 blocks/s), sprint (5.61) and jump height (1.25). Not simulated: water, ladders, ice-boat style speeds, potion effects, and some exact jump arcs; the bot clears a 3-block sprint jump but the simulation is not tuned for 4-block jumps. The tool description says a failed jump may still be fine for a person.
4. **Blocks come from the agent, not from chunk packets.** The bot reads the 11-block cube around it through `world_read`, refreshed every 10 ticks. That avoids a chunk decoder that changes every Minecraft version, at the cost of only working while the agent is connected, which `player_test` requires anyway.
5. **Join setup goes through the console.** The tool runs `op <name>` and `gamemode <mode> <name>` (survival by default), so `/command` and `tp` steps work. It joins the address in the server's `server.properties`, and needs `online-mode=false`, which MSC's dev servers already use.

6. **One client per protocol version, chosen by a server list ping.** (Added after the Tidewright playtest, where Minestom ran 26.2 and the 26.3 bot was turned away.) MCProtocolLib supports one Minecraft version per release. The bot is compiled against the newest one. The previous version ships in the install as `bot/<version>/protocol-<version>.jar`, from the catalog's `mcprotocollib-previous`, and is not on the class path. `Bots` pings the server first. If the protocol is not the built-in one, it loads the bot classes again in a child-first class loader with that jar, so they link against the old MCProtocolLib. The core sees the bot only through the shared `Bot` interface. The two calls whose API differs between the versions go through reflection (`Compat`). A server on any other version gets an error naming the versions the bot speaks. ViaVersion-style translation was rejected: it would be a larger dependency than the whole bot. `mcprotocollib-previous` must stay one Minecraft version behind `mcprotocollib`; `BotsTest` fails if a dependency update moves it.
7. **A kick is an error.** Being turned away while joining fails the join, and being kicked during the actions fails the call. The error leads with the reason and keeps the report up to the kick.

## Consequences

- One more tool and one more module; the tool-list guard goes up to 15,600 characters.
- MCProtocolLib must be updated with every Minecraft release, together with Paper. Dependabot sees it through the catalog.
- Checked end to end on Paper 26.3 and Minestom 26.2. On Minestom there are no `op` or `gamemode` commands, so the player keeps the server's game mode, and the join line says so.
