
## Corpus Check
- 59 files · ~27,937 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1109 nodes · 2343 edges · 51 communities (21 shown, 30 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 169 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- CommandTree
- MongoRepository
- Codec
- ColumnType
- Datapack
- BungeeScheduler
- Cache
- Repository
- Vec3
- Scheduler
- Database
- list
- Format
- Query
- Team
- Text
- LootTable
- Json.java
- Override
- Game
- Sounds
- net.kyori.adventure.audience.Audience
- Row
- Listener
- Dialogue
- EventBus
- Region
- DatabaseConfig
- Builder
- Cuboid
- Bar
- java.sql.Connection
- .end
- Messages
- Leaderboard
- Id.java
- Randoms
- Builder
- .apply
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
8. `CommandTree` - 28 edges
9. `Store` - 27 edges
10. `Cache` - 23 edges

## Surprising Connections (you probably didn't know these)
- `BungeeScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-bungeecord/io/github/imnotstable/msc/stdlib/bungeecord/BungeeScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
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

## Communities (51 total, 30 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.05
Nodes (8): PlayerData, Events, Store, WriteBehind, Permissions, Group, GroupEdit, User

### Community 1 - "CommandTree"
Cohesion: 0.06
Nodes (8): Cmd, CommandTree.Source, TreeCommand, CommandTree, Ctx, Fail, Source, Cooldowns

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 4 - "ColumnType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "BungeeScheduler"
Cohesion: 0.11
Nodes (4): BungeeScheduler, Task, Servers, Stdlib

### Community 7 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 8 - "Repository"
Cohesion: 0.13
Nodes (4): Repository, Field, Economy, Account

### Community 10 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 11 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 18 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 21 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 24 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 28 - "Dialogue"
Cohesion: 0.24
Nodes (4): Dialogue, Builder, Choice, Page

### Community 29 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 30 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 35 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 36 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 41 - "Id.java"
Cohesion: 0.47
Nodes (3): Id, Indexed, Length

### Community 50 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 239 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **30 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `ColumnType`, `CommandTree.java`, `Database`, `Query`, `Json.java`, `Repository.java`, `Row`?**
  _High betweenness centrality (0.121) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05432595573440644 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Builder`, `.end`, `Scheduler`, `Builder`, `Team`, `Game.java`, `Phase`, `Listener`?**
  _High betweenness centrality (0.112) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.055288461538461536 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository`, `Codec`, `ColumnType`, `MongoRepository.java`, `list`, `map`, `Override`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
