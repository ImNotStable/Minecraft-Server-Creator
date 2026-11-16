# msc-plugin

The `msc` Claude Code plugin ([ADR 0021](../msc-docs/docs/adr/0021-plugin-install.md)). The repo root's
`.claude-plugin/marketplace.json` lists it, so `/plugin marketplace add <repo folder or ImNotStable/Minecraft-Server-Creator>`
then `/plugin install msc@msc` adds it.

- `.claude-plugin/plugin.json`: the manifest.
- `.mcp.json`: one MCP server, `msc`, run as `java <plugin>/launcher/.../MscLauncher.java` (Java's single-file source
  launch: no jar to build or ship).
- `launcher/`: `MscLauncher`, one source file on the Java 17 API. It picks the install under `~/.msc/core`
  (`MSC_CORE_HOME`) that was installed last, then runs its `msc-core` in the same JVM when that is Java 25+, or as a
  child with the Java the install was built with (`MSC_CORE_JAVA`, `install.properties` `java`, `JAVA_HOME`) that
  shares stdin and stdout. The workspace is the folder Claude Code runs the server in.
- `src/test/`: unit tests for the choice of install and Java, checks of the plugin files, and an end-to-end test that
  starts the real core (from `:msc-core:installDist`) through the launcher, in-process and as a child, and gets an MCP
  `initialize` answer.

`./gradlew :msc-plugin:test` runs them.
