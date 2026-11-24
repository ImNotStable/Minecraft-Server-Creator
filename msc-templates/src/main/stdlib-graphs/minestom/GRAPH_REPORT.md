
## Corpus Check
- 69 files · ~31,149 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1251 nodes · 2744 edges · 62 communities (20 shown, 42 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 185 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.kyori.adventure.audience.Audience
- MongoRepository
- Codec
- MinestomScheduler
- CommandTree
- Cache
- Database
- Format
- Vec3
- Datapack
- ColumnType
- Scheduler
- Json.java
- Query
- Team
- LootTable
- Permissions
- Override
- net.minestom.server.coordinate.Point
- Game
- Cmd.java
- ItemBuilder
- .update
- Hologram
- Definition
- Area
- list
- Repository
- Row
- Listener
- java.sql.Connection
- Store
- EventBus
- Cuboid
- Dialogue
- net.minestom.server.item.ItemStack
- DatabaseType
- WriteBehind
- Builder
- Region
- Text.java
- GroupEdit
- .end
- Leaderboard
- Npcs.java
- net.minestom.server.entity.Player
- Randoms
- Locations.java
- Builder
- Players.java
- net.kyori.adventure.text.Component
- Config
- Migration
- RateLimiter
- Phase

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 86 edges
2. `Game` - 45 edges
3. `Database` - 43 edges
4. `Repository` - 40 edges
5. `Permissions` - 32 edges
6. `Datapack` - 29 edges
7. `Shapes` - 29 edges
8. `Store` - 27 edges
9. `Scheduler` - 27 edges
10. `CommandTree` - 25 edges

## Surprising Connections (you probably didn't know these)
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `MinestomScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Npcs` --references--> `Dialogue`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Npcs.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/npc/Dialogue.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (62 total, 42 thin omitted)

### Community 0 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.06
Nodes (5): Messages, Bar, Feedback, Sounds, Lang

### Community 1 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 2 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 3 - "MinestomScheduler"
Cohesion: 0.08
Nodes (7): Boards, Shown, MinestomScheduler, Task, Prompt, Pending, Stdlib

### Community 4 - "CommandTree"
Cohesion: 0.08
Nodes (5): CommandTree, Ctx, Fail, Source, Cooldowns

### Community 5 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 6 - "Database"
Cohesion: 0.09
Nodes (4): Database, PoolStats, RowMapper, DatabaseConfig

### Community 7 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 10 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 12 - "Scheduler"
Cohesion: 0.13
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 17 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 20 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 33 - "java.sql.Connection"
Cohesion: 0.22
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 35 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 37 - "Dialogue"
Cohesion: 0.27
Nodes (4): Dialogue, Builder, Choice, Page

### Community 39 - "net.minestom.server.item.ItemStack"
Cohesion: 0.26
Nodes (3): Gui, GuiClick, Nav

### Community 40 - "DatabaseType"
Cohesion: 0.13
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 44 - "Region"
Cohesion: 0.18
Nodes (3): Region, Region, Region

### Community 47 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 61 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 270 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **42 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Listener`, `Cuboid`, `map`, `Builder`, `Gui.java`, `.orient`, `Region`, `Team`, `Locations.java`, `net.minestom.server.coordinate.Point`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.06110102843315184 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `Database`, `Format`, `DatabaseType`, `ColumnType`, `Json.java`, `Query`, `MongoRepository.java`, `.update`, `Row`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository`, `Codec`, `WriteBehind`, `ColumnType`, `Gui.java`, `GroupEdit`, `Permissions.java`, `Permissions`, `MongoRepository.java`, `Override`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **Should `Codec` be split into smaller, more focused modules?**
  _Cohesion score 0.06561085972850679 - nodes in this community are weakly interconnected._
