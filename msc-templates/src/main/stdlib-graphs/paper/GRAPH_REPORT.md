
## Corpus Check
- 75 files · ~35,653 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1384 nodes · 3101 edges · 61 communities (23 shown, 38 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 186 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Dialogue
- Permissions
- Gui
- org.bukkit.event.EventHandler
- MongoRepository
- Cmd
- DatabaseType
- Datapack
- CommandTree
- Format
- Cache
- Vec3
- MongoRepository.java
- Redis
- Database
- java.util.random.RandomGenerator
- Query
- VaultBridge.java
- Sounds
- Hologram
- Codec
- Override
- LootTable
- org.bukkit.entity.Player
- Npcs.java
- Repository
- Row
- Area
- .mm
- Bar
- DatabaseConfig
- Cuboid
- java.sql.Connection
- org.bukkit.Location
- Arena
- Game
- Sidebar
- Team
- Scheduler
- ParticleEffect
- EventBus
- .end
- Leaderboard
- Economy
- Builder
- Listener
- Sequence
- Nametags.java
- Randoms
- Matchmaker
- Phase
- RateLimiter
- Scheduler.Task

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 85 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 34 edges
6. `Gui` - 31 edges
7. `Datapack` - 30 edges
8. `Shapes` - 29 edges
9. `Store` - 26 edges
10. `Scheduler` - 26 edges

## Surprising Connections (you probably didn't know these)
- `Shown` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Boards.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `Shown` --references--> `Sidebar`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Boards.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Sidebar.java
- `Cmd` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Gui` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java

## Import Cycles
- None detected.

## Communities (61 total, 38 thin omitted)

### Community 0 - "Dialogue"
Cohesion: 0.06
Nodes (12): Builder, Choice, Dialogue, Page, Clicks, Npcs, PaperPermissions, PaperScheduler (+4 more)

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (8): Store, WriteBehind, Group, GroupEdit, Permissions, User, Hooks, PlayerData

### Community 2 - "Gui"
Cohesion: 0.07
Nodes (4): Click, Gui, Theme, ItemBuilder

### Community 3 - "org.bukkit.event.EventHandler"
Cohesion: 0.05
Nodes (7): CustomItems, Listener, Definition, Listener, Hooks, Listener, Listener

### Community 4 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 5 - "Cmd"
Cohesion: 0.06
Nodes (6): Messages, Cmd, BasicCommand, Ctx, Fail, Lang

### Community 6 - "DatabaseType"
Cohesion: 0.07
Nodes (20): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+12 more)

### Community 7 - "Datapack"
Cohesion: 0.09
Nodes (4): Config, Validated, Datapack, Recipes

### Community 8 - "CommandTree"
Cohesion: 0.09
Nodes (5): CommandTree, Ctx, Fail, Source, Cooldowns

### Community 9 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 10 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 13 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 14 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 16 - "java.util.random.RandomGenerator"
Cohesion: 0.11
Nodes (4): Region, Region, Region, WeightedRandom

### Community 21 - "Sounds"
Cohesion: 0.11
Nodes (13): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+5 more)

### Community 23 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 24 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 25 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 26 - "org.bukkit.entity.Player"
Cohesion: 0.17
Nodes (3): Boards, Shown, Players

### Community 37 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 45 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 46 - ".end"
Cohesion: 0.21
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 58 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 303 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **38 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Game` to `Arena`, `Team`, `Scheduler`, `.end`, `Builder`, `Listener`, `.addScore`, `Matchmaker`, `Phase`, `Gui.java`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `Dialogue` be split into smaller, more focused modules?**
  _Cohesion score 0.05755693581780538 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `Cuboid`, `org.bukkit.Location`, `Arena`, `ParticleEffect`, `map`, `.orient`, `java.util.random.RandomGenerator`, `Listener`, `.addScore`, `Gui.java`?**
  _High betweenness centrality (0.104) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06010230179028133 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `Repository.java`, `DatabaseType`, `Format`, `MongoRepository.java`, `Database`, `Economy`, `Query`, `VaultBridge.java`, `Row`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Should `Gui` be split into smaller, more focused modules?**
  _Cohesion score 0.06826923076923076 - nodes in this community are weakly interconnected._
