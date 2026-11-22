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

## Addendum 2026-11-22: Renovate leaves Paper alone

Renovate (ADR 0064) proposed Paper 26.3.build.257 with MockBukkit 4.123.0; `:msc-agent-paper:test` failed 28 of 33
tests on build 257 ("Could not initialize class org.bukkit.Registry") and passed on build 252. `renovate.json` now
disables `io.papermc.paper:paper-api`, so Paper is raised by hand under rule 2; MockBukkit still updates on its own
and must pass on the pinned build.

## Addendum 2026-11-22: Paper follows its newest build again; only MockBukkit tests stay on 252

Root cause, measured on Paper 26.3 build 257 against MockBukkit 4.123.0 (the newest release; its `main` still builds
against Paper build 200, and the gap is open upstream as MockBukkit#1684). Build 253 changed paper-api in two ways
that MockBukkit does not handle:

1. Two new registry keys, `RegistryKey.TIMELINE` (`minecraft:timeline`) and `RegistryKey.WORLD_CLOCK`
   (`minecraft:world_clock`). MockBukkit's `RegistryAccessMock` reads every `RegistryKey` constant by reflection and
   looks each up in its bundled `registries/registry_key_class_relation.json`, which lacks both, so its static
   initializer throws and takes `org.bukkit.Registry` down with it ("Could not initialize class org.bukkit.Registry",
   so `MockBukkit.mock()` fails in every test).
2. `TimeSkipEvent` and `ClockTimeSkipEvent` constructors now take a `WorldClock`. MockBukkit's `WorldMock.setFullTime`
   (and so `World#setTime`) calls the removed constructor and throws `NoSuchMethodError`. A link check of every
   paper-api reference in the MockBukkit jar finds this as the only one that resolves on build 252 and not on 257.

Patching the registry key map onto the test classpath fixes the first (`:msc-agent-paper:test` passed with it), but
not the second: the `paper-game` template's tests call `World#setTime` and failed. Fixing that would mean rewriting
MockBukkit bytecode or shadowing a Paper class.

Decision, replacing rules 1 and 2 above:

1. `paper` is an exact build that Renovate raises (`26.3.build.257-stable` now). The agent, msc-stdlib-paper and the
   Paper templates (`paperDevBundle`, so NMS too) compile against it, and the dev server runs the newest build.
2. A second catalog version, `mockbukkit-paper` (`26.3.build.252-stable`, the newest build MockBukkit runs), is the
   paper-api that MockBukkit tests compile and run against (`libs.paper.api.mockbukkit` in msc-agent-paper and
   msc-stdlib-paper; the `paper-api` library of the `paper-plugin` and `paper-game` templates, which only their tests
   use). `renovate.json` holds only that value (`matchCurrentValue`); it moves by hand together with MockBukkit, after
   `:msc-agent-paper:test` and `:msc-templates:verifyTemplates` pass with `--refresh-dependencies`.
3. Code that calls paper-api added after `mockbukkit-paper` compiles but cannot be tested under MockBukkit; that was
   already so, since MockBukkit only implements the API of its own build.

Rejected: a newer MockBukkit (none), and an in-repo shim (the registry patch alone leaves `World#setTime` broken).
