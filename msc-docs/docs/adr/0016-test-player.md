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

## Consequences

- One more tool and one more module; the tool-list guard goes up to 15,600 characters.
- MCProtocolLib must be updated with every Minecraft release, together with Paper. Dependabot sees it through the catalog.
- Paper-only for now in practice, since only Paper dev servers were checked end to end; the client itself is platform-neutral.
