
## Corpus Check
- 66 files · ~29,538 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1165 nodes · 2552 edges · 56 communities (19 shown, 37 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 162 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.kyori.adventure.audience.Audience
- Permissions
- MongoRepository.java
- Redis
- DatabaseType
- Datapack
- Cache
- Vec3
- MinestomScheduler
- Scheduler
- Team
- Database
- Format
- Query
- Codec
- Override
- LootTable
- Json.java
- Gui.java
- net.minestom.server.item.ItemStack
- net.minestom.server.coordinate.Point
- Dialogue
- Hologram
- Repository
- Row
- Area
- ItemBuilder
- Game
- DatabaseConfig
- java.sql.Connection
- java.util.random.RandomGenerator
- .update
- Listener
- Cuboid
- Text.java
- .end
- net.minestom.server.entity.Player
- EventBus
- Leaderboard
- Stdlib
- Override
- net.minestom.server.coordinate.Pos
- Randoms
- Builder
- Cooldowns
- .apply
- Arena
- Phase
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 85 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 30 edges
6. `Shapes` - 29 edges
7. `Datapack` - 28 edges
8. `Scheduler` - 27 edges
9. `Store` - 26 edges
10. `Cache` - 22 edges

## Surprising Connections (you probably didn't know these)
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `MinestomScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Stdlib` --references--> `MinestomScheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Stdlib.java → src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Area` --references--> `Region`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Regions.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Region.java

## Import Cycles
- None detected.

## Communities (56 total, 37 thin omitted)

### Community 0 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.05
Nodes (17): Messages, Bar, Feedback, Sounds, CLICK, DEFEAT, FAIL, GO (+9 more)

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 2 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 3 - "Redis"
Cohesion: 0.08
Nodes (8): Messenger, Messenger, Subscription, Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 4 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 8 - "MinestomScheduler"
Cohesion: 0.12
Nodes (6): Boards, Shown, MinestomScheduler, Task, Pending, Prompt

### Community 9 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 13 - "Database"
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

### Community 21 - "Gui.java"
Cohesion: 0.19
Nodes (3): Gui, GuiClick, Menu

### Community 25 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 33 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 40 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 42 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 54 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 254 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **37 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Game` connect `Game` to `Listener`, `.end`, `Scheduler`, `Team`, `.join`, `Repository.java`, `Arena`, `Phase`?**
  _High betweenness centrality (0.127) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.05030181086519115 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository.java`, `Redis`, `DatabaseType`, `map`, `Codec`, `Override`, `Repository.java`?**
  _High betweenness centrality (0.117) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06241519674355495 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `java.util.random.RandomGenerator`, `Cuboid`, `Team`, `map`, `.orient`, `.join`, `Override`, `net.minestom.server.coordinate.Pos`, `Builder`, `Repository.java`, `Arena`, `net.minestom.server.coordinate.Point`?**
  _High betweenness centrality (0.096) - this node is a cross-community bridge._
- **Should `MongoRepository.java` be split into smaller, more focused modules?**
  _Cohesion score 0.06240084611316764 - nodes in this community are weakly interconnected._
