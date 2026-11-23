
## Corpus Check
- 56 files · ~26,400 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1008 nodes · 2134 edges · 50 communities (21 shown, 29 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 153 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- net.kyori.adventure.audience.Audience
- MongoRepository
- DatabaseType
- Datapack
- VelocityScheduler
- Cache
- Vec3
- Redis
- Scheduler
- Database
- Format
- Query
- Codec
- LootTable
- Override
- Game
- Team
- Sounds
- Json.java
- Dialogue
- list
- Repository
- Row
- Region
- DatabaseConfig
- java.sql.Connection
- .update
- Arena
- Cuboid
- EventBus
- .end
- Leaderboard
- Listener
- Id.java
- Builder
- Randoms
- Cooldowns
- .apply
- Phase
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 75 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 30 edges
6. `Shapes` - 29 edges
7. `Datapack` - 28 edges
8. `Store` - 26 edges
9. `Cache` - 22 edges
10. `Cuboid` - 22 edges

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

## Communities (50 total, 29 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.07
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 1 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.06
Nodes (5): Messages, Text, Bar, Feedback, Lang

### Community 2 - "MongoRepository"
Cohesion: 0.07
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 4 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 5 - "VelocityScheduler"
Cohesion: 0.11
Nodes (4): Servers, Stdlib, VelocityScheduler, Task

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 8 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 9 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 10 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 15 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 16 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 18 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 21 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 23 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 28 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 30 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 34 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 35 - ".end"
Cohesion: 0.21
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 38 - "Id.java"
Cohesion: 0.47
Nodes (3): Id, Indexed, Length

### Community 48 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 219 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **29 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `DatabaseType`, `duration`, `Database`, `Query`, `MongoRepository.java`, `Json.java`, `Row`, `.update`?**
  _High betweenness centrality (0.109) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06746031746031746 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository`, `DatabaseType`, `Redis`, `MongoRepository.java`, `Codec`, `Override`, `map`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.05888376856118792 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Arena`, `.end`, `Listener`, `.join`, `Scheduler`, `Builder`, `Phase`, `Database.java`, `Team`?**
  _High betweenness centrality (0.091) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.0660377358490566 - nodes in this community are weakly interconnected._
