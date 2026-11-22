
## Corpus Check
- 47 files · ~19,572 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 814 nodes · 1681 edges · 33 communities (15 shown, 18 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 113 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Vec3
- Gui.java
- ColumnType
- MongoRepository
- Format
- Cache
- Redis
- MinestomScheduler
- ParticleEffect
- Store
- Database
- Scheduler
- Query
- Repository.java
- Codec
- Override
- list
- Row
- EventBus
- Repository
- java.sql.Connection
- Leaderboard
- Randoms
- Economy
- Config
- Messages
- Cooldowns
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 74 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Shapes` - 29 edges
5. `Store` - 24 edges
6. `Cache` - 23 edges
7. `MongoRepository` - 22 edges
8. `Gui` - 20 edges
9. `ColumnType` - 18 edges
10. `DatabaseType` - 18 edges

## Surprising Connections (you probably didn't know these)
- `ItemBuilder` --references--> `Builder`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ItemBuilder.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/cache/Cache.java
- `MinestomScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Field` --references--> `ColumnType`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/ColumnType.java
- `Database` --references--> `DatabaseConfig`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Database.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/DatabaseConfig.java

## Import Cycles
- None detected.

## Communities (33 total, 18 thin omitted)

### Community 0 - "Vec3"
Cohesion: 0.06
Nodes (6): Cuboid, Region, Region, Region, Shapes, Vec3

### Community 1 - "Gui.java"
Cohesion: 0.06
Nodes (5): Text, Gui, GuiClick, Menu, ItemBuilder

### Community 2 - "ColumnType"
Cohesion: 0.05
Nodes (21): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+13 more)

### Community 3 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "Format"
Cohesion: 0.07
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 5 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 6 - "Redis"
Cohesion: 0.11
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 7 - "MinestomScheduler"
Cohesion: 0.14
Nodes (3): MinestomScheduler, Task, Stdlib

### Community 9 - "Store"
Cohesion: 0.14
Nodes (3): Store, WriteBehind, PlayerData

### Community 11 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 12 - "Scheduler"
Cohesion: 0.13
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 15 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 16 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 20 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 24 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

## Knowledge Gaps
- **18 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+13 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 168 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `ParticleEffect`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _18 weakly-connected nodes found - possible missing edges._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.057124310288867254 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `ColumnType`, `Format`, `Database`, `Query`, `Repository.java`, `Row`, `Economy`, `.update`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Should `Gui.java` be split into smaller, more focused modules?**
  _Cohesion score 0.06240084611316764 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `ColumnType`, `MongoRepository`, `Redis`, `Redis.java`, `Codec`, `Override`, `MongoRepository.java`?**
  _High betweenness centrality (0.118) - this node is a cross-community bridge._
- **Should `ColumnType` be split into smaller, more focused modules?**
  _Cohesion score 0.053551912568306013 - nodes in this community are weakly interconnected._
