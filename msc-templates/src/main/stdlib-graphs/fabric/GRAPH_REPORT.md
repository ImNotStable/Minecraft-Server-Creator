
## Corpus Check
- 69 files · ~30,585 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1225 nodes · 2652 edges · 62 communities (21 shown, 41 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 171 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.minecraft.world.item.ItemStack
- Permissions
- MongoRepository
- Codec
- DatabaseType
- Datapack
- Format
- Cache
- Vec3
- Task
- net.minecraft.server.level.ServerPlayer
- Database
- Query
- Team
- Override
- LootTable
- Sounds
- Sounds
- Area
- Definition
- Game
- Json.java
- Dialogue
- Text.java
- Repository
- Row
- Listener
- Bar
- Hologram
- Cuboid
- Region
- Stdlib.java
- DatabaseConfig
- ParticleEffect
- java.sql.Connection
- .update
- Arena
- .end
- arraylist
- net.kyori.adventure.audience.Audience
- Messages
- EventBus
- Leaderboard
- Hooks
- Scheduler
- Sequence
- Builder
- Randoms
- Cooldowns
- .apply
- Phase
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
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Hooks.java → src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Area` --references--> `Region`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Regions.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Region.java

## Import Cycles
- None detected.

## Communities (62 total, 41 thin omitted)

### Community 0 - "net.minecraft.world.item.ItemStack"
Cohesion: 0.06
Nodes (5): Bar, Gui, GuiClick, Menu, ItemBuilder

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (7): PlayerData, Store, WriteBehind, Group, GroupEdit, Permissions, User

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 4 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "Format"
Cohesion: 0.09
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 7 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 11 - "Task"
Cohesion: 0.15
Nodes (6): Pending, Prompt, Teleports, Entry, TickScheduler, Task

### Community 13 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 18 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 19 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 20 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 21 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 23 - "Definition"
Cohesion: 0.16
Nodes (3): CustomItems, Definition, Locations

### Community 26 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 34 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 38 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 41 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 45 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 57 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 281 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **41 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Hologram`, `Cuboid`, `Region`, `ParticleEffect`, `Arena`, `map`, `Task`, `Team`, `list`, `.orient`, `Area`, `Definition`, `.lerp`, `Listener`?**
  _High betweenness centrality (0.139) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `net.minecraft.world.item.ItemStack` be split into smaller, more focused modules?**
  _Cohesion score 0.05548654244306418 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `DatabaseType`, `Format`, `.update`, `Repository.java`, `Database`, `Query`, `Json.java`, `Row`?**
  _High betweenness centrality (0.100) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.059676044330775786 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Arena`, `.end`, `Task`, `Team`, `list`, `Scheduler`, `Builder`, `Phase`, `Listener`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06363636363636363 - nodes in this community are weakly interconnected._
