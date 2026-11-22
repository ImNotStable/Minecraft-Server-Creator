
## Corpus Check
- 62 files · ~26,111 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1108 nodes · 2411 edges · 38 communities (16 shown, 22 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 138 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Vec3
- Gui
- Cmd
- MongoRepository.java
- Scheduler
- .mm
- DatabaseType
- org.bukkit.entity.Player
- Store
- org.bukkit.Location
- Redis
- Cache
- Regions.java
- Database
- Json.java
- Format
- Query
- list
- Codec
- Definition
- Area
- Repository
- .update
- Database.java
- EventBus
- org.bukkit.event.EventHandler
- Row
- Leaderboard
- java.sql.Connection
- Listener
- Randoms
- .apply
- Config
- RateLimiter

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
10. `Scheduler` - 21 edges

## Surprising Connections (you probably didn't know these)
- `Shown` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Boards.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Listener` --implements--> `Listener`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/CustomItems.java
- `Gui` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/Gui.java → src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/PaperScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-paper/io/github/imnotstable/msc/stdlib/paper/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (38 total, 22 thin omitted)

### Community 0 - "Vec3"
Cohesion: 0.06
Nodes (5): Cuboid, Region, Region, Shapes, Vec3

### Community 1 - "Gui"
Cohesion: 0.06
Nodes (5): CustomItems, Gui, Click, Theme, ItemBuilder

### Community 2 - "Cmd"
Cohesion: 0.05
Nodes (7): Messages, Cooldowns, Cmd, BasicCommand, Ctx, Fail, Lang

### Community 3 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 4 - "Scheduler"
Cohesion: 0.06
Nodes (10): Scheduler, Task, Sequence, Scheduler.Task, Step, PaperScheduler, Task, Prompt (+2 more)

### Community 5 - ".mm"
Cohesion: 0.06
Nodes (15): Bar, Feedback, Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY (+7 more)

### Community 6 - "DatabaseType"
Cohesion: 0.05
Nodes (20): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+12 more)

### Community 7 - "org.bukkit.entity.Player"
Cohesion: 0.06
Nodes (6): Text, Boards, Shown, Nametags, Players, Sidebar

### Community 8 - "Store"
Cohesion: 0.07
Nodes (6): Store, Store<>, Store<>, Store<>, WriteBehind, PlayerData

### Community 9 - "org.bukkit.Location"
Cohesion: 0.07
Nodes (3): Hologram, Locations, ParticleEffect

### Community 10 - "Redis"
Cohesion: 0.08
Nodes (8): Messenger, Messenger, Subscription, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 11 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 13 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 18 - "Codec"
Cohesion: 0.16
Nodes (3): Codec, Codec<>, FileStore

### Community 20 - "Area"
Cohesion: 0.13
Nodes (3): Region, Regions, Area

### Community 25 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

## Knowledge Gaps
- **29 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+24 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 235 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **22 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `MongoRepository.java`, `Database`, `Json.java`, `Query`, `.update`, `Row`, `Repository.java`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _29 weakly-connected nodes found - possible missing edges._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.061458718992965566 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository.java`, `DatabaseType`, `Redis`, `Regions.java`, `Codec`?**
  _High betweenness centrality (0.108) - this node is a cross-community bridge._
- **Should `Gui` be split into smaller, more focused modules?**
  _Cohesion score 0.05859969558599695 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `.contains`, `Area`, `org.bukkit.Location`?**
  _High betweenness centrality (0.073) - this node is a cross-community bridge._
- **Should `Cmd` be split into smaller, more focused modules?**
  _Cohesion score 0.05034965034965035 - nodes in this community are weakly interconnected._
