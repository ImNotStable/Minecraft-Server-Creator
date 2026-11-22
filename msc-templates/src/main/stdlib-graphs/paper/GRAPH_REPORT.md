
## Corpus Check
- 62 files · ~26,071 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1107 nodes · 2399 edges · 43 communities (20 shown, 23 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 138 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Gui
- Vec3
- Cmd
- MongoRepository.java
- .mm
- Store
- org.bukkit.Location
- Redis
- Json.java
- Cache
- list
- Scheduler
- org.bukkit.event.EventHandler
- Repository
- .scheduler
- Database
- Format
- org.bukkit.entity.Player
- Codec
- Sidebar
- .update
- Row
- EventBus
- java.sql.Connection
- Table
- Leaderboard
- PaperDatabase.java
- DatabaseType
- org.bukkit.event.player.PlayerQuitEvent
- ColumnType
- DatabaseConfig
- Players.java
- Gui.java
- Randoms
- .syncSchema
- Nametags.java
- .apply
- RateLimiter
- Prompt

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 76 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Gui` - 32 edges
5. `Shapes` - 29 edges
6. `ItemBuilder` - 24 edges
7. `Store` - 24 edges
8. `Cache` - 23 edges
9. `MongoRepository` - 22 edges
10. `Cmd` - 19 edges

## Surprising Connections (you probably didn't know these)
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Bar.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `Shown` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Boards.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Listener` --implements--> `Listener`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java
- `Gui` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java

## Import Cycles
- None detected.

## Communities (43 total, 23 thin omitted)

### Community 0 - "Gui"
Cohesion: 0.05
Nodes (6): CustomItems, Definition, Gui, Click, Theme, ItemBuilder

### Community 1 - "Vec3"
Cohesion: 0.05
Nodes (6): Cuboid, Region, Region, Shapes, Vec3, WeightedRandom

### Community 2 - "Cmd"
Cohesion: 0.05
Nodes (7): Messages, Cooldowns, Cmd, BasicCommand, Ctx, Fail, Lang

### Community 3 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 4 - ".mm"
Cohesion: 0.06
Nodes (14): Bar, Feedback, Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY (+6 more)

### Community 5 - "Store"
Cohesion: 0.07
Nodes (6): Store, Store<>, Store<>, Store<>, WriteBehind, PlayerData

### Community 6 - "org.bukkit.Location"
Cohesion: 0.06
Nodes (3): Hologram, Locations, ParticleEffect

### Community 7 - "Redis"
Cohesion: 0.08
Nodes (8): Messenger, Messenger, Subscription, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 8 - "Json.java"
Cohesion: 0.08
Nodes (4): Config, Validated, Json, TypeAdapter<>

### Community 9 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 11 - "Scheduler"
Cohesion: 0.11
Nodes (6): Scheduler, Task, Sequence, Scheduler.Task, Step, Teleports

### Community 14 - ".scheduler"
Cohesion: 0.14
Nodes (3): PaperScheduler, Task, Stdlib

### Community 15 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 17 - "org.bukkit.entity.Player"
Cohesion: 0.14
Nodes (3): Region, Regions, Area

### Community 18 - "Codec"
Cohesion: 0.16
Nodes (3): Codec, Codec<>, FileStore

### Community 20 - "Sidebar"
Cohesion: 0.14
Nodes (3): Boards, Shown, Sidebar

### Community 25 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 26 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 30 - "DatabaseType"
Cohesion: 0.20
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 32 - "ColumnType"
Cohesion: 0.18
Nodes (10): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+2 more)

## Knowledge Gaps
- **29 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+24 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 234 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **23 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `MongoRepository.java`, `.syncSchema`, `.toDb`, `Json.java`, `Database`, `Repository.java`, `.update`, `Row`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _29 weakly-connected nodes found - possible missing edges._
- **Should `Gui` be split into smaller, more focused modules?**
  _Cohesion score 0.05070028011204482 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `org.bukkit.entity.Player`, `org.bukkit.Location`?**
  _High betweenness centrality (0.083) - this node is a cross-community bridge._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.05462962962962963 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository.java`, `Redis`, `Codec`, `Mongo.java`, `map`, `Table`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Should `Cmd` be split into smaller, more focused modules?**
  _Cohesion score 0.05034965034965035 - nodes in this community are weakly interconnected._
