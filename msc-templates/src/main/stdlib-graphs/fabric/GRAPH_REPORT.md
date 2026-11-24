
## Corpus Check
- 74 files · ~33,059 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1342 nodes · 2950 edges · 71 communities (22 shown, 49 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 200 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- Datapack
- ColumnType
- Cache
- Format
- Repository
- Vec3
- Database
- Cuboid
- TickScheduler
- Gui
- net.minecraft.world.item.ItemStack
- Area
- net.minecraft.server.level.ServerPlayer
- list
- Json.java
- Hooks
- Codec
- Query
- LootTable
- MongoRepository
- java.sql.Connection
- ParticleEffect
- Dialogue
- Override
- ItemBuilder.java
- Mongo
- Cmd.java
- Row
- Game
- Listener
- Bar
- net.minecraft.network.chat.Component
- Bar
- Hologram
- EventBus
- Text.java
- Messages
- Team
- Override
- Redis
- CommandTree
- .end
- Messenger
- net.kyori.adventure.audience.Audience
- Ctx
- Scheduler
- Leaderboard
- Randoms
- .dispatch
- Override
- Sequence
- Sidebar
- Builder
- Cooldowns
- ui/Sounds.java
- Locations
- CommandTree.Source
- Migration
- RateLimiter
- Phase
- Scheduler.Task

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 99 edges
2. `Game` - 45 edges
3. `Database` - 43 edges
4. `Repository` - 40 edges
5. `Permissions` - 32 edges
6. `Datapack` - 31 edges
7. `CommandTree` - 29 edges
8. `Shapes` - 29 edges
9. `Store` - 27 edges
10. `Scheduler` - 27 edges

## Surprising Connections (you probably didn't know these)
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `CommandTree`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/cmd/CommandTree.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Hooks.java → src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/TickScheduler.java

## Import Cycles
- None detected.

## Communities (71 total, 49 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.05
Nodes (7): PlayerData, Store, WriteBehind, Permissions, Group, GroupEdit, User

### Community 1 - "Datapack"
Cohesion: 0.08
Nodes (4): Recipes, Config, Validated, Datapack

### Community 2 - "ColumnType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 3 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 4 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 5 - "Repository"
Cohesion: 0.12
Nodes (4): Repository, Field, Economy, Account

### Community 7 - "Database"
Cohesion: 0.10
Nodes (3): Database, PoolStats, DatabaseConfig

### Community 8 - "Cuboid"
Cohesion: 0.10
Nodes (3): Arena, Builder, Cuboid

### Community 9 - "TickScheduler"
Cohesion: 0.12
Nodes (4): Prompt, Pending, TickScheduler, Entry

### Community 10 - "Gui"
Cohesion: 0.12
Nodes (4): Gui, GuiClick, Menu, Nav

### Community 12 - "Area"
Cohesion: 0.14
Nodes (3): Regions, Area, Region

### Community 14 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.14
Nodes (3): Definition, Npcs, Players

### Community 18 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 21 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 23 - "java.sql.Connection"
Cohesion: 0.17
Nodes (4): DatabaseException, RowMapper, SqlFunction, Schema

### Community 26 - "Dialogue"
Cohesion: 0.22
Nodes (4): Dialogue, Builder, Choice, Page

### Community 27 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 38 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 45 - "Override"
Cohesion: 0.22
Nodes (3): JedisPubSub, Messenger, Store<>

### Community 48 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 49 - "Messenger"
Cohesion: 0.21
Nodes (3): Messenger, Messenger, Subscription

### Community 69 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 296 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **49 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Locations`, `Listener`, `Hologram`, `.orient`, `Cuboid`, `Area`, `map`, `net.minecraft.server.level.ServerPlayer`, `list`, `Gui.java`, `.addScore`, `ParticleEffect`, `Override`?**
  _High betweenness centrality (0.163) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.053208137715179966 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Listener`, `Phase`, `Cuboid`, `Team`, `.end`, `Gui.java`, `Scheduler`, `.addScore`, `Builder`?**
  _High betweenness centrality (0.094) - this node is a cross-community bridge._
- **Should `Datapack` be split into smaller, more focused modules?**
  _Cohesion score 0.08139534883720931 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `ColumnType`, `map`, `Redis`, `Codec`, `MongoRepository.java`, `Override`, `Mongo`?**
  _High betweenness centrality (0.078) - this node is a cross-community bridge._
- **Should `ColumnType` be split into smaller, more focused modules?**
  _Cohesion score 0.07928118393234672 - nodes in this community are weakly interconnected._
