
## Corpus Check
- 74 files · ~33,054 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1339 nodes · 2951 edges · 57 communities (23 shown, 34 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 200 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- MongoRepository.java
- CommandTree
- Permissions
- Scheduler
- Bar
- Codec
- Datapack
- ColumnType
- Cache
- Repository
- Vec3
- Task
- Database
- net.minecraft.network.chat.Component
- Hooks
- Redis.java
- Format
- Team
- list
- Area
- net.minecraft.server.level.ServerPlayer
- Query
- LootTable
- java.sql.Connection
- Override
- Dialogue
- Listener
- Game
- Row
- Gui
- ItemBuilder
- Messages
- EventBus
- Builder
- Cuboid
- Leaderboard
- Menu
- Override
- Sidebar
- Randoms
- .end
- Builder
- forge/Sounds.java
- Locations
- Migration
- RateLimiter
- Phase

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
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Hooks.java → src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/TickScheduler.java
- `Npcs` --references--> `Dialogue`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Npcs.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/npc/Dialogue.java

## Import Cycles
- None detected.

## Communities (57 total, 34 thin omitted)

### Community 0 - "MongoRepository.java"
Cohesion: 0.05
Nodes (9): Id, Indexed, Length, Messenger, Messenger, Subscription, Mongo, Store<> (+1 more)

### Community 1 - "CommandTree"
Cohesion: 0.05
Nodes (7): Cmd, CommandTree.Source, CommandTree, Ctx, Fail, Source, Cooldowns

### Community 2 - "Permissions"
Cohesion: 0.06
Nodes (7): PlayerData, Store, WriteBehind, Permissions, Group, GroupEdit, User

### Community 3 - "Scheduler"
Cohesion: 0.06
Nodes (6): Hologram, ParticleEffect, Scheduler, Sequence, Scheduler.Task, Step

### Community 4 - "Bar"
Cohesion: 0.06
Nodes (4): Text, Bar, Feedback, Sounds

### Community 5 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 6 - "Datapack"
Cohesion: 0.08
Nodes (4): Recipes, Config, Validated, Datapack

### Community 7 - "ColumnType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 9 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 10 - "Repository"
Cohesion: 0.12
Nodes (4): Repository, Field, Economy, Account

### Community 12 - "Task"
Cohesion: 0.12
Nodes (6): Prompt, Pending, Teleports, TickScheduler, Entry, Task

### Community 13 - "Database"
Cohesion: 0.10
Nodes (3): Database, PoolStats, DatabaseConfig

### Community 15 - "Hooks"
Cohesion: 0.11
Nodes (3): CustomItems, Hooks, Stdlib

### Community 20 - "Area"
Cohesion: 0.18
Nodes (3): Regions, Area, Region

### Community 21 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.15
Nodes (3): Definition, Npcs, Players

### Community 23 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 24 - "java.sql.Connection"
Cohesion: 0.17
Nodes (4): DatabaseException, RowMapper, SqlFunction, Schema

### Community 25 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 27 - "Dialogue"
Cohesion: 0.22
Nodes (4): Dialogue, Builder, Choice, Page

### Community 32 - "Gui"
Cohesion: 0.20
Nodes (3): Gui, GuiClick, Nav

### Community 35 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 47 - ".end"
Cohesion: 0.27
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 55 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 293 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **34 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Scheduler`, `Builder`, `Cuboid`, `.orient`, `map`, `Task`, `Override`, `Team`, `Locations`, `Area`, `net.minecraft.server.level.ServerPlayer`, `list`, `Listener`?**
  _High betweenness centrality (0.163) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `MongoRepository.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05046948356807512 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Scheduler`, `Builder`, `map`, `Task`, `.end`, `Builder`, `Team`, `Phase`, `Listener`?**
  _High betweenness centrality (0.095) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.05311871227364185 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository.java`, `Codec`, `ColumnType`, `map`, `Redis.java`, `list`, `Override`?**
  _High betweenness centrality (0.078) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05507246376811594 - nodes in this community are weakly interconnected._
