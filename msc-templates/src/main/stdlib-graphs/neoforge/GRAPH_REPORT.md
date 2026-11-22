
## Corpus Check
- 61 files · ~23,597 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1039 nodes · 2197 edges · 51 communities (18 shown, 33 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 137 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Repository
- Store
- MongoRepository
- Cache
- Format
- Vec3
- ParticleEffect
- Database
- Scheduler
- Redis
- Gui
- ColumnType
- net.minecraft.server.level.ServerPlayer
- Json.java
- Sounds
- Area
- Override
- net.minecraft.network.chat.Component
- Bar
- Sounds
- Text.java
- DatabaseType
- TickScheduler
- net.minecraft.world.item.ItemStack
- EventBus
- Messages
- Hooks
- Cuboid
- java.sql.Connection
- java.util.random.RandomGenerator
- net.kyori.adventure.audience.Audience
- Bar
- Leaderboard
- DatabaseConfig
- Randoms
- Prompt
- .apply
- Cooldowns
- Locations
- Config
- RateLimiter
- WeightedRandom

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 85 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Shapes` - 29 edges
5. `Store` - 24 edges
6. `Gui` - 23 edges
7. `Cache` - 23 edges
8. `MongoRepository` - 22 edges
9. `Scheduler` - 22 edges
10. `Format` - 20 edges

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

## Communities (51 total, 33 thin omitted)

### Community 0 - "Repository"
Cohesion: 0.06
Nodes (6): Repository, Field, Query, Row, Economy, Account

### Community 1 - "Store"
Cohesion: 0.06
Nodes (6): Codec, Codec<>, FileStore, Store, WriteBehind, PlayerData

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 4 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 7 - "Database"
Cohesion: 0.11
Nodes (3): Database, PoolStats, RowMapper

### Community 8 - "Scheduler"
Cohesion: 0.13
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 9 - "Redis"
Cohesion: 0.12
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 10 - "Gui"
Cohesion: 0.13
Nodes (3): Gui, GuiClick, Menu

### Community 12 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 13 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.15
Nodes (3): Feedback, Players, Teleports

### Community 16 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 18 - "Area"
Cohesion: 0.20
Nodes (3): Region, Regions, Area

### Community 19 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 22 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 24 - "DatabaseType"
Cohesion: 0.16
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 29 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 35 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

## Knowledge Gaps
- **40 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+35 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 241 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Cuboid`, `.orient`, `java.util.random.RandomGenerator`, `ParticleEffect`, `Scheduler`, `Gui.java`, `net.minecraft.server.level.ServerPlayer`, `Locations`, `Redis.java`, `Area`?**
  _High betweenness centrality (0.121) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _40 weakly-connected nodes found - possible missing edges._
- **Should `Repository` be split into smaller, more focused modules?**
  _Cohesion score 0.06025039123630673 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `Redis.java`, `Override`, `ColumnType`, `MongoRepository.java`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `Store` be split into smaller, more focused modules?**
  _Cohesion score 0.061016949152542375 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `Format`, `Database`, `Json.java`, `MongoRepository.java`, `DatabaseType`?**
  _High betweenness centrality (0.089) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06458635703918723 - nodes in this community are weakly interconnected._
