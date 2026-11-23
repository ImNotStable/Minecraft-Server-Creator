
## Corpus Check
- 70 files · ~33,135 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1298 nodes · 2864 edges · 53 communities (21 shown, 32 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 161 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- org.bukkit.entity.Player
- Permissions
- Scheduler
- MongoRepository.java
- Cmd
- Json.java
- org.bukkit.Location
- Redis
- Database
- Cache
- Vec3
- org.bukkit.event.EventHandler
- Datapack
- ColumnType
- Format
- Cmd.java
- Regions.java
- ItemBuilder
- Query
- Repository
- Gui
- Override
- LootTable
- Definition
- Area
- Dialogue
- Row
- Sounds
- .update
- java.sql.Connection
- java.util.random.RandomGenerator
- Arena
- Game
- Cuboid
- DatabaseType
- Team
- EventBus
- .end
- Leaderboard
- .paged
- Builder
- Listener
- Randoms
- Matchmaker
- Phase
- RateLimiter
- WeightedRandom

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 85 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Gui` - 31 edges
6. `Permissions` - 30 edges
7. `Shapes` - 29 edges
8. `Datapack` - 28 edges
9. `Store` - 26 edges
10. `Scheduler` - 26 edges

## Surprising Connections (you probably didn't know these)
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Gui` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `PaperScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Field` --references--> `ColumnType`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/ColumnType.java

## Import Cycles
- None detected.

## Communities (53 total, 32 thin omitted)

### Community 0 - "org.bukkit.entity.Player"
Cohesion: 0.05
Nodes (10): Boards, Shown, Nametags, PaperScheduler, Task, Players, Pending, Prompt (+2 more)

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 2 - "Scheduler"
Cohesion: 0.06
Nodes (8): Scheduler, Task, Sequence, Scheduler.Task, Step, Bar, Feedback, Teleports

### Community 3 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 4 - "Cmd"
Cohesion: 0.06
Nodes (7): Messages, Cooldowns, Cmd, BasicCommand, Ctx, Fail, Lang

### Community 5 - "Json.java"
Cohesion: 0.06
Nodes (7): Config, Validated, Codec, Codec<>, FileStore, Json, TypeAdapter<>

### Community 6 - "org.bukkit.Location"
Cohesion: 0.06
Nodes (3): Hologram, Locations, ParticleEffect

### Community 7 - "Redis"
Cohesion: 0.08
Nodes (8): Messenger, Messenger, Subscription, Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 8 - "Database"
Cohesion: 0.08
Nodes (5): Database, PoolStats, RowMapper, DatabaseConfig, PaperDatabase

### Community 9 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 14 - "ColumnType"
Cohesion: 0.10
Nodes (14): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+6 more)

### Community 24 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 25 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 27 - "Area"
Cohesion: 0.14
Nodes (3): Region, Area, Regions

### Community 28 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 30 - "Sounds"
Cohesion: 0.15
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 32 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 37 - "DatabaseType"
Cohesion: 0.16
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 40 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 41 - ".end"
Cohesion: 0.21
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 50 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 285 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Store` connect `Permissions` to `MongoRepository.java`, `Json.java`, `Redis`, `Repository.java`, `ColumnType`, `map`, `list`, `Regions.java`, `Override`?**
  _High betweenness centrality (0.116) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `org.bukkit.entity.Player` be split into smaller, more focused modules?**
  _Cohesion score 0.05081081081081081 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `java.util.random.RandomGenerator`, `Arena`, `Cuboid`, `org.bukkit.Location`, `.orient`, `Listener`, `.addScore`, `map`, `list`, `Area`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06196291270918137 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `MongoRepository.java`, `Json.java`, `Database`, `Repository.java`, `Query`, `Row`, `.update`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Should `Scheduler` be split into smaller, more focused modules?**
  _Cohesion score 0.05764145954521417 - nodes in this community are weakly interconnected._
