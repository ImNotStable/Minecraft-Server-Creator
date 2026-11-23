# ADR 0040: Crash and start-up failure triage as a mode of server_logs

Status: accepted, 2026-11-14, decided by Claude. Extended by ADR 0089 (source links, jar names, proxy causes, 5
findings).

## Context

When the dev server crashes or does not start, the AI had `server_logs mode=errors`: every warning and error since the
start, traces trimmed to the project's frames, repeats collapsed. That is the right view of a noisy start, but not of a
death: the AI had to find the one entry that killed the server among the others, and it never saw the files the server
writes when it dies (`crash-reports/crash-*.txt`, the JVM's `hs_err_pid*.log`). Many deaths also have a known cause the
log states in a platform-specific way (the EULA, a port in use, out of memory, a class compiled for a newer Java, a
missing dependency in Paper's, Fabric's or Forge's wording, a mixin that found no target), each with a one-line fix the
AI otherwise works out from raw text. The tool surface is capped (no new MCP tools; the tool list under 16,000 chars).

## Decision

1. **`server_logs mode=triage`**, not a new tool: at most 3 findings, each `what [source]`, `culprit:`, `cause:` (the
   root exception, the last `Caused by`), up to 3 key frames and `fix:`. The culprit is the first frame outside the
   JDK, server and loader packages `LogCondenser` already folds (the msc agent is named as such), else `server or
   loader code`.
2. **Sources**: the log since the last start (`logs/latest.log` when the core holds no lines, as after a core restart),
   the newest crash report and the newest `hs_err_pid*.log` in the server folder written since this core last launched
   the server (any with `all=true`, or when this core never launched one). The same entries `LogCondenser` hides as
   known-harmless are skipped.
3. **Known causes are a table of patterns** (`CrashTriage.RULES`) on whole log entries, checked in order: EULA, port,
   Java version, out of memory, missing or wrong-version dependencies (Paper and Purpur, Fabric and Quilt, NeoForge and
   Forge), plugin enable and load failures, Fabric entrypoints, mixins, NeoForge and Forge mod construction, the
   watchdog. A known cause whose trace has no plugin or mod frame is reported as one line and its fix; otherwise its
   cause and frames are kept. Without a known cause, the fatal or uncaught exception (`Encountered an unexpected
   exception`, `Exception in thread`, FATAL level) is shown, and with none of those the last logged exception, marked
   `not fatal`.
4. **One finding per thing**: a crash report or a later log entry with the same cause joins the first finding (its
   source added), and the same what and culprit again counts (`(x2)`).
5. **`server_start` and `dev_cycle` put the triage first** when the server exits during or right after startup, above
   the condensed errors they already showed; a ready server's report is unchanged.

## Consequences

- A crash costs one short call, or none: the start report already says what died and the likely fix.
- The patterns follow the platforms' wording. A changed message falls through to the exception finding, which is still
  useful; the fixtures in `msc-core/src/test/resources/crash-logs/` show each format the table expects.
- The fixes are hints, not diagnoses: the frames and `mode=errors` stay available for anything the table gets wrong.
