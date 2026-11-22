
## Corpus Check
- 60 files · ~23,336 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1017 nodes · 2146 edges · 48 communities (17 shown, 31 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 138 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Scheduler
- MongoRepository
- Codec
- net.kyori.adventure.audience.Audience
- Cache
- Vec3
- Store
- Sounds
- ColumnType
- Database
- Format
- Region
- DatabaseType
- .update
- Query
- Json.java
- net.minecraft.server.level.ServerPlayer
- Override
- Sounds
- net.minecraft.network.chat.Component
- Messages
- Row
- Area
- Hologram
- ParticleEffect
- Repository
- net.minecraft.world.item.ItemStack
- EventBus
- ItemBuilder
- Cuboid
- java.sql.Connection
- Menu
- Leaderboard
- Id.java
- Randoms
- Locations
- Config
- Migration
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 84 edges
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
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Area` --references--> `Region`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/Regions.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Region.java
- `Field` --references--> `ColumnType`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/ColumnType.java
- `Database` --references--> `DatabaseConfig`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Database.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/DatabaseConfig.java

## Import Cycles
- None detected.

## Communities (48 total, 31 thin omitted)

### Community 0 - "Scheduler"
Cohesion: 0.05
Nodes (11): Bar, FabricScheduler, Entry, Prompt, Pending, Stdlib, Scheduler, Task (+3 more)

### Community 1 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 2 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 3 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.08
Nodes (3): Text, Bar, Feedback

### Community 4 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 6 - "Store"
Cohesion: 0.13
Nodes (3): PlayerData, Store, WriteBehind

### Community 7 - "Sounds"
Cohesion: 0.07
Nodes (13): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+5 more)

### Community 8 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 9 - "Database"
Cohesion: 0.13
Nodes (3): Database, PoolStats, RowMapper

### Community 14 - "Region"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 15 - "DatabaseType"
Cohesion: 0.10
Nodes (7): DatabaseConfig, DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 19 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.17
Nodes (3): Feedback, Players, Teleports

### Community 20 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 21 - "Sounds"
Cohesion: 0.13
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 32 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 35 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 40 - "Id.java"
Cohesion: 0.47
Nodes (3): Id, Indexed, Length

## Knowledge Gaps
- **40 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+35 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 230 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **31 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Repository` connect `Repository` to `ColumnType`, `Database`, `MongoRepository.java`, `.update`, `Query`, `Json.java`, `Row`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _40 weakly-connected nodes found - possible missing edges._
- **Should `Scheduler` be split into smaller, more focused modules?**
  _Cohesion score 0.05217391304347826 - nodes in this community are weakly interconnected._
- **Why does `Vec3` connect `Vec3` to `Cuboid`, `.orient`, `Locations`, `Regions.java`, `Region`, `net.minecraft.server.level.ServerPlayer`, `Area`, `Hologram`, `ParticleEffect`?**
  _High betweenness centrality (0.104) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
- **Why does `Cache` connect `Cache` to `Gui.java`, `Override`?**
  _High betweenness centrality (0.102) - this node is a cross-community bridge._
- **Should `Codec` be split into smaller, more focused modules?**
  _Cohesion score 0.06561085972850679 - nodes in this community are weakly interconnected._
