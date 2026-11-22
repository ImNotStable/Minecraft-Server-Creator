
## Corpus Check
- 61 files · ~23,561 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1037 nodes · 2192 edges · 44 communities (18 shown, 26 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 137 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Repository
- net.minecraft.world.item.ItemStack
- net.minecraft.server.level.ServerPlayer
- Json.java
- MongoRepository
- Cache
- Store
- Vec3
- Redis
- Format
- ColumnType
- Database
- Region
- Codec
- Sounds
- Override
- TickScheduler
- net.kyori.adventure.audience.Audience
- Text.java
- Sounds
- DatabaseType
- Bar
- Hologram
- ParticleEffect
- EventBus
- Stdlib.java
- java.sql.Connection
- Cuboid
- Scheduler
- Leaderboard
- Bar
- DatabaseConfig
- Id.java
- Randoms
- Prompt
- Sequence
- Hooks
- .apply
- Cooldowns
- .query
- RateLimiter
- Scheduler.Task

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
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Hooks.java → src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Pending` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Prompt.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java

## Import Cycles
- None detected.

## Communities (44 total, 26 thin omitted)

### Community 1 - "Repository"
Cohesion: 0.06
Nodes (6): Repository, Field, Query, Row, Economy, Account

### Community 2 - "net.minecraft.world.item.ItemStack"
Cohesion: 0.06
Nodes (4): Gui, GuiClick, Menu, ItemBuilder

### Community 3 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.06
Nodes (8): CustomItems, Definition, Feedback, Locations, Players, Regions, Area, Teleports

### Community 4 - "Json.java"
Cohesion: 0.05
Nodes (6): Lang, Config, Validated, Messages, Json, TypeAdapter<>

### Community 5 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "Store"
Cohesion: 0.10
Nodes (3): PlayerData, Store, WriteBehind

### Community 9 - "Redis"
Cohesion: 0.11
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 11 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 13 - "Region"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 14 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 15 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 16 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 20 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 21 - "DatabaseType"
Cohesion: 0.15
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 25 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 27 - "java.sql.Connection"
Cohesion: 0.23
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 34 - "Id.java"
Cohesion: 0.47
Nodes (3): Id, Indexed, Length

## Knowledge Gaps
- **40 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+35 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 239 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **26 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `list`, `net.minecraft.server.level.ServerPlayer`, `Region`, `Hologram`, `ParticleEffect`, `Cuboid`, `.orient`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _40 weakly-connected nodes found - possible missing edges._
- **Should `list` be split into smaller, more focused modules?**
  _Cohesion score 0.055600106923282544 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `list`, `MongoRepository`, `Redis`, `ColumnType`, `Codec`, `Override`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Should `Repository` be split into smaller, more focused modules?**
  _Cohesion score 0.06116700201207243 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `list`, `DatabaseType`, `Json.java`, `Database`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Should `net.minecraft.world.item.ItemStack` be split into smaller, more focused modules?**
  _Cohesion score 0.059673659673659674 - nodes in this community are weakly interconnected._
