
## Corpus Check
- 74 files · ~33,303 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1332 nodes · 2927 edges · 68 communities (22 shown, 46 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 194 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- CommandTree
- MongoRepository.java
- DatabaseType
- Redis
- Datapack
- Cache
- Vec3
- Hologram
- Database
- net.minecraft.network.chat.Component
- Dialogue
- Query
- Repository
- Format
- net.minecraft.world.item.ItemStack
- net.minecraft.server.level.ServerPlayer
- list
- Area
- Codec
- Override
- LootTable
- Sounds
- Json.java
- Sounds
- Stdlib.java
- Text.java
- Row
- Listener
- Cuboid
- Region
- Bar
- DatabaseConfig
- ParticleEffect
- Arena
- Game
- net.kyori.adventure.audience.Audience
- Economy
- .end
- Team
- Bar
- Task
- EventBus
- Leaderboard
- net.minecraft.world.entity.Entity
- .left
- Scheduler
- Hooks
- Sidebar
- java.sql.Connection
- Sequence
- .scheduler
- Builder
- Players
- Randoms
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
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Hooks.java → src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (68 total, 46 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.06
Nodes (7): PlayerData, Store, WriteBehind, Group, GroupEdit, Permissions, User

### Community 1 - "CommandTree"
Cohesion: 0.06
Nodes (7): Cmd, CommandTree.Source, CommandTree, Ctx, Fail, Source, Cooldowns

### Community 2 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 3 - "DatabaseType"
Cohesion: 0.07
Nodes (20): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+12 more)

### Community 4 - "Redis"
Cohesion: 0.08
Nodes (8): Messenger, Messenger, Subscription, Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 5 - "Datapack"
Cohesion: 0.09
Nodes (4): Recipes, Config, Validated, Datapack

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 8 - "Hologram"
Cohesion: 0.11
Nodes (3): Hologram, Lang, Messages

### Community 9 - "Database"
Cohesion: 0.14
Nodes (5): Database, DatabaseException, PoolStats, RowMapper, SqlFunction

### Community 10 - "net.minecraft.network.chat.Component"
Cohesion: 0.16
Nodes (3): Gui, GuiClick, Menu

### Community 13 - "Dialogue"
Cohesion: 0.18
Nodes (5): Npcs, Builder, Choice, Dialogue, Page

### Community 18 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.15
Nodes (3): CustomItems, Definition, Feedback

### Community 22 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 23 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 24 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 25 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 27 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 34 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 44 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 47 - "Task"
Cohesion: 0.33
Nodes (3): Entry, TickScheduler, Task

### Community 48 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 64 - ".phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 309 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **46 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Listener`, `Cuboid`, `Region`, `Npcs.java`, `ParticleEffect`, `Arena`, `Hologram`, `.orient`, `Datapack.java`, `map`, `.addScore`, `net.minecraft.world.entity.Entity`, `Repository.java`, `Area`, `.scheduler`?**
  _High betweenness centrality (0.135) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05714285714285714 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository.java`, `DatabaseType`, `Redis`, `map`, `Codec`, `Override`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.056189640035118525 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Listener`, `.phase`, `Arena`, `.end`, `Team`, `Task`, `.addScore`, `Repository.java`, `Scheduler`, `Builder`?**
  _High betweenness centrality (0.087) - this node is a cross-community bridge._
- **Should `MongoRepository.java` be split into smaller, more focused modules?**
  _Cohesion score 0.0633879781420765 - nodes in this community are weakly interconnected._
