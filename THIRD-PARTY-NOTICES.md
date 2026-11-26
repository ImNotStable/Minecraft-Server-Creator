# Third-party notices

Minecraft Server Creator (MSC) is licensed under the [Apache License 2.0](LICENSE). This file lists the third-party
software it uses, where each one ends up, and its license. Decision and audit: [ADR 0115](msc-docs/docs/adr/0115-license.md).

"Shipped" means the library is inside something MSC hands to users: the msc-core distribution (`installMsc`,
release archives) or an agent jar. "Scaffolded" means projects that MSC scaffolds bundle it in their own jars.
"Compile-only" means MSC's jars only compile against it and the server platform supplies it at run time.
"Build and tests" means it never leaves this repository's build.

Most of these jars carry no license file of their own, so the license texts are here: the Apache License 2.0 is
[LICENSE](LICENSE), the MIT License is below, and the GNU texts are in [licenses/](licenses/). A release archive
carries this file, `LICENSE`, `NOTICE` and `licenses/` at its root.

## Minecraft and Mojang

MSC ships no Mojang code or assets. The Fabric, NeoForge and Forge agents compile against Minecraft, and the server
jars MSC runs are downloaded by `server_setup` from their official sources onto the user's machine. Those servers run
under the [Minecraft EULA](https://aka.ms/MinecraftEULA), which MSC never accepts by default ([TERMS.md](TERMS.md)).
msc-core's `checkAgentJars` task fails the build if an agent jar contains a `net/minecraft/` or `com/mojang/` class.

## Shipped in the msc-core distribution

| Library | Version | License | Copyright |
|---|---|---|---|
| `io.modelcontextprotocol.sdk:mcp` (with `mcp-core`, `mcp-json-jackson3`) | 2.2.0 | MIT | The original author or authors |
| `tools.jackson.core:jackson-core`, `jackson-databind`; `tools.jackson.dataformat:jackson-dataformat-yaml` (pinned by `tools.jackson:jackson-bom`) | 3.3.0 | Apache-2.0 | FasterXML, LLC |
| `com.fasterxml.jackson.core:jackson-annotations` | 2.x | Apache-2.0 | FasterXML, LLC |
| `io.projectreactor:reactor-core` | 3.x | Apache-2.0 | VMware, Inc. or its affiliates |
| `org.reactivestreams:reactive-streams` | 1.0.4 | MIT-0 | Reactive Streams contributors |
| `com.networknt:json-schema-validator` | 3.x | Apache-2.0 | Network New Technologies Inc. |
| `com.ethlo.time:itu` | 1.x | Apache-2.0 | Morten Haraldsen (ethlo) |
| `org.snakeyaml:snakeyaml-engine` | 3.x | Apache-2.0 | SnakeYAML contributors |
| `org.slf4j:slf4j-api` | 2.x | MIT | QOS.ch Sarl |
| `com.google.code.gson:gson` | 2.15.0 | Apache-2.0 | Google Inc. |
| `org.geysermc.mcprotocollib:protocol` (26.3 on the class path, 26.2 in `bot/26.2/`), `text-serializer-nbt` | 26.3-4, 26.2 | MIT | Steveice10; GeyserMC |
| `io.netty:*` (pinned by `io.netty:netty-bom`) | 4.2.21.Final | Apache-2.0 | The Netty Project (its native libraries include code under the licenses in [Netty's NOTICE](https://github.com/netty/netty/blob/4.2/NOTICE.txt)) |
| `net.kyori:adventure-*`, `net.kyori:option` (through MCProtocolLib) | 5.x, 1.x | MIT | KyoriPowered |
| `org.cloudburstmc:nbt` | 3.x | Apache-2.0 | CloudburstMC |
| `org.cloudburstmc.math:api`, `immutable` | 2.0 | MIT | CloudburstMC |
| `com.nukkitx.fastutil:*` | 8.5.3 | Apache-2.0 | Sebastiano Vigna |
| `net.lenni0451.commons:gson`, `httpclient` | 1.x | MIT | Lenni0451 |
| `net.raphimc:MinecraftAuth` | 5.x | **LGPL-3.0** | RaphiMC |
| `org.jetbrains:annotations`, `org.jspecify:jspecify`, `com.google.errorprone:error_prone_annotations`, `com.google.auto.service:auto-service-annotations` | | Apache-2.0 | JetBrains s.r.o.; The JSpecify Authors; Google LLC |
| `org.checkerframework:checker-qual` | 3.x | MIT | The Checker Framework developers |

MinecraftAuth comes in through MCProtocolLib. MSC does not change it and ships it as its own unmodified jar in the
distribution's `lib/` folder, loaded from the class path, so you can replace it with another build of the same
library. Its source is at <https://github.com/RaphiMC/MinecraftAuth>. Its license is in
[licenses/LGPL-3.0.txt](licenses/LGPL-3.0.txt), which builds on [licenses/GPL-3.0.txt](licenses/GPL-3.0.txt).

## Shipped in the agent jars

| Agent jar | Third-party code inside |
|---|---|
| `msc-agent-paper`, `msc-agent-neoforge`, `msc-agent-forge`, `msc-agent-minestom` | None: only MSC's own `msc-agent-common` and `msc-protocol` classes |
| `msc-agent-fabric` | `net.fabricmc.fabric-api:fabric-api` modules `fabric-api-base`, `fabric-lifecycle-events-v1`, `fabric-networking-api-v1`, nested as unmodified jars (Apache-2.0, FabricMC) |

## Scaffolded into your projects

Code MSC scaffolds and the code the AI writes in your project are yours. MSC's standard library and these
libraries end up in the jars your project builds, and keep their own licenses there:

| Library | License | When |
|---|---|---|
| msc-stdlib and its platform modules | Apache-2.0 (this repository) | Every scaffolded project |
| `com.zaxxer:HikariCP` | Apache-2.0 (Brett Wooldridge) | Every project that uses msc-stdlib's SQL storage |
| `net.kyori:adventure-text-minimessage`, `net.kyori:adventure-text-serializer-plain`, `net.kyori:adventure-text-serializer-legacy`, `net.kyori:adventure-text-serializer-gson` | MIT (KyoriPowered) | Minestom and BungeeCord projects (Paper and Velocity ship Adventure) |
| `com.h2database:h2` | MPL-2.0 or EPL-1.0 | Projects that pick the H2 database |
| `org.mongodb:mongodb-driver-sync` | Apache-2.0 | Projects that use Mongo (msc-stdlib only compiles against it) |
| `redis.clients:jedis` | MIT | Projects that use Redis (msc-stdlib only compiles against it) |
| Gradle wrapper (`gradle-wrapper.jar`) | Apache-2.0 (Gradle, Inc.) | Every scaffolded project |

## Compile-only: supplied by the server platform

| Library | License |
|---|---|
| `io.papermc.paper:paper-api` | GPL-3.0 (the Bukkit API it includes) and MIT (Paper's additions) |
| `com.mojang:minecraft` | Mojang's proprietary terms ([Minecraft EULA](https://aka.ms/MinecraftEULA)); never shipped |
| `net.fabricmc:fabric-loader` | Apache-2.0 |
| `net.minecraftforge:forge`, `net.minecraftforge:fmlcore`, `net.minecraftforge:javafmllanguage`, `net.minecraftforge:eventbus` | LGPL-2.1 |
| NeoForge (through ModDevGradle) | LGPL-2.1 |
| `net.minestom:minestom` | Apache-2.0 |
| `com.velocitypowered:velocity-api` | MIT |
| `io.github.waterfallmc:waterfall-api` | BSD-3-Clause (BungeeCord) |

MSC's code only calls these APIs. Apache-2.0 code may be combined with GPL-3.0 and LGPL code, and nothing from them
is copied into MSC's jars.

## Build and tests only

| Library or plugin | License |
|---|---|
| `org.junit:junit-bom`, `org.junit.jupiter:junit-jupiter`, `org.junit.platform:junit-platform-launcher` | EPL-2.0 |
| `org.assertj:assertj-core` | Apache-2.0 |
| `org.mockbukkit.mockbukkit:mockbukkit-v26.3` | MIT |
| `net.fabricmc:fabric-loader-junit` | Apache-2.0 |
| `net.minestom:testing` | Apache-2.0 |
| `org.xerial:sqlite-jdbc` | Apache-2.0 |
| `org.postgresql:postgresql` | BSD-2-Clause |
| `org.mariadb.jdbc:mariadb-java-client` | LGPL-2.1 |
| Gradle plugins: Shadow, run-paper, foojay-resolver | Apache-2.0 |
| Gradle plugins: Fabric Loom | MIT |
| Gradle plugins: NeoForge ModDevGradle | LGPL-2.1 |
| Written into templates only: resource-factory, paperweight-userdev, ForgeGradle | MIT; MIT; LGPL-2.1 |

## The MIT License

The libraries above marked MIT are each under this license, with the copyright line of their own project:

```
Copyright (c) <year> <copyright holders>

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit
persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

MIT-0 (reactive-streams) is the same license without the attribution condition.
