
## Corpus Check
- 58 files · ~27,634 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1061 nodes · 2252 edges · 49 communities (22 shown, 27 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 164 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.kyori.adventure.audience.Audience
- Permissions
- CommandTree
- MongoRepository
- DatabaseType
- Datapack
- Format
- Cache
- VelocityScheduler
- Vec3
- Redis
- Scheduler
- Repository.java
- Database
- Query
- Codec
- Override
- LootTable
- list
- Sounds
- Dialogue
- Repository
- Row
- Listener
- Game
- Cuboid
- Region
- DatabaseConfig
- java.sql.Connection
- .update
- Arena
- .end
- Team
- EventBus
- Leaderboard
- Builder
- Randoms
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
10. `CommandTree` - 22 edges

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

## Communities (49 total, 27 thin omitted)

### Community 0 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.05
Nodes (5): Messages, Text, Bar, Feedback, Lang

### Community 1 - "Permissions"
Cohesion: 0.07
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 2 - "CommandTree"
Cohesion: 0.06
Nodes (8): CommandTree, Ctx, Fail, Source, Cooldowns, Cmd, SimpleCommand, CommandTree.Source

### Community 3 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 7 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 8 - "VelocityScheduler"
Cohesion: 0.11
Nodes (4): Servers, Stdlib, VelocityScheduler, Task

### Community 10 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 11 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 14 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 16 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 17 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 18 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 22 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 23 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 29 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 31 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 35 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 37 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 46 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 230 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **27 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Game` to `Arena`, `.addScore`, `.end`, `Team`, `Builder`, `Game.java`, `Scheduler`, `Phase`, `Listener`?**
  _High betweenness centrality (0.132) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.054274084124830396 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `Arena`, `.addScore`, `.orient`, `Game.java`, `.lerp`, `map`, `Listener`, `Cuboid`, `Region`?**
  _High betweenness centrality (0.129) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06746031746031746 - nodes in this community are weakly interconnected._
- **Why does `Scheduler` connect `Scheduler` to `net.kyori.adventure.audience.Audience`, `Arena`, `VelocityScheduler`, `Builder`, `Game.java`, `MongoRepository.java`, `list`, `Game`?**
  _High betweenness centrality (0.126) - this node is a cross-community bridge._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.06233766233766234 - nodes in this community are weakly interconnected._
