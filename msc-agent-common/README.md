# msc-agent-common

Platform-neutral code shared by the server agents (`msc-agent-paper`, `msc-agent-fabric`,
`msc-agent-neoforge`, `msc-agent-forge` and `msc-agent-minestom`; the three mod-loader agents share
the `msc-agent-vanilla` source folder too). Depends only on `msc-protocol`; no server API.

- `WorldOps` and `ServerOps` carry out every operation in `Ops`; `AgentRoutes` wires them.
- A platform supplies three adapters: `AgentWorlds<S>` (worlds by name, block state parsing),
  `AgentWorld<S>` (block, entity and sign access for one world) and `AgentServer` (versions,
  commands, resource packs). `S` is the platform's block state type.
- `ReadJob`, `EditJob` and `HeightmapJob` are `SlicedJob`s the platform's `MainThread` runs in
  per-tick slices; every platform steps its jobs through one `JobQueue`, so all jobs in flight share one
  tick budget (`AgentDefaults.TICK_BUDGET`); `UndoRecord` and `EditHistory` keep undo.
- `BlockNames` suggests close block ids when a state names an unknown block.
- `AgentLink` connects to msc-core (`CoreAddress`), reconnects and sends events.

Tests run the operations against an in-memory world (`MapWorld` in the tests). Design: ADR 0024.
