# ADR 0034: One atomic file writer, in msc-process

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Part of #407. Widens ADR 0031's module again.

## Context

`ServerPropertiesFile.set` (ADR 0033) wrote `server.properties` in place with `Files.writeString`: a core killed
mid-write, or a full disk, left a truncated file, and the server then starts with defaults (no RCON, another game
mode) or not at all. msc-core already had a writer that does not have this problem, `core.files.AtomicFile`: it
writes a temp file beside the target and moves it into place, and it gives the result the permissions of the file
it replaces, or the default ones for a new file, never the `0600` of `Files.createTempFile` (#399).
`ServerPropertiesFile` lives in `msc-process`, which depends on nothing, and msc-bench uses it too, so it could not
call msc-core's class. A second temp-then-move writer in msc-process would be a second place for #399 to come back.

## Decision

1. **`AtomicFile` moves to `msc-process`** (`io.github.imnotstable.msc.process.AtomicFile`), unchanged, with its
   tests. msc-core already depends on msc-process (ADR 0031), and its callers (`Downloads`, `OutputFile`,
   `ResourcePack`, `WorldTools`) import it from there. It is the one temp-then-move writer in the repository.
2. **`ServerPropertiesFile.set` writes through it**, so `server.properties` is replaced whole or not at all and keeps
   its mode, group or ACL. A symbolic link at its place is followed, as the plain write did.
3. **A forwarding `core.files.AtomicFile` stays for one change only**: `DevCycle`, which another change was editing at
   the same time, imports `sameFile` from it. It is deleted once that import names msc-process.

## Consequences

- A failed or interrupted `server_setup`, `pack_build` or bench run never leaves a half-written `server.properties`.
- msc-bench can write any other file whole through the same class.
- `msc-process` now has four pieces (tree kill, RCON client, `server.properties` file, atomic writer); it still
  depends on nothing.
