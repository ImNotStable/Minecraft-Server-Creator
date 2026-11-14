# ADR 0033: One server.properties reader and writer, in msc-process

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Closes #391 and #392. Widens ADR 0032's module.

## Context

A dev server's `server.properties` was read and written by five hand-rolled pieces that disagreed (#391, #392):

| Piece | Read as | Wrote values |
| --- | --- | --- |
| msc-core `ServerProperties.read`/`set` (game mode) | UTF-8 lines split at `=`, no escapes | raw |
| msc-core `PackProperties` (resource pack lines) | `Properties.load` over UTF-8 | escaped like `Properties.store` |
| msc-core `RconTarget.of` (stop a leftover server) | `Properties.load(InputStream)`, ISO-8859-1 | - |
| msc-core `PlayerTools.address` (test player) | `Properties.load` over UTF-8 | - |
| msc-bench `Workspaces.setProperties` (RCON settings) | UTF-8 lines split at `=` | raw |

What the server does, checked with `javap` on the 26.3 server jar (`net.minecraft.server.dedicated.Settings`):
`loadFromFile` reads the file with `Properties.load` over a UTF-8 decoder that reports malformed input, and on a
`CharacterCodingException` reads it again as ISO-8859-1. `store` writes it with `Properties.store` over
`Files.newBufferedWriter(path, UTF_8)`, which writes non-ASCII text raw, not as `\u` escapes, and the server stores
the file on every start.

So a non-ASCII `rcon.password` was raw UTF-8 on disk after the first start, `RconTarget` read it as ISO-8859-1, the
login was refused and the leftover server was killed instead of saved (#391). And the bench wrote its configured
`rcon.password` raw, so a backslash or leading space made the server's password differ from the bench's, and every
RCON login failed (#392).

## Decision

1. **One class, `io.github.imnotstable.msc.process.ServerPropertiesFile`, in `msc-process`.** It is the only code
   that reads or writes `server.properties`. msc-core and msc-bench both already depend on `msc-process` (ADR 0031,
   ADR 0032) and the bench must not depend on msc-core (ADR 0004); a new leaf module for one class would add a node
   and two edges for nothing. `msc-process` is the server-side code both share: kill its process tree, talk to it
   over RCON, read and write its settings file.
2. **Read as the server reads.** `read(file)` decodes the bytes as strict UTF-8, falls back to ISO-8859-1, and
   parses with `Properties.load(Reader)`, so `\uXXXX`, backslash escapes, `:` and whitespace separators and
   continuation lines mean what they mean to the server. A malformed `\u` escape is an `IOException`.
3. **Write so the server reads back exactly what was set.** `set(file, values)` edits line by line: the first line
   of each key is replaced, later lines of the same key are dropped (the server takes the last), missing keys are
   appended, and comments, other lines (continuations included) and their order stay. Keys and values are escaped
   as `Properties.store(OutputStream)` escapes them: backslash, a leading space (every space in a key), `#`, `!`,
   `=`, `:`, tab, newline, carriage return, form feed, and every character outside printable ASCII as `\uXXXX`.
   So a written line means the same whether the file is read as UTF-8 or ISO-8859-1. The file is UTF-8 with `\n`
   line ends. A null or empty key, or a null value, is refused before the file is touched.
4. **Callers.** msc-core `ServerProperties` keeps only the play modes and the start report's mode line;
   `ServerSetup`, `PackProperties`, `RconTarget` and `PlayerTools` use `ServerPropertiesFile`, and so does the
   bench's `Workspaces`. `PackProperties` still writes nothing when the file does not exist, and the byte snapshot
   and restore of the file around a pack build (#388) are untouched: they copy bytes and do not parse.
5. **A repository check keeps it so.** msc-core's `repoChecks` runs `ServerPropertiesAccessTest` over every
   module's production sources: it fails on a new `java.util.Properties` user (one for another file is listed there),
   on code that splits `key=value` lines it read as text, and on a text read or write whose target names
   `server.properties`.

## Consequences

- A leftover server whose RCON password is not ASCII is stopped and saved, however the file was written.
- A bench `server.rconPassword` with a backslash, a leading space or non-ASCII text logs in.
- Lines the core writes into `server.properties` may now carry `\uXXXX` for non-ASCII text where they were raw;
  the server reads both the same.
- `msc-process` has a third piece; it still depends on nothing.
