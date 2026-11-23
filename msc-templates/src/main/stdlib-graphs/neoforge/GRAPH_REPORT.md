
## Corpus Check
- 74 files · ~33,334 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1332 nodes · 2930 edges · 61 communities (22 shown, 39 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 194 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.minecraft.network.chat.Component
- Permissions
- CommandTree
- Cache
- MongoRepository
- Scheduler
- Datapack
- DatabaseType
- Format
- Vec3
- Redis
- Task
- Database
- Query
- Team
- Dialogue
- net.minecraft.server.level.ServerPlayer
- list
- Codec
- LootTable
- Sounds
- Json.java
- Area
- Game
- Text.java
- Sounds
- Stdlib.java
- Repository
- Row
- Listener
- Cuboid
- Region
- DatabaseConfig
- java.sql.Connection
- Hooks
- .update
- Bar
- net.kyori.adventure.audience.Audience
- Arena
- .end
- Messages
- .init
- net.minecraft.world.entity.Entity
- Sidebar
- EventBus
- Leaderboard
- Builder
- Randoms
- .apply
- Phase
- RateLimiter

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
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Hooks.java → src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (61 total, 39 thin omitted)

### Community 0 - "net.minecraft.network.chat.Component"
Cohesion: 0.06
Nodes (5): Bar, Gui, GuiClick, Menu, ItemBuilder

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 2 - "CommandTree"
Cohesion: 0.06
Nodes (7): CommandTree, Ctx, Fail, Source, Cooldowns, Cmd, CommandTree.Source

### Community 3 - "Cache"
Cohesion: 0.06
Nodes (13): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+5 more)

### Community 4 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 5 - "Scheduler"
Cohesion: 0.06
Nodes (6): Scheduler, Sequence, Scheduler.Task, Step, Hologram, ParticleEffect

### Community 6 - "Datapack"
Cohesion: 0.08
Nodes (4): Config, Validated, Datapack, Recipes

### Community 7 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 8 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 11 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 12 - "Task"
Cohesion: 0.13
Nodes (6): Task, Pending, Prompt, Teleports, Entry, TickScheduler

### Community 13 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 16 - "Dialogue"
Cohesion: 0.18
Nodes (5): Builder, Choice, Dialogue, Page, Npcs

### Community 19 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 20 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 21 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 27 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 35 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 37 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 43 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 48 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 57 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 309 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **39 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Listener`, `Gui.java`, `Cuboid`, `Region`, `Scheduler`, `map`, `Arena`, `Task`, `net.minecraft.world.entity.Entity`, `Team`, `list`, `.orient`, `Area`, `.lerp`?**
  _High betweenness centrality (0.137) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `net.minecraft.network.chat.Component` be split into smaller, more focused modules?**
  _Cohesion score 0.05548654244306418 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `Cache`, `MongoRepository`, `DatabaseType`, `map`, `Redis`, `Codec`, `MongoRepository.java`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.059676044330775786 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Listener`, `Gui.java`, `Scheduler`, `Arena`, `.end`, `Task`, `Team`, `Builder`, `Phase`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.06009615384615385 - nodes in this community are weakly interconnected._
