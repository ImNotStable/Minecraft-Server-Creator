
## Corpus Check
- 58 files · ~22,514 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 969 nodes · 2073 edges · 40 communities (18 shown, 22 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 125 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Vec3
- Gui.java
- MongoRepository
- net.minestom.server.entity.Player
- MinestomScheduler
- ColumnType
- Cache
- Format
- Repository
- Store
- Database
- Redis
- Hologram
- Json.java
- Codec
- Query
- Override
- net.kyori.adventure.audience.Audience
- Sounds
- Bar
- Row
- Scheduler
- EventBus
- DatabaseConfig
- Messages
- java.sql.Connection
- Leaderboard
- Randoms
- Sequence
- .apply
- Cooldowns
- Config
- Teleports.java
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 74 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Shapes` - 29 edges
5. `Store` - 24 edges
6. `Cache` - 23 edges
7. `MongoRepository` - 22 edges
8. `Scheduler` - 22 edges
9. `Gui` - 20 edges
10. `Format` - 19 edges

## Surprising Connections (you probably didn't know these)
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `ItemBuilder` --references--> `Builder`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ItemBuilder.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/cache/Cache.java
- `MinestomScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Area` --references--> `Region`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Regions.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Region.java

## Import Cycles
- None detected.

## Communities (40 total, 22 thin omitted)

### Community 0 - "Vec3"
Cohesion: 0.05
Nodes (7): Cuboid, Region, Region, Region, Shapes, Vec3, WeightedRandom

### Community 1 - "Gui.java"
Cohesion: 0.05
Nodes (7): Text, CustomItems, Definition, Gui, GuiClick, Menu, ItemBuilder

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "net.minestom.server.entity.Player"
Cohesion: 0.08
Nodes (4): ParticleEffect, Players, Regions, Area

### Community 4 - "MinestomScheduler"
Cohesion: 0.08
Nodes (7): Boards, Shown, MinestomScheduler, Task, Prompt, Pending, Stdlib

### Community 5 - "ColumnType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 6 - "Cache"
Cohesion: 0.09
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 8 - "Repository"
Cohesion: 0.14
Nodes (4): Repository, Field, Economy, Account

### Community 9 - "Store"
Cohesion: 0.13
Nodes (3): Store, WriteBehind, PlayerData

### Community 10 - "Database"
Cohesion: 0.11
Nodes (3): Database, PoolStats, RowMapper

### Community 11 - "Redis"
Cohesion: 0.12
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 15 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 17 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 20 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 24 - "Scheduler"
Cohesion: 0.18
Nodes (3): Scheduler, Task, Scheduler.Task

### Community 25 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 30 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

## Knowledge Gaps
- **29 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+24 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 211 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **22 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `ColumnType`, `Format`, `Database`, `Json.java`, `Query`, `MongoRepository.java`, `Row`?**
  _High betweenness centrality (0.106) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _29 weakly-connected nodes found - possible missing edges._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.05078855920876771 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `net.minestom.server.entity.Player`, `Hologram`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `Gui.java` be split into smaller, more focused modules?**
  _Cohesion score 0.05257312106627175 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository`, `ColumnType`, `Redis`, `Redis.java`, `Codec`, `Override`, `MongoRepository.java`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06262626262626263 - nodes in this community are weakly interconnected._
