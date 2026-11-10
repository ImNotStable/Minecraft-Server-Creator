# Minecraft Server Creator

A toolset that helps an AI develop custom Minecraft servers on any platform. This is one git repo (a monorepo); each folder is a module. It stays private and local for now and moves to a private GitHub repo later. The MVP targets Paper on the latest stable Minecraft release.

| Module | Phase |
| --- | --- |
| [msc-docs](msc-docs/README.md) | MVP |
| [msc-protocol](msc-protocol/README.md) | MVP |
| [msc-core](msc-core/README.md) | MVP |
| [msc-agent-paper](msc-agent-paper/README.md) | MVP |
| [msc-templates](msc-templates/README.md) | MVP |
| [msc-bench](msc-bench/README.md) | MVP |
| [msc-agent-fabric](msc-agent-fabric/README.md) | Post-MVP |
| [msc-agent-minestom](msc-agent-minestom/README.md) | Post-MVP |
| [msc-renderer](msc-renderer/README.md) | Post-MVP |
| [msc-textures](msc-textures/README.md) | Post-MVP |

## Build

Java 25 and Gradle 9.9 (wrapper). Gradle downloads Java 25 if it is not installed.

```
./gradlew build
```

Working conventions for humans and AI are in [CLAUDE.md](CLAUDE.md).

The full plan is in [msc-docs/docs/plan.md](msc-docs/docs/plan.md).
