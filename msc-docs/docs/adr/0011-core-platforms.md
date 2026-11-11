# ADR 0011: Fabric and Minestom in the core's lifecycle tools

Status: accepted, 2026-11-11. Decided by Claude under Jeremiah's hand-over of build decisions.

## Context

The Fabric agent ([ADR 0009](0009-fabric-agent.md)) and the Minestom agent ([ADR 0010](0010-minestom-agent.md)) speak the same protocol as the Paper agent, so the world, build, texture and render tools already work once one of them connects. What was Paper-only was getting a server running: `project_scaffold`, `server_setup`, `server_start` and `dev_cycle` downloaded Paper, put jars in `plugins/` and started `paper.jar`. The API index also looked only for the Paper API.

## Decisions

1. **One platform per workspace, kept in `<workspace>/.msc/platform`.** `Platform` is an enum (`paper`, `fabric`, `minestom`) that carries the three things that differ: the jar the server starts from, the folder plugins or mods go in, and whether a Mojang EULA applies. `project_scaffold` writes the file from the template's `platform` property, `server_setup platform=...` overrides it, and `dev_cycle` writes it when the file is missing and the project says what it is. With no file the core reads the project (`fabric.mod.json` means Fabric, `plugin.yml` Paper, a Minestom dependency in the build files Minestom) and falls back to Paper, so existing workspaces behave as before.
2. **No new tools and one new argument.** The same four tools serve every platform; only `server_setup` gained `platform`. The tool list is paid for in every conversation, and the AI should not have to learn a second loop.
3. **Fabric setup.** `server_setup` downloads the Fabric server launcher from Fabric's meta service (`/v2/versions/loader/<minecraft>/<loader>/<installer>/server/jar`, newest stable installer) as `fabric-server-launch.jar`, and the Fabric API mod from Fabric's Maven repository into `mods/fabric-api.jar`, because both the agent and the `fabric-mod` template depend on it. Loader and Fabric API versions are the ones `msc-templates` pins, so the server matches what the scaffolded project compiles against; a different Minecraft version is refused with a one-line error instead of installing a mismatched Fabric API. Both downloads are cached in `.msc/cache`. The launcher fetches the Minecraft server and libraries itself on first start. The server config files are the same vanilla defaults Paper uses.
4. **Minestom has no setup and no EULA step.** A Minestom server is the project's own runnable jar with the agent built in. `server_setup` says so in one line. `dev_cycle` copies the built jar to `server.jar` in the server directory and starts it with `java -jar`; `server_start` before the first build tells the AI to run `dev_cycle`. No Mojang code is downloaded, so there is no `eula.txt` check.
5. **The EULA rule is unchanged for Paper and Fabric.** The core never accepts it: `eula.txt` decides, `acceptEula` is set only when the user has agreed, and `server_start` refuses otherwise ([ADR 0005](0005-core-tools.md), point 12).
6. **Agent jars are chosen by name.** In `.msc/agent`, the jar whose file name contains the platform id wins; a jar named for another platform is never installed, and a jar with no platform in its name is taken on trust. `MSC_AGENT_JAR` still overrides.
7. **The API index follows the platform.** Fabric: the Minecraft jar Loom keeps under `caches/fabric-loom/minecraftMaven` (26.x is unobfuscated, so it is readable as is), every Fabric API module, and the loader. Minestom: the Minestom jar and Adventure. Paper as before.

## Checked

- Minestom, end to end in the cloud on a real Minestom `2026.10.07-26.2` server through MCP: scaffold `minestom-server`, `dev_cycle` (real Gradle build, 17 s, server ready in 1.8 s with the agent connected), `server_command`, `world_summary`, `build_fill`, `world_slice`, `world_render`, `build_undo`, `api_search` on the Minestom jar, `server_logs`, `server_stop`.
- Fabric: `project_scaffold fabric-mod` and `server_setup` against the real Fabric services (launcher for 26.3 with loader 0.20.1, Fabric API 0.166.0+26.3, agent in `mods/`), and `server_start` refusing without the EULA.
- Not yet run: starting a Fabric server and `dev_cycle` on a Fabric mod. That starts Mojang's server, so it needs the accepted `eula.txt` on Jeremiah's PC.

## Consequences

- A fourth platform is one enum constant plus its setup branch.
- A workspace with projects for two platforms needs `server_setup platform=...` to switch; one dev server at a time stays the rule.
- Fabric versions move with `msc-templates`' pins, not with what the user asks for.
