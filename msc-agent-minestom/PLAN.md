# msc-agent-minestom plan

Phase: Post-MVP. Steps are in order; each ends with passing tests.

- [x] Minestom hook skeleton (`MscAgent.start()`, a library call; ADR 0010).
- [x] Handshake and command execution.
- [x] Block edits and region reads.
- [x] Minestom template in `msc-templates` (`minestom-server`).
- [x] Minestom API index (msc-core: index the Minestom jar for `api_search`).
- [x] msc-core runs Minestom projects (scaffold, build, `java -jar`, no Paper download or EULA check).
- [ ] Faster large edits (chunk batches) if 1M-block edits at ~12 s become a bottleneck.
