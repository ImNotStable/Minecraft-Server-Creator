# ADR 0052: Pin Paper to a build MockBukkit runs

Status: accepted, 2026-11-16, decided by Claude. Amends [ADR 0001](0001-build-setup.md) (Paper API version).

## Context

The catalog used `paper = "26.3.build.+"`, Paper's recommended form, so every fresh dependency resolution picked the
newest 26.3 build. Paper 26.3 build 253 added the `minecraft:world_clock` registry, which MockBukkit 4.120.0 (built
against build 200) does not know: `:msc-agent-paper:test` fails 17 of 18 classes with "Null JSON element while
retrieving minecraft:world_clock - MockBukkit / MC version mismatch". Main went red without a commit, only because a
new Paper build was published. Scaffolded Paper projects take the same version from the catalog, so their MockBukkit
tests broke the same way.

## Decision

1. `paper` in `gradle/libs.versions.toml` is an exact build, `26.3.build.252-stable`, the newest build MockBukkit
   4.120.0 runs.
2. Paper and MockBukkit move together: raise `paper` only with a MockBukkit release that runs it, and run
   `:msc-agent-paper:test` and `:msc-templates:verifyTemplates` with `--refresh-dependencies` before merging.
3. `msc_upstream` keeps checking Paper by its Minecraft version (`26.3`), whatever build is pinned.

## Consequences

Builds no longer change under us when Paper publishes. Paper fixes after build 252 wait for a MockBukkit release.
