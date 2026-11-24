
## Corpus Check
- 58 files · ~27,423 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1073 nodes · 2265 edges · 49 communities (21 shown, 28 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 167 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- CommandTree
- MongoRepository
- ColumnType
- Datapack
- Format
- Cache
- VelocityScheduler
- Vec3
- Repository
- Database
- Redis
- Redis.java
- Scheduler
- Query
- Codec
- Team
- LootTable
- Bar
- Text.java
- Override
- list
- Row
- Listener
- Dialogue
- Messages
- EventBus
- Builder
- Game
- Region
- Cuboid
- java.sql.Connection
- .end
- Leaderboard
- DatabaseConfig
- net.kyori.adventure.audience.Audience
- Randoms
- .apply
- Sounds.java
- RateLimiter
- Phase

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 76 edges
2. `Game` - 45 edges
3. `Database` - 43 edges
4. `Repository` - 40 edges
5. `Datapack` - 29 edges
6. `Shapes` - 29 edges
7. `Permissions` - 29 edges
8. `Store` - 27 edges
9. `CommandTree` - 25 edges
10. `Cache` - 23 edges

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

## Communities (49 total, 28 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Permissions, Group, GroupEdit, User, PlayerData

### Community 1 - "CommandTree"
Cohesion: 0.06
Nodes (8): CommandTree, Ctx, Fail, Source, Cooldowns, Cmd, SimpleCommand, CommandTree.Source

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "ColumnType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 4 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 5 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "VelocityScheduler"
Cohesion: 0.11
Nodes (4): Servers, Stdlib, VelocityScheduler, Task

### Community 9 - "Repository"
Cohesion: 0.14
Nodes (4): Repository, Field, Economy, Account

### Community 10 - "Database"
Cohesion: 0.11
Nodes (3): Database, PoolStats, RowMapper

### Community 11 - "Redis"
Cohesion: 0.11
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 13 - "Scheduler"
Cohesion: 0.13
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 15 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 19 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 23 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 28 - "Dialogue"
Cohesion: 0.24
Nodes (4): Dialogue, Builder, Choice, Page

### Community 30 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 33 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 35 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 36 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 47 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 227 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **28 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Game` to `.end`, `.addScore`, `Scheduler`, `Phase`, `Team`, `CommandTree.java`, `Listener`, `Builder`?**
  _High betweenness centrality (0.125) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06299603174603174 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `ColumnType`, `Format`, `Database`, `Redis.java`, `Query`, `MongoRepository.java`, `Row`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.05649717514124294 - nodes in this community are weakly interconnected._
- **Why does `Database` connect `Database` to `Permissions`, `java.sql.Connection`, `ColumnType`, `Format`, `DatabaseConfig`, `Repository`, `Database.java`, `.apply`, `map`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
