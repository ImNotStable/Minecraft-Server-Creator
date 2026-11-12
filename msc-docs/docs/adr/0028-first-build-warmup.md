# ADR 0028: Warm the first build of NeoForge and Forge projects

Status: accepted, 2026-11-12. Decided by Claude under Jeremiah's hand-over of build decisions, after the PC check of
ADR 0027's platforms.

## Context

On the PC, the first `dev_cycle` of a `forge-mod` project took 502 s: ForgeGradle 7 downloads, decompiles and recompiles
Minecraft once per version. A `neoforge-mod` cycle took 79 s, 59 s of it the test run. In the cloud, once warm, the same
NeoForge test run takes 8 s (configuration cache saves under a second); the slow part is everything a first build does
once: ModDevGradle's Minecraft setup, and for the tests, preparing the test run and downloading Minecraft's assets.

Forge and NeoForge server logs put the logger after the level (`[12:34:56] [main/WARN] [os.ha.pl.Foo/]: msg`), so
`LogCondenser`'s known-noise patterns (the system report's OSHI warnings, `Failed to get system info for ...`) did not
match, and ModLauncher appends a `Transformer Audit:` block to every stack trace.

## Decisions

1. **`project_scaffold` starts the first build in the background** for templates whose `template.properties` says
   `warmup=true` (`neoforge-mod`, `forge-mod`): the dev jar task and `check`, the same work a first `dev_cycle` does,
   with a 20-minute limit. The result says so. The AI writes code meanwhile.
2. **`dev_cycle` waits for its project's warmup** before building, so two Gradle builds never race for the loader's
   caches, and shows the wait as `waited for first build` in its timings. A failed warmup is ignored: it only means a
   cold cache, and `dev_cycle`'s own build reports the real error. A warmup lives in the core's memory; after a core
   restart there is nothing to wait for.
3. **Tests stay in every cycle.** They already run beside the server restart, and once warm they are short; skipping
   them would hide failures from the AI.
4. **Log lines with a logger tag** are parsed without it (fewer tokens), except OSHI's (`oshi.` or Forge's shortened
   `os.`), which keeps its tag so the existing noise pattern drops it. The `Transformer Audit` block is cut from traces.

## Consequences

- The ForgeGradle and ModDevGradle setup cost moves from the first `dev_cycle` to the time right after scaffolding.
  If the AI calls `dev_cycle` at once, it still waits, but no longer than before.
- Fabric's Loom setup could use the same flag; left off until a Fabric first build is measured as slow.
