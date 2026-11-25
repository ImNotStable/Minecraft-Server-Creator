# ADR 0115: Apache-2.0 license and third-party notices for the public repository

Status: accepted, 2026-11-25. Decided by Claude; Jeremiah made the GitHub repository public and asked for a license
and the other files a public repository needs, with every dependency's license followed.

## Context

The repository had no license, so nobody could legally use, change or share it. MSC ships an MCP server distribution
(msc-core), agent jars for six platforms, a standard library that scaffolded projects bundle, and a Claude Code plugin.

## Audit

The runtime class path of msc-core was resolved (Maven, the same coordinates and BOMs as the catalog) and each
artifact's license read from its POM; every agent build script was read for what its jar bundles.

- **Shipped in msc-core**: all Apache-2.0, MIT or MIT-0, except `net.raphimc:MinecraftAuth` (LGPL-3.0), which comes in
  through MCProtocolLib. MSC ships it unmodified as its own jar in `lib/`, loaded from the class path, which keeps it
  replaceable; LGPL-3.0 then asks for a notice and the LGPL and GPL texts, which the distribution now carries.
- **Agent jars**: Paper, NeoForge, Forge and Minestom shade only `msc-agent-common` and `msc-protocol`; Fabric nests
  three Fabric API modules (Apache-2.0) as unmodified jars. No agent bundles Minecraft, Paper, Forge or NeoForge.
- **Compile-only**: paper-api (GPL-3.0 Bukkit parts), Forge and NeoForge (LGPL-2.1), Minecraft itself (Mojang's
  terms). Not distributed; Apache-2.0 code may call GPL-3.0 and LGPL APIs, and the server supplies them at run time.
- **Tests only**: MariaDB client (LGPL-2.1), JUnit (EPL-2.0), the rest permissive. Never distributed.
- **Scaffolded projects** bundle msc-stdlib (now Apache-2.0), HikariCP (Apache-2.0), and, by the project's own
  choice, Adventure (MIT), H2 (MPL-2.0/EPL-1.0), Mongo (Apache-2.0) or Jedis (MIT). All allow that.

## Decision

- **Apache-2.0** for the whole repository: permissive, with an explicit patent grant, and compatible with every
  license above (GPL-3.0 included, for the Bukkit API). `LICENSE` is the plain text; `NOTICE` holds the copyright and
  says MSC is not affiliated with Mojang or Microsoft.
- `THIRD-PARTY-NOTICES.md` lists each library by where it ends up (shipped, agent jar, scaffolded, compile-only,
  build) with its license, the MIT text, and the LGPL note. `licenses/` has the LGPL-3.0 and GPL-3.0 texts.
- The msc-core distribution carries `LICENSE`, `NOTICE`, `THIRD-PARTY-NOTICES.md` and `licenses/` at its root; every
  jar MSC builds carries `LICENSE` and `NOTICE` in `META-INF` (msc.java-conventions).
- Guards: `LicenseNoticesTest` (msc-core repoChecks) fails when a catalog library is missing from the notices, so a
  new dependency comes with its license. `checkAgentJars` (msc-core, part of `check`) fails when an agent jar contains
  a `net/minecraft/` or `com/mojang/` class.
- `CONTRIBUTING.md` (where work happens, the from-scratch rule, inbound = outbound Apache-2.0) and `SECURITY.md`
  (private reporting) are added; the plugin manifest names the license.

## Not done

- No code of conduct file: the repository takes no outside pull requests on GitHub yet (it mirrors Gitea), so
  CONTRIBUTING says how to raise issues instead. Add one if outside contributions open.
- No per-file license headers: Apache-2.0 does not require them, and they would cost every reader tokens.
