# ADR 0059: Minecraft internals (NMS) in Paper projects

Status: accepted, 2026-11-20, decided by Claude on Jeremiah's request ("ensure NMS is also usable by AI; extremely
customizable").

## Context

Fabric, Quilt, NeoForge and Forge projects already compile against the whole Minecraft server (Loom, ModDevGradle and
ForgeGradle give the game's classes), and the API tools index those jars. Paper projects compiled only against
`paper-api`, so `net.minecraft.*` and `org.bukkit.craftbukkit.*` were out of reach, and `api_search` could not find
them. ADR 0057 had left paperweight-userdev out for its first-build cost.

Measured on omen for Minecraft 26.3 (Mojang ships it unobfuscated, so nothing is remapped):

- paperweight-userdev 2.0.0-beta.24 with the dev bundle of the pinned Paper build (`26.3.build.252-stable`) builds
  the `paper-plugin` template and a call to `((CraftPlayer) p).getHandle().connection.latency()` in 1 min 35 s the
  first time on a machine, then as fast as before: the patched server is kept in the Gradle user home.
- With the server on the test classpath, MockBukkit's tests fail (two Adventure service providers). With
  `addServerDependencyTo = compileOnly`, they pass; a test that reaches NMS code fails with `NoClassDefFoundError`,
  as MockBukkit has no server internals.

## Decision

- `paper-plugin` and `paper-game` apply paperweight-userdev and depend on `paperweight.paperDevBundle(<paper pin>)`,
  the server on `compileOnly` only, tests still on `paper-api` and MockBukkit. Every Paper project can use NMS with no
  extra setup; the dev bundle follows the Paper pin (ADR 0052).
- Their CLAUDE.md says how to reach the internals (`getHandle()` on Craft* objects), to use the Paper API first, to keep
  NMS calls in their own classes that no MockBukkit test reaches, to check them on the dev server, and that NMS ties a
  plugin to one Minecraft version. `paper-plugin`'s recipes add an `Nms` class, compiled by `verifyTemplates`.
- `api_search` and `api_class` on Paper and Purpur also index the newest patched server in
  `~/.gradle/caches/paperweight-userdev/v2/work/applyDevBundlePatches_*/output.jar`, once a Paper project has built.
- `checkUpstreamVersions` lists paperweight, whose releases are all betas, by its newest version of any kind.
- No background first build for Paper templates: the cost is once per machine and Paper version, and a background build
  after scaffold would change files under calls that must change nothing (the bad-input and side-effect tests).

## Consequences

- An AI can write packets, entity AI goals, registry and world-generation code against Mojang names, and look the
  classes up with the API tools, in any Paper project.
- The first Paper build on a machine takes about a minute and a half longer; dev_cycle's build timeout covers it.
- Folia projects get the same NMS access; Folia's threading rules still apply to it.
