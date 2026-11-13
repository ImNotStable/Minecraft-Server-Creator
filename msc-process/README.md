# msc-process

The process tree kill that msc-core and msc-bench share ([ADR 0031](../msc-docs/docs/adr/0031-process-tree-kill.md)).
`ProcessTree.destroy(handle)` kills a command and every process it can prove descends from it, including a child
forked during the kill. It never kills a process whose only link to the command is a recorded parent pid.

**Phase:** Post-MVP  
**Status:** Done and tested. The Windows branch has been checked only against the fake table so far.

| Class | Use |
| --- | --- |
| `ProcessTree` | The public entry point |
| `TreeKill` | The decisions: which processes are proven descendants, and in what order to stop and kill them |
| `ProcessTable` | The view of the system's processes that `TreeKill` decides on |
| `LiveProcessTable` | The real table, through `ProcessHandle` and `/bin/sh kill` |
