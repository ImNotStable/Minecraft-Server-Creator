
## Corpus Check
- 75 files · ~35,337 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1408 nodes · 3132 edges · 54 communities (22 shown, 32 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 198 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Vec3
- MongoRepository.java
- Permissions
- Scheduler
- Dialogue
- Cmd
- Codec
- CommandTree
- org.bukkit.event.EventHandler
- Cache
- ColumnType
- Datapack
- ItemBuilder
- org.bukkit.Location
- Gui
- Database
- list
- Text.java
- Format
- Query
- Team
- Hologram
- net.kyori.adventure.text.Component
- VaultBridge.java
- Area
- LootTable
- org.bukkit.entity.Player
- Config.java
- Override
- Game
- Definition
- Row
- Listener
- java.sql.Connection
- Repository
- Economy
- EventBus
- DatabaseType
- .of
- Json.java
- DatabaseConfig
- .end
- Leaderboard
- Randoms
- Builder
- Config
- Click
- Migration
- TypeAdapter<>
- RateLimiter
- Phase

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 88 edges
2. `Game` - 45 edges
3. `Database` - 43 edges
4. `Repository` - 40 edges
5. `Permissions` - 33 edges
6. `Gui` - 32 edges
7. `Datapack` - 31 edges
8. `Shapes` - 29 edges
9. `Store` - 27 edges
10. `Scheduler` - 26 edges

## Surprising Connections (you probably didn't know these)
- `Shown` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Boards.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `Cmd` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Gui` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `PaperScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java

## Import Cycles
- None detected.

## Communities (54 total, 32 thin omitted)

### Community 0 - "Vec3"
Cohesion: 0.05
Nodes (7): Arena, Builder, Cuboid, Region, Region, Shapes, Vec3

### Community 1 - "MongoRepository.java"
Cohesion: 0.05
Nodes (9): Id, Indexed, Length, Messenger, Messenger, Subscription, Mongo, Store<> (+1 more)

### Community 2 - "Permissions"
Cohesion: 0.05
Nodes (8): Store, WriteBehind, Permissions, Group, GroupEdit, User, PaperPermissions, PlayerData

### Community 3 - "Scheduler"
Cohesion: 0.05
Nodes (9): Scheduler, Task, Sequence, Scheduler.Task, Step, Bar, Feedback, Sounds (+1 more)

### Community 4 - "Dialogue"
Cohesion: 0.06
Nodes (12): Dialogue, Builder, Choice, Page, Npcs, Clicks, PaperScheduler, Task (+4 more)

### Community 5 - "Cmd"
Cohesion: 0.06
Nodes (6): Messages, Cmd, BasicCommand, Ctx, Fail, Lang

### Community 6 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 7 - "CommandTree"
Cohesion: 0.08
Nodes (5): CommandTree, Ctx, Fail, Source, Cooldowns

### Community 8 - "org.bukkit.event.EventHandler"
Cohesion: 0.11
Nodes (3): Hooks, Hooks, Listener

### Community 9 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 11 - "ColumnType"
Cohesion: 0.11
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 15 - "Gui"
Cohesion: 0.13
Nodes (3): Gui, Nav, Theme

### Community 16 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 23 - "net.kyori.adventure.text.Component"
Cohesion: 0.14
Nodes (3): Boards, Shown, Sidebar

### Community 25 - "Area"
Cohesion: 0.13
Nodes (3): Region, Regions, Area

### Community 26 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 29 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 35 - "java.sql.Connection"
Cohesion: 0.22
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 38 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 39 - "DatabaseType"
Cohesion: 0.14
Nodes (7): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE, PaperDatabase

### Community 43 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 53 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 296 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Game` to `Vec3`, `Listener`, `Gui.java`, `Scheduler`, `.end`, `Builder`, `Team`, `Phase`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.05005107252298264 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `Listener`, `Gui.java`, `org.bukkit.Location`, `list`, `Team`, `Area`?**
  _High betweenness centrality (0.084) - this node is a cross-community bridge._
- **Should `MongoRepository.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05136986301369863 - nodes in this community are weakly interconnected._
- **Why does `Database` connect `Database` to `Gui.java`, `java.sql.Connection`, `Repository`, `Economy`, `Permissions`, `DatabaseConfig`, `ColumnType`, `Database.java`, `Migration`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05251141552511415 - nodes in this community are weakly interconnected._
