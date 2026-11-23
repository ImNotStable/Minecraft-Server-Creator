# ADR 0090: server_profile, a flight-recorder profile with what to tune

Status: accepted, 2026-11-23. Decided by Claude, item 4 of Jeremiah's gap list: "performance profiling and config
tuning".

## Context

Checked against main first: the agent reports TPS (`server.info`), and `dev_cycle` reports its own phase times, but
nothing showed where a slow server spends its time. Options looked at:

- **spark** (bundled with Paper) profiles well, but its results go to an upload site or a protobuf file in its own
  format, it is only on Paper, Fabric and Forge as installed plugins or mods, and the AI would read it through text
  commands.
- **The JDK's Java Flight Recorder** is in every JVM MSC starts (every platform, the proxies included), is started from
  outside with `jcmd`, and its files are read with `jdk.jfr.consumer`, part of the JDK: no library, no plugin.

## Decision

1. **A new tool, `server_profile seconds=30`** (5 to 120): the server's own run time had no tool, and none of the
   existing ones can carry it. The tool-list guard rises by 400 characters for it.
2. **Recording**: `jcmd <pid> JFR.start settings=profile`, then `JFR.dump` and `JFR.stop` after the asked time, with
   the `jcmd` next to the server's own `java` (else the core's); without one the call says profiling needs a JDK. The
   file stays at `.msc/profile/server.jfr` for JDK Mission Control (one over 200 MB is deleted).
3. **Reading** (`Profiler`): the main server thread's samples when it has at least a quarter of all, else all threads.
   Each sample has one owner: the first frame outside JDK, server and loader packages (and the msc agent) is
   `your code` when it has a source file under the workspace (`CrashSources`, ADR 0089), else the plugin or mod jar
   that holds its class, else its package; a sample with no such frame goes to the server area its frames name first
   (chunk generation, lighting, chunk loading and saving, redstone and hoppers, block entities, entities, networking,
   commands and scheduler), else `server: other`. The report: samples, the main thread's busy share per tick and TPS,
   owners over 1%, the 6 hottest methods by own time (a JDK frame counts for its caller; project methods with file,
   line and code), GC pauses, heap after GC, the top allocation sites, and `tune:` lines.
4. **Tuning advice is tied to the measurement**: each server area over 15% gets the setting that lowers it
   (`view-distance`, `simulation-distance`, `entity-activation-range`, hopper ticks, pregenerating, forceload), with
   the current values from `server.properties`; your code over 15% points at its hottest method; GC over 5% of the time
   or a heap over 80% full after GC says to allocate less or give more heap. Nothing is changed by the tool: the AI
   edits the file it names.

Verified on omen: a Paper plugin from the workspace that spins 8 ms a tick came out as `your code 95%`, its loop
lines linked, the main thread busy about 16% (8 of 50 ms); flying through new terrain came out as chunk generation 71%
on worker threads with the view-distance advice and the generator's hottest methods.

## Consequences

- The AI can tell lag from its own code, a plugin, or the server's own work, and what to change, in one call.
- A profile of an idle server is empty, and says so: the AI loads the server first (`player_test`, a game).
- The area words follow Mojang's class names; a renamed package falls into `server: other` until the table follows.
- Not done: changing settings automatically, JVM flags (the dev server keeps `-Xmx2G`), and a tick-by-tick MSPT
  history (TPS comes from the agent; the busy share is per profile).
