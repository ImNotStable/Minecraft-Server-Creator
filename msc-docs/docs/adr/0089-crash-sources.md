# ADR 0089: Crash triage that points at the code

Status: accepted, 2026-11-23. Decided by Claude, item 3 of Jeremiah's gap list: "a crash and log triage tool: read
server logs and crash reports (Paper, Fabric, Forge/NeoForge, Minestom, Velocity/Waterfall), group and de-duplicate
errors, identify the culprit plugin, mod or class and a version mismatch or missing dependency, link it to the agent's
own source files and lines, and suggest the likely fix, in a token-cheap summary".

## Context

Checked against main first: `server_logs mode=triage` (ADR 0040) already reads the log, the newest crash report and
JVM crash file, groups repeats, names a culprit frame and a cause, and has known causes with fixes for Paper, Purpur,
Fabric, Quilt, NeoForge, Forge and Minestom; `mode=errors` already de-duplicates every warning and error. Real crashes
on omen showed what it missed:

- Four broken Paper plugins (an NPE in `onEnable`, a `NoSuchMethodError` from a dependency built in another version, a
  missing dependency, `api-version: 99.0`): the `api-version` one was not found at all (Paper's "Could not load plugin
  'X' in folder" wording), and with the three-finding cap a fifth would have been dropped.
- The culprit was a class name and `Boom.java:9`: the AI still had to find the file among the workspace's projects,
  and for a third-party plugin it was not said which jar the class came from.
- A `NoSuchMethodError` got the generic "compiled against another version" fix, not which installed plugin lacks it.
- Velocity ("Can't load plugin needv due to missing dependency luckperms", "Couldn't pass ProxyInitializeEvent to
  boomv") and Waterfall ("LuckPerms (required by NeedW) is unavailable", "Exception encountered when loading plugin:
  BoomW") had no known causes, and their routine banners (Waterfall's end of life, Geyser's channel initializer notes)
  counted as warnings.

## Decision

1. **Known causes for the proxies** in `CrashTriage.RULES`: Velocity's missing dependency, listener failure and plugin
   construction failure, Waterfall's missing dependency and plugin load failure, and Paper's "Could not load plugin"
   wording. `Unsupported API version N` gets its own fix (lower `api-version`, or set the server up with that version).
   The two banners are known-harmless in `LogCondenser`.
2. **At most 5 findings** (was 3): several plugins failing at once is the common case; the rest are still counted.
3. **Every finding is located** (`CrashSources`, after the findings are made, so the rules stay as they were):
   - a frame whose class has a source file under the workspace (every `src/main/java` and `src/main/kotlin` under the
     server folder's parent, at most 6 folders deep and 3,000 folders, skipping build output, `run/`, caches and VCS)
     becomes `at Class.method -> <path>:<line>: <the line's code>`, and "fix the code at" names that path;
   - otherwise the culprit gets the plugin or mod jar in the addon folder that holds its class, with its id and
     version (`in plugin Linky 1.0 (plugins/Linky.jar)`);
   - a `NoSuchMethodError`, `NoSuchFieldError` or `AbstractMethodError` names the installed plugin or mod whose class
     lacks the member, or says the server's own API lacks it; a `NoClassDefFoundError` or `ClassNotFoundException`
     says whether any installed jar has the class (then: add a dependency so its class loader sees it) or none does.
4. **Real logs are the fixtures**: `paper-plugins.txt`, `velocity-plugins.txt` and `waterfall-plugins.txt` in
   `crash-logs/` are the logs of those crashes on omen.

## Consequences

- One `server_logs mode=triage` call (or the start report) names the file and line to edit, with the code on it, or
  the jar to replace and why, in a few lines per finding.
- The source lookup reads the workspace's folders on each triage; the caps keep a server folder set far from its
  project from walking a disk. A project outside the server folder's parent is not linked (the frame stays as before).
- Not done: line links for frames in a crash report's own trace when the project is elsewhere; a mod's `mods/` jar on
  Fabric is named by its `fabric.mod.json` id as for plugins, but Minestom has no addon folder, so its culprits are only
  ever linked to the project's source.
