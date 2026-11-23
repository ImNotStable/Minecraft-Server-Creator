
## Corpus Check
- 69 files · ~30,621 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1227 nodes · 2657 edges · 62 communities (24 shown, 38 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 171 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Permissions
- net.kyori.adventure.audience.Audience
- Stdlib.java
- MongoRepository
- DatabaseType
- Datapack
- Cache
- Repository
- Vec3
- Database
- Redis
- Query
- Team
- java.util.random.RandomGenerator
- Json.java
- net.minecraft.world.item.ItemStack
- Codec
- Override
- LootTable
- Sounds
- Game
- Dialogue
- TickScheduler
- net.minecraft.server.level.ServerPlayer
- Row
- Scheduler
- ItemBuilder
- Listener
- Format
- Cuboid
- ParticleEffect
- java.sql.Connection
- Sounds
- Menu
- Gui
- Arena
- .end
- EventBus
- Messages
- Leaderboard
- Format.java
- DatabaseConfig
- net.minecraft.network.chat.Component
- Id.java
- Builder
- Randoms
- Task
- Cooldowns
- .apply
- Phase
- RateLimiter
- Prompt
- Scheduler.Task

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 94 edges
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
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Hooks.java → src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Pending` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Prompt.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java

## Import Cycles
- None detected.

## Communities (62 total, 38 thin omitted)

### Community 0 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 1 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.05
Nodes (4): Text, Bar, Feedback, Hologram

### Community 2 - "Stdlib.java"
Cohesion: 0.07
Nodes (4): Hooks, Area, Regions, Stdlib

### Community 3 - "MongoRepository"
Cohesion: 0.07
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "DatabaseType"
Cohesion: 0.08
Nodes (18): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+10 more)

### Community 5 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "Repository"
Cohesion: 0.14
Nodes (4): Field, Repository, Account, Economy

### Community 9 - "Database"
Cohesion: 0.10
Nodes (3): Database, PoolStats, RowMapper

### Community 10 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 14 - "java.util.random.RandomGenerator"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 16 - "net.minecraft.world.item.ItemStack"
Cohesion: 0.17
Nodes (3): CustomItems, Definition, Locations

### Community 17 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 18 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 19 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 22 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 25 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 29 - "Scheduler"
Cohesion: 0.20
Nodes (3): Scheduler, Sequence, Step

### Community 35 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 37 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 42 - ".end"
Cohesion: 0.20
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 45 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 51 - "Id.java"
Cohesion: 0.47
Nodes (3): Id, Indexed, Length

### Community 57 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **48 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+43 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 283 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **38 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Cuboid`, `net.kyori.adventure.audience.Audience`, `Stdlib.java`, `.orient`, `ParticleEffect`, `Arena`, `linkedhashmap`, `Team`, `list`, `java.util.random.RandomGenerator`, `net.minecraft.world.item.ItemStack`, `Task`, `map`, `Listener`?**
  _High betweenness centrality (0.138) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _48 weakly-connected nodes found - possible missing edges._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.058385093167701865 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `DatabaseType`, `Repository.java`, `Database`, `Query`, `Json.java`, `Format.java`, `Row`?**
  _High betweenness centrality (0.101) - this node is a cross-community bridge._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.05144230769230769 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Arena`, `.end`, `Team`, `list`, `Builder`, `Task`, `Phase`, `Scheduler`, `Listener`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Should `Stdlib.java` be split into smaller, more focused modules?**
  _Cohesion score 0.06531204644412192 - nodes in this community are weakly interconnected._
