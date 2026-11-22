
## Corpus Check
- 61 files · ~23,569 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1036 nodes · 2195 edges · 50 communities (19 shown, 31 thin omitted)
- Extraction: 94% EXTRACTED · 6% INFERRED · 0% AMBIGUOUS · INFERRED: 137 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.minecraft.world.item.ItemStack
- Scheduler
- MongoRepository
- Cache
- Format
- Store
- Vec3
- Repository
- Database
- Redis
- ColumnType
- DatabaseType
- Json.java
- Region
- Codec
- Query
- Sounds
- net.minecraft.server.level.ServerPlayer
- Override
- Hooks
- net.kyori.adventure.audience.Audience
- Sounds
- Text.java
- Row
- Hologram
- ParticleEffect
- EventBus
- Regions
- Messages
- Cuboid
- java.sql.Connection
- Bar
- Definition
- Leaderboard
- Randoms
- Cooldowns
- Players
- Config
- Migration
- Locations
- RateLimiter

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
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Hooks.java → src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java
- `Area` --references--> `Region`  [EXTRACTED]
  src/msc-stdlib-forge/io/github/imnotstable/msc/stdlib/forge/Regions.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Region.java
- `Field` --references--> `ColumnType`  [EXTRACTED]
  src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/db/ColumnType.java

## Import Cycles
- None detected.

## Communities (50 total, 31 thin omitted)

### Community 0 - "net.minecraft.world.item.ItemStack"
Cohesion: 0.06
Nodes (4): Gui, GuiClick, Menu, ItemBuilder

### Community 1 - "Scheduler"
Cohesion: 0.06
Nodes (10): Bar, Prompt, Pending, TickScheduler, Entry, Scheduler, Task, Sequence (+2 more)

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 4 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 5 - "Store"
Cohesion: 0.11
Nodes (3): PlayerData, Store, WriteBehind

### Community 7 - "Repository"
Cohesion: 0.13
Nodes (4): Repository, Field, Economy, Account

### Community 8 - "Database"
Cohesion: 0.10
Nodes (3): Database, PoolStats, RowMapper

### Community 9 - "Redis"
Cohesion: 0.11
Nodes (5): Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 10 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 11 - "DatabaseType"
Cohesion: 0.10
Nodes (7): DatabaseConfig, DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 13 - "Region"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 14 - "Codec"
Cohesion: 0.15
Nodes (3): Codec, Codec<>, FileStore

### Community 17 - "Sounds"
Cohesion: 0.10
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 18 - "net.minecraft.server.level.ServerPlayer"
Cohesion: 0.16
Nodes (3): Feedback, Area, Teleports

### Community 19 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 23 - "Sounds"
Cohesion: 0.14
Nodes (12): Sounds, CLICK, DEFEAT, FAIL, GO, NOTIFY, OPEN, PICKUP (+4 more)

### Community 30 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 35 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

## Knowledge Gaps
- **40 isolated node(s):** `CLICK`, `SUCCESS`, `FAIL`, `TICK`, `GO` (+35 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 237 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **31 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Cuboid`, `Definition`, `.orient`, `Region`, `Locations`, `Redis.java`, `net.minecraft.server.level.ServerPlayer`, `Hologram`, `ParticleEffect`, `Regions`?**
  _High betweenness centrality (0.130) - this node is a cross-community bridge._
- **What connects `CLICK`, `SUCCESS`, `FAIL` to the rest of the system?**
  _40 weakly-connected nodes found - possible missing edges._
- **Should `net.minecraft.world.item.ItemStack` be split into smaller, more focused modules?**
  _Cohesion score 0.059673659673659674 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Store` to `MongoRepository`, `Redis`, `ColumnType`, `Codec`, `Redis.java`, `Override`, `MongoRepository.java`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Should `Scheduler` be split into smaller, more focused modules?**
  _Cohesion score 0.057539682539682536 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `Format`, `Database`, `ColumnType`, `Json.java`, `Query`, `Row`, `Repository.java`?**
  _High betweenness centrality (0.082) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
