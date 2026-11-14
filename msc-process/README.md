# msc-process

Server process control that msc-core, msc-bench and msc-bot share (msc-bot uses the HTTP stall rule): the process tree kill
([ADR 0031](../msc-docs/docs/adr/0031-process-tree-kill.md)), the RCON client
([ADR 0032](../msc-docs/docs/adr/0032-one-rcon-client.md)), the `server.properties` reader and writer
([ADR 0033](../msc-docs/docs/adr/0033-one-server-properties-file.md)) with the server address read from it, the HTTP
stall rule, the atomic file writer
([ADR 0034](../msc-docs/docs/adr/0034-one-atomic-file-writer.md)), and the UTF-8 console flags of a server's Java. It depends on nothing, so the bench can use it without
depending on msc-core (ADR 0004).

`ProcessTree.destroy(handle)` kills a command and every process it can prove descends from it, including a child
forked during the kill. It never kills a process whose only link to the command is a recorded parent pid.

`RconClient.connect(host, port, password, timeout)` logs in to a Minecraft server's RCON, and `command(text)` returns
the whole reply. Bodies are UTF-8; a command or password over `RconClient.MAX_BODY` (1446) bytes is refused before it
is sent, since the vanilla server reads one request with a single read of 1460 bytes and drops a longer one. A
refused login, or a password over the limit, throws `RconRefusedException`, so a caller can tell it from a server
that is not up yet.

`ServerPropertiesFile.read(file)` reads a server's `server.properties` as the 26.3 server does (strict UTF-8, then
ISO-8859-1, `Properties.load` rules), and `ServerPropertiesFile.set(file, values)` sets keys, keeping every other line,
comment and their order, escaped so the server reads back exactly what was set. No other code in the repository reads
or writes that file (msc-core's `repoChecks` checks that no other code names it).

`ServerAddress.game(properties)` and `ServerAddress.rcon(properties)` turn the keys of a read `server.properties` into
the address to dial for the game port and the RCON port: `server-ip` with loopback in place of an empty or wildcard
value, and the port key as the server reads it (its default when it is not a number, no address when it is outside 1 to
65535). It is the one place that does so, so a test player and an RCON client reach the same server.

`HttpStall.send(http, request, handler, limit, retry)` sends an HTTP request and gives it up once no byte has come for
the stall limit (`HttpStall.LIMIT`, 30 s, for downloads), with a one-line error that names the host and what to retry.
`HttpRequest.timeout` cannot do this: the JDK applies it only until the response headers arrive. Every HTTP read in
the product goes through it (#454).

`AtomicFile.write(target, writer)` and `AtomicFile.replace(target, writer)` write a temp file beside the target and
move it into place, so a failed write never leaves half a file; `AtomicFile.copy(source, target)` does the same for a
copy. The replaced file's permissions are kept, and a new file gets the folder's defaults, never `0600`. It is the
repository's one temp-then-move writer: use it rather than writing another.

**Phase:** Post-MVP  
**Status:** Done and tested. The Windows branch of the tree kill has been checked only against the fake table so far.

| Class | Use |
| --- | --- |
| `ProcessTree` | The public entry point of the tree kill |
| `TreeKill` | The decisions: which processes are proven descendants, and in what order to stop and kill them |
| `ProcessTable` | The view of the system's processes that `TreeKill` decides on |
| `LiveProcessTable` | The real table, through `ProcessHandle` and `/bin/sh kill` |
| `rcon.RconClient` | The RCON client: login, commands, replies split over several packets |
| `rcon.RconRefusedException` | A login the server refused, or a password too long to send: retrying will not help |
| `ServerPropertiesFile` | The one reader and writer of `server.properties` |
| `ServerAddress` | The one place that turns `server-ip` and a port key into the address to dial, game or RCON port |
| `HttpStall` | The one stall rule for HTTP reads: no byte for the limit and the request is given up |
| `ServerJava` | The one place that adds the UTF-8 console flags (`stdout`, `stderr`, `stdin`, `file.encoding`) to a server's Java command; any other program is left alone |
| `AtomicFile` | The one atomic file writer: write, replace and copy through a temp file, permissions of the replaced file kept |
| `rcon.FakeRconServer` (test fixtures) | A vanilla-like RCON server for the RCON tests of this module, msc-core and msc-bench, with faults for broken replies |
| `StallingHttpServer` (test fixtures) | An HTTP server that sends its headers and some bytes, then stalls or trickles, for the stall tests |
