# msc-agent-fabric plan

Phase: Post-MVP. Steps are in order; each ends with passing tests.

- [x] Fabric mod skeleton (Gradle KTS, Loom, server-only, jar-in-jar).
- [x] Handshake and command execution.
- [x] Block edits with undo, and region reads.
- [x] Unit tests and a smoke test against a real `CoreServer` socket.
- [x] Fabric template in `msc-templates` (`fabric-mod`).
- [x] msc-core starts Fabric servers (Fabric server launcher, agent in `mods/`). Owned by msc-core.
- [ ] Smoke test on a real Fabric 26.3 server started by `msc-core`.

## Next

- API index for Fabric: Minecraft 26.x is unobfuscated, so the core's class-file index can read the server jar and Fabric API jars directly; no mappings needed.
- Persist undo history across restarts.
