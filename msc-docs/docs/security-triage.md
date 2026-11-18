# Dependency security triage

Every known advisory against a resolved dependency, and what we did about it. "Shipped" means the
library is inside something we hand to users (the msc-core distribution or an agent jar). "Provided"
means the server platform (Paper, Minecraft/Fabric, Minestom) supplies it at run time and our jars
only compile against it; bumping it in our build would not change what runs.

How to re-check: resolve every module's `compileClasspath` and `runtimeClasspath`
(`./gradlew :<module>:dependencies`) and query the coordinates against OSV
(`https://api.osv.dev/v1/querybatch`, ecosystem `Maven`). GitHub's automatic dependency submission for
Gradle (repository setting, no workflow file) sends the resolved graph, so Dependabot alerts cover
transitive libraries too.

## 2026-11-12

GitHub reported 22 alerts on main (1 critical, 8 high, 12 moderate, 1 low); all 22 are in this table.

| Library (version) | Where it comes from | Advisories | Decision |
|---|---|---|---|
| `tools.jackson.core:jackson-core` / `jackson-databind` 3.1.4 | MCP SDK 2.1.0 (newest stable), in msc-core, **shipped** | GHSA-7hhh-6rmp-j9qf, GHSA-p6pp-m3f8-5c89, GHSA-cxp5-3px4-pw24, GHSA-wv8q-qhhj-9h54, GHSA-q4xh-88c3-wmh7 (high, DoS); GHSA-5gvw-p9qm-jgwh, GHSA-gx83-3vf8-gh7j, GHSA-vvgp-rfg2-7rr6, GHSA-wjgm-6hv5-3cvf (moderate) | **Fixed**: `jackson-bom` 3.1.8 platform in msc-core. Jackson parses every MCP message, so the parser DoS paths are reachable (input comes from the local MCP client, so real risk is low). `DependencyFloorTest` guards the floor. Drop the BOM once an MCP SDK release pulls 3.1.7+ itself. |
| `at.yawk.lz4:lz4-java` 1.10.1 | Minecraft 26.3 server, msc-agent-fabric, **provided** | GHSA-mcr4-qmvw-px4g (high, local temp-dir native lib swap), GHSA-4v53-57pg-c464, GHSA-6cx8-rjf8-pr8g, GHSA-gm45-99xc-r7wv, GHSA-xx22-p4ch-683r (moderate), GHSA-343h-94h5-c4wr (low) | Not ours to fix: the agent never calls LZ4 and does not ship it. Fixed when Mojang updates the server's libraries. |
| `io.netty:netty-handler` / `netty-codec-http` 4.2.16.Final | Minecraft 26.3 server, msc-agent-fabric, **provided** | GHSA-c4c3-7fpv-j4q5 (critical, SNI routing bypass), GHSA-fccg-mwvh-qqg4 (moderate, SNI parsing), GHSA-8c42-7qj2-3j46 (moderate, CORS Vary header) | Not reachable: the Minecraft protocol runs without TLS/SNI and without Netty's HTTP CORS handler, and the agent talks to msc-core over its own link, not Netty. Provided by the server; fixed when Mojang ships Netty 4.2.17+. |
| `com.google.guava:guava` 33.6.0-jre | Minecraft 26.3 server, msc-agent-fabric, **provided** | GHSA-xxph-c9ww-hj94 (high, Java deserialization of Compact* collections) | Not reachable: the agent never Java-deserializes anything. Provided by the server. |
| `org.codehaus.plexus:plexus-utils` 3.5.1 | `paper-api` compile classpath, **provided** | GHSA-6fmv-xxpf-w3cw (high, `extractFile` traversal) | Not reachable: compile-only, never called. |
| `org.apache.commons:commons-lang3` 3.12.0 | `paper-api` compile classpath, **provided** | GHSA-j288-q9x7-2f5v (moderate) | Not reachable: compile-only. |
| `org.apache.logging.log4j:log4j-api` 2.26.0 | `paper-api` compile classpath, **provided** | GHSA-qv9r-c865-cp47 (moderate, MapMessage JSON) | Not reachable: the agent does not log MapMessages. Provided by Paper. |

Gradle plugin classpaths (`buildEnvironment`: Shadow, run-paper, Loom, foojay, build-logic) were scanned too and have no known advisories.

## 2026-11-16

Re-scan of every resolvable configuration in all 16 projects (313 coordinates, including Loom's and
ModDevGradle's own configurations). Everything from 2026-11-12 is unchanged except Jackson, which is now
3.3.0 through the `jackson-bom` floor. MCP SDK 2.2.0 (released 2026-11-13) pulls Jackson 3.1.8 by itself, so
once msc-core moves to it the floor only keeps us ahead of the SDK and is no longer a security fix.
`msc-bot`'s Netty floor (4.2.20.Final) is clean. New since the last scan:

| Library (version) | Where it comes from | Advisories | Decision |
|---|---|---|---|
| `org.jline:jline-reader` 3.20.0 | NeoForge 26.3 server, msc-agent-neoforge, **provided** | GHSA-5q95-hrpc-m3w3 (moderate, ReDoS via `HISTORY_IGNORE`) | Not reachable: the server's console library; the agent never touches the console or sets that variable, and does not ship JLine. |
| `org.apache.commons:commons-compress` 1.18 | NeoForm's external tools (`neoFormRuntimeExternalTools`, msc-agent-neoforge and msc-agent-forge), **build tool** | GHSA-53x6-4x5p-rrvv, GHSA-7hfm-57qf-j43q, GHSA-crv7-7245-f45f, GHSA-mc84-pj99-q6hh, GHSA-xqfj-vm6h-2x34 (high, DoS on crafted archives); GHSA-4g9r-vxhx-9pgx (moderate) | Not reachable by users: runs only at build time on Minecraft's own jars, which Gradle checks by hash. Never shipped. Fixed when NeoForm updates its tools. |
| `org.apache.commons:commons-lang3` 3.9 | Same NeoForm tools, **build tool** | GHSA-j288-q9x7-2f5v (moderate) | Same as above. |
