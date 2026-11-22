
## Corpus Check
- 48 files · ~19,376 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 810 nodes · 1643 edges · 34 communities (16 shown, 18 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 114 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Vec3
- net.kyori.adventure.audience.Audience
- ColumnType
- MongoRepository
- Format
- Cache
- VelocityScheduler
- Redis
- Scheduler
- Store
- Database
- Query
- Codec
- Override
- Json.java
- Sounds
- Row
- EventBus
- Repository
- java.sql.Connection
- Leaderboard
- Randoms
- Economy
- Config
- Cooldowns
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 64 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Shapes` - 29 edges
5. `Store` - 24 edges
6. `Cache` - 23 edges
7. `MongoRepository` - 22 edges
8. `ColumnType` - 18 edges
9. `DatabaseType` - 18 edges
10. `Cuboid` - 18 edges

## Surprising Connections (you probably didn't know these)
- `VelocityScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-velocity/io/github/imnotstable/msc/stdlib/velocity/VelocityScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Field` --references--> `ColumnType`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/ColumnType.java
- `Database` --references--> `DatabaseConfig`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Database.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/DatabaseConfig.java
- `Repository` --references--> `Database`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Database.java
- `Economy` --references--> `Database`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/economy/Economy.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Database.java

## Import Cycles
- None detected.

## Communities (34 total, 18 thin omitted)

### Community 0 - "Vec3"
Cohesion: 0.05
Nodes (7): Cuboid, Region, Region, Region, Shapes, Vec3, WeightedRandom

### Community 1 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.05
Nodes (5): Messages, Text, Bar, Feedback, Lang

### Community 2 - "ColumnType"
Cohesion: 0.05
Nodes (21): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+13 more)

### Community 3 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 5 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 6 - "VelocityScheduler"
Cohesion: 0.11
Nodes (4): Servers, Stdlib, VelocityScheduler, Task

### Community 7 - "Redis"
Cohesion: 0.11
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 8 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 9 - "Store"
Cohesion: 0.15
Nodes (3): Store, WriteBehind, PlayerData

### Community 10 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 12 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 13 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 15 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 20 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 22 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

## Knowledge Gaps
- **29 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+24 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 178 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `ColumnType`, `Format`, `Database`, `Query`, `Json.java`, `MongoRepository.java`, `Row`, `Economy`, `.update`?**
  _High betweenness centrality (0.129) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _29 weakly-connected nodes found - possible missing edges._
- **Should `Vec3` be split into smaller, more focused modules?**
  _Cohesion score 0.05170998632010944 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `ColumnType`, `MongoRepository`, `Redis`, `Codec`, `Override`, `Redis.java`, `MongoRepository.java`?**
  _High betweenness centrality (0.118) - this node is a cross-community bridge._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.05443371378402107 - nodes in this community are weakly interconnected._
- **Why does `Database` connect `Database` to `ColumnType`, `Format`, `Repository`, `java.sql.Connection`, `Economy`, `Database.java`, `.update`?**
  _High betweenness centrality (0.104) - this node is a cross-community bridge._
- **Should `ColumnType` be split into smaller, more focused modules?**
  _Cohesion score 0.053551912568306013 - nodes in this community are weakly interconnected._
