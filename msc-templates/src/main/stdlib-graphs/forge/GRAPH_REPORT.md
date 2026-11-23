
## Corpus Check
- 69 files · ~30,593 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1224 nodes · 2655 edges · 65 communities (21 shown, 44 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 171 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- MongoRepository
- Codec
- DatabaseType
- Vec3
- Datapack
- Format
- Cache
- Database
- Area
- Query
- Redis.java
- Gui
- list
- Override
- LootTable
- ParticleEffect
- Repository
- Sounds
- net.minecraft.world.item.ItemStack
- Dialogue
- Text.java
- Row
- net.minecraft.network.chat.Component
- Hologram
- net.minecraft.server.level.ServerPlayer
- java.sql.Connection
- Hooks
- ItemBuilder
- .scheduler
- Listener
- Sounds
- Arena
- Game
- Region
- Bar
- net.kyori.adventure.audience.Audience
- .update
- .end
- Team
- Messages
- EventBus
- Cuboid
- Leaderboard
- DatabaseConfig
- Task
- Scheduler
- Sequence
- Builder
- Randoms
- Cooldowns
- .phase
- RateLimiter
- Scheduler.Task

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 94 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 30 edges
6. `Shapes` - 29 edges
7. `Datapack` - 28 edges
8. `Scheduler` - 27 edges
9. `Store` - 26 edges
10. `Cache` - 22 edges

## Surprising Connections (you probably didn't know these)
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Hooks.java → src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Pending` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Prompt.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java

## Import Cycles
- None detected.

## Communities (65 total, 44 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.06
Nodes (7): PlayerData, Store, WriteBehind, Group, GroupEdit, Permissions, User

### Community 1 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 2 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 3 - "DatabaseType"
Cohesion: 0.07
Nodes (20): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+12 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 7 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 9 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 10 - "Area"
Cohesion: 0.14
Nodes (3): Locations, Area, Regions

### Community 13 - "Gui"
Cohesion: 0.16
Nodes (3): Gui, GuiClick, Menu

### Community 15 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 16 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 21 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 23 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 30 - "java.sql.Connection"
Cohesion: 0.21
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 35 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 38 - "Region"
Cohesion: 0.18
Nodes (3): Region, Region, Region

### Community 43 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 46 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 53 - "Task"
Cohesion: 0.38
Nodes (3): Entry, TickScheduler, Task

### Community 60 - ".phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 280 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **44 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Listener`, `Arena`, `Region`, `map`, `Area`, `.addScore`, `Cuboid`, `ParticleEffect`, `.orient`, `Gui.java`, `Hologram`?**
  _High betweenness centrality (0.140) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.058385093167701865 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `DatabaseType`, `Format`, `Database`, `.update`, `Query`, `Redis.java`, `MongoRepository.java`, `Row`?**
  _High betweenness centrality (0.100) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06363636363636363 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Listener`, `Arena`, `.end`, `Team`, `.addScore`, `Task`, `Scheduler`, `Gui.java`, `Builder`, `.phase`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Should `Codec` be split into smaller, more focused modules?**
  _Cohesion score 0.06802721088435375 - nodes in this community are weakly interconnected._
