# ADR 0032: One RCON client, in msc-process

Status: accepted, 2026-11-14. Decided by Claude under ADR 0029. Closes #380. Widens ADR 0031's module.

## Context

msc-core and msc-bench each had an RCON client, and the two had drifted (#380).

- msc-core's `Rcon` sent one command to stop a server an earlier core left running (`ServerManager.stopLeftover`,
  ADR 0013). It encoded every body as US-ASCII, so a non-ASCII `rcon.password` went out with `?` in its place, the
  login was refused, and the leftover server was killed instead of being asked to stop and save. It capped a reply
  packet at 4120 bytes.
- msc-bench's `RconClient` runs setup commands and checks. It speaks UTF-8, reads replies split over several packets,
  and was just reworked (#379, #385) so it never has two packets in flight. A fix to one client did not reach the
  other.

ADR 0004 says the bench must not depend on core's code. ADR 0031 solved the same problem for the process tree kill
with a leaf module, `msc-process`, that both depend on.

Neither client checked the size of a request. The vanilla server reads each request with a single read of up to
1460 bytes and closes the connection, without running anything, when that read does not hold exactly the one packet
its length field announces. A packet is 14 bytes of framing plus its body, so a longer command was silently dropped
and the client saw only a closed connection.

## Decision

1. **One client, in `msc-process`.** The bench's `RconClient` moves to `io.github.imnotstable.msc.process.rcon`.
   msc-core's `Rcon` is deleted. `msc-process` is now the server process control both sides share: kill a process
   tree, or ask the server over RCON to do something (stop and save). A second leaf module would add a node and two
   edges to the module graph for one class. The module keeps its name: a rename would touch the settings file, both
   build files, every import and ADR 0031 for no change in what the module is for.
2. **msc-core keeps only what is its own.** `RconTarget` (in `core.server`) reads host, port and password from
   `server.properties` and asks to stop through `RconClient`. Treating a connection closed by a stopping server as
   "asked" stays a choice of that caller. It reads them as the 26.3 server does (#391, #394, #396; the file through
   `ServerPropertiesFile`, ADR 0033): `enable-rcon` in any case, a password that is not empty, `rcon.port` 25575 when
   it is not a number and no RCON at all outside 1 to 65535, and the host `server-ip` (loopback when it is empty or
   a wildcard), the address the server binds RCON to. Host and port come from msc-process's `ServerAddress`, the
   one reading of `server-ip` and a port key, which player_test also dials the game port by (#448). Asking never
   throws, and `ServerManager.stopLeftover` keeps the pid file until the leftover is gone, so a failed ask always
   falls back to the kill.
3. **The rules of the client.**
   - Every body, the password included, is UTF-8.
   - A command or password over `RconClient.MAX_BODY` bytes (1460 minus 14 = 1446 bytes of UTF-8) is refused before
     anything is sent, with an `IOException` that names the limit. The connection stays usable.
   - A refused login, and a password over the limit, throw `RconRefusedException` (an `IOException`), so a caller
     tells "the server answered but will not let us in" from "nothing answers yet" (#393). The bench fails a start
     at once on it instead of waiting out its start timeout.
   - Timeouts are bounded: connecting and every read wait at most the caller's timeout, and a timeout that is not
     positive (a socket would wait forever on 0) is refused with `IllegalArgumentException`.
   - Replies are read as #379 settled: no end marker for a reply under one full packet, the end marker never sent
     behind the command, and a reconnect after an unanswered end marker.
4. **One fake server for every RCON test.** `FakeRconServer`, which reads requests like the vanilla connection loop,
   lives in `msc-process`'s Gradle test fixtures (`java-test-fixtures`, part of Gradle, no new library). msc-core and
   msc-bench use it with `testImplementation(testFixtures(project(":msc-process")))`. Its own tests pin the 1446-byte
   limit the client enforces.

## Consequences

- A fix to the RCON client reaches both the core and the bench.
- The core can now stop and save a leftover server whose RCON password is not ASCII, written raw in UTF-8 as the
  server stores it or `\u`-escaped (#391 completed this; #380 alone read the file as ISO-8859-1).
- A bench task whose command is too long fails its check with a message naming the limit instead of a closed
  connection.
- `msc-process` has two packages, `process` (the tree kill) and `process.rcon`. It still depends on nothing.
