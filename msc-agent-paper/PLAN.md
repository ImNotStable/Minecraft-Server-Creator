# msc-agent-paper plan

Phase: MVP. Steps are in order; each ends with passing tests.

- [x] Gradle KTS Paper plugin skeleton with the protocol client.
- [x] Handshake and command execution.
- [x] Batched block edits with undo history.
- [x] Region reads for world views.
- [x] MockBukkit tests and a smoke test against a real `CoreServer` socket.
- [ ] Smoke test on a real Paper 26.3 server started by `msc-core` (needs the core's server launcher).

## Next

- Persist undo history across restarts.
- Capture console log output from commands, not only sender feedback.
- `world.read` through chunk snapshots off the main thread, if reads show up in tick timings.
