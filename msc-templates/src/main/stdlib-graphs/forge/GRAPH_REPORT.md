
## Corpus Check
- 74 files · ~33,298 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1329 nodes · 2928 edges · 66 communities (23 shown, 43 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 194 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- CommandTree
- Cache
- MongoRepository
- Datapack
- DatabaseType
- Format
- Vec3
- map
- net.minecraft.world.item.ItemStack
- Gui
- Redis
- Redis.java
- Dialogue
- net.minecraft.server.level.ServerPlayer
- Database
- Query
- Codec
- LootTable
- Sounds
- Scheduler
- Area
- .init
- Text.java
- ParticleEffect
- Sounds
- .scheduler
- list
- Repository
- Row
- Hologram
- Listener
- Region
- net.minecraft.network.chat.Component
- DatabaseConfig
- java.sql.Connection
- net.kyori.adventure.audience.Audience
- .update
- Arena
- Game
- Cuboid
- Stdlib.java
- .end
- Team
- Bar
- Task
- Hooks
- Messages
- EventBus
- Leaderboard
- Sidebar
- Builder
- Randoms
- .apply
- .phase
- RateLimiter
- Scheduler.Task

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 96 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 33 edges
6. `Datapack` - 30 edges
7. `Shapes` - 29 edges
8. `Scheduler` - 27 edges
9. `CommandTree` - 26 edges
10. `Store` - 26 edges

## Surprising Connections (you probably didn't know these)
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Hooks.java → src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (66 total, 43 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.06
Nodes (7): PlayerData, Store, WriteBehind, Group, GroupEdit, Permissions, User

### Community 1 - "CommandTree"
Cohesion: 0.06
Nodes (7): Cmd, CommandTree.Source, CommandTree, Ctx, Fail, Source, Cooldowns

### Community 2 - "Cache"
Cohesion: 0.06
Nodes (13): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+5 more)

### Community 3 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 5 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 6 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 10 - "Gui"
Cohesion: 0.12
Nodes (3): Gui, GuiClick, Menu

### Community 11 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 14 - "Dialogue"
Cohesion: 0.17
Nodes (5): Npcs, Builder, Choice, Dialogue, Page

### Community 16 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 20 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 21 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 22 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 23 - "Scheduler"
Cohesion: 0.16
Nodes (3): Scheduler, Sequence, Step

### Community 25 - ".init"
Cohesion: 0.16
Nodes (3): CustomItems, Definition, Locations

### Community 28 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 35 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 38 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 45 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 48 - "Task"
Cohesion: 0.31
Nodes (3): Entry, TickScheduler, Task

### Community 51 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 60 - ".phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 306 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **43 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Hologram`, `Listener`, `Region`, `net.minecraft.network.chat.Component`, `map`, `Arena`, `Cuboid`, `uuid`, `Gui.java`, `.addScore`, `.orient`, `Area`, `.init`, `ParticleEffect`, `.lerp`?**
  _High betweenness centrality (0.136) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05714285714285714 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `Cache`, `MongoRepository`, `DatabaseType`, `Redis`, `Redis.java`, `Gui.java`, `MongoRepository.java`, `Codec`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.056189640035118525 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Listener`, `Arena`, `.end`, `Team`, `Task`, `Gui.java`, `.addScore`, `Scheduler`, `Builder`, `.phase`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Should `Cache` be split into smaller, more focused modules?**
  _Cohesion score 0.06127946127946128 - nodes in this community are weakly interconnected._
