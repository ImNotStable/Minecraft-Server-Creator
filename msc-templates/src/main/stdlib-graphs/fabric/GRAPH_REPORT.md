
## Corpus Check
- 47 files · ~19,878 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 829 nodes · 1695 edges · 38 communities (13 shown, 25 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 119 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Gui
- Scheduler
- MongoRepository.java
- Store
- Redis
- Cache
- Vec3
- Database
- Format
- ColumnType
- Query
- Json.java
- Repository
- Codec
- list
- DatabaseType
- Row
- Text.java
- EventBus
- Region
- java.sql.Connection
- Cuboid
- DatabaseConfig
- Format.java
- Leaderboard
- Randoms
- Economy
- Locations
- Messages
- Cooldowns
- Config
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 76 edges
2. `Database` - 41 edges
3. `Repository` - 40 edges
4. `Shapes` - 29 edges
5. `Store` - 24 edges
6. `Gui` - 23 edges
7. `Cache` - 23 edges
8. `MongoRepository` - 22 edges
9. `ItemBuilder` - 19 edges
10. `ColumnType` - 18 edges

## Surprising Connections (you probably didn't know these)
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-fabric/io/github/imnotstable/msc/stdlib/fabric/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
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

## Communities (38 total, 25 thin omitted)

### Community 0 - "Gui"
Cohesion: 0.07
Nodes (4): Gui, GuiClick, Menu, ItemBuilder

### Community 1 - "Scheduler"
Cohesion: 0.05
Nodes (9): FabricScheduler, Entry, ParticleEffect, Stdlib, Scheduler, Task, Sequence, Scheduler.Task (+1 more)

### Community 2 - "MongoRepository.java"
Cohesion: 0.06
Nodes (6): Id, Indexed, Length, Mongo, Store<>, MongoRepository

### Community 3 - "Store"
Cohesion: 0.07
Nodes (6): PlayerData, Store, Store<>, Store<>, Store<>, WriteBehind

### Community 4 - "Redis"
Cohesion: 0.07
Nodes (8): Messenger, Messenger, Subscription, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 5 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "Database"
Cohesion: 0.13
Nodes (4): Database, PoolStats, RowMapper, SqlFunction

### Community 9 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 14 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 16 - "DatabaseType"
Cohesion: 0.15
Nodes (6): DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 20 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 21 - "Region"
Cohesion: 0.17
Nodes (3): Region, Region, Region

### Community 22 - "java.sql.Connection"
Cohesion: 0.18
Nodes (3): Migration, Change, Schema

## Knowledge Gaps
- **18 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+13 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 177 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **25 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Scheduler`, `.lerp`, `Region`, `Cuboid`, `.orient`, `Locations`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _18 weakly-connected nodes found - possible missing edges._
- **Should `Gui` be split into smaller, more focused modules?**
  _Cohesion score 0.06646825396825397 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `MongoRepository.java`, `Database`, `Query`, `Json.java`, `DatabaseType`, `Row`, `Repository.java`, `Economy`?**
  _High betweenness centrality (0.126) - this node is a cross-community bridge._
- **Should `Scheduler` be split into smaller, more focused modules?**
  _Cohesion score 0.054563492063492064 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository.java`, `Redis`, `ColumnType`, `Redis.java`, `Codec`, `Repository.java`?**
  _High betweenness centrality (0.116) - this node is a cross-community bridge._
- **Should `MongoRepository.java` be split into smaller, more focused modules?**
  _Cohesion score 0.06440677966101695 - nodes in this community are weakly interconnected._
