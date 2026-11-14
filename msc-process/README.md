# msc-process

Server process control that msc-core and msc-bench share: the process tree kill
([ADR 0031](../msc-docs/docs/adr/0031-process-tree-kill.md)) and the RCON client
([ADR 0032](../msc-docs/docs/adr/0032-one-rcon-client.md)). It depends on nothing, so the bench can use it without
depending on msc-core (ADR 0004).

`ProcessTree.destroy(handle)` kills a command and every process it can prove descends from it, including a child
forked during the kill. It never kills a process whose only link to the command is a recorded parent pid.

`RconClient.connect(host, port, password, timeout)` logs in to a Minecraft server's RCON, and `command(text)` returns
the whole reply. Bodies are UTF-8; a command or password over `RconClient.MAX_BODY` (1446) bytes is refused before it
is sent, since the vanilla server reads one request with a single read of 1460 bytes and drops a longer one.

**Phase:** Post-MVP  
**Status:** Done and tested. The Windows branch of the tree kill has been checked only against the fake table so far.

| Class | Use |
| --- | --- |
| `ProcessTree` | The public entry point of the tree kill |
| `TreeKill` | The decisions: which processes are proven descendants, and in what order to stop and kill them |
| `ProcessTable` | The view of the system's processes that `TreeKill` decides on |
| `LiveProcessTable` | The real table, through `ProcessHandle` and `/bin/sh kill` |
| `rcon.RconClient` | The RCON client: login, commands, replies split over several packets |
| `rcon.FakeRconServer` (test fixtures) | A vanilla-like RCON server for the RCON tests of this module, msc-core and msc-bench |
