
## Corpus Check
- 74 files · ~33,090 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1342 nodes · 2953 edges · 63 communities (22 shown, 41 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 200 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- CommandTree
- Permissions
- MongoRepository
- Bar
- Codec
- Datapack
- Cache
- Format
- Vec3
- Dialogue
- Repository
- Database
- ColumnType
- Hooks
- Redis.java
- Query
- Team
- java.util.random.RandomGenerator
- DatabaseType
- LootTable
- net.minecraft.world.item.ItemStack
- TickScheduler
- Override
- Listener
- Game
- net.minecraft.network.chat.Component
- Area
- Row
- net.minecraft.server.level.ServerPlayer
- Bar
- Hologram
- ParticleEffect
- EventBus
- Builder
- Cuboid
- Messages
- java.sql.Connection
- Menu
- Scheduler
- Leaderboard
- neoforge/Feedback.java
- Sidebar
- Randoms
- .end
- Sequence
- Builder
- neoforge/Sounds.java
- Locations
- Migration
- RateLimiter
- Prompt
- Phase
- Scheduler.Task
- .warmup

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 99 edges
2. `Game` - 45 edges
3. `Database` - 43 edges
4. `Repository` - 40 edges
5. `Permissions` - 32 edges
6. `Datapack` - 31 edges
7. `CommandTree` - 29 edges
8. `Shapes` - 29 edges
9. `Store` - 27 edges
10. `Scheduler` - 27 edges

## Surprising Connections (you probably didn't know these)
- `Bar` --references--> `Task`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Bar.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `Hooks` --references--> `TickScheduler`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/Hooks.java → src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/TickScheduler.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-neoforge/io/github/imnotstable/msc/stdlib/neoforge/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (63 total, 41 thin omitted)

### Community 0 - "CommandTree"
Cohesion: 0.05
Nodes (7): CommandTree, Ctx, Fail, Source, Cooldowns, Cmd, CommandTree.Source

### Community 1 - "Permissions"
Cohesion: 0.05
Nodes (7): Store, WriteBehind, Permissions, Group, GroupEdit, User, PlayerData

### Community 2 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 3 - "Bar"
Cohesion: 0.07
Nodes (4): Text, Bar, Feedback, Sounds

### Community 4 - "Codec"
Cohesion: 0.07
Nodes (8): Codec, Codec<>, FileStore, Redis, Lock, JedisPubSub, Messenger, Store<>

### Community 5 - "Datapack"
Cohesion: 0.08
Nodes (4): Config, Validated, Datapack, Recipes

### Community 6 - "Cache"
Cohesion: 0.10
Nodes (10): Cache, Builder, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 7 - "Format"
Cohesion: 0.08
Nodes (5): Id, Indexed, Length, Durations, Format

### Community 9 - "Dialogue"
Cohesion: 0.13
Nodes (5): Dialogue, Builder, Choice, Page, Npcs

### Community 10 - "Repository"
Cohesion: 0.14
Nodes (4): Repository, Field, Economy, Account

### Community 11 - "Database"
Cohesion: 0.11
Nodes (3): Database, PoolStats, RowMapper

### Community 12 - "ColumnType"
Cohesion: 0.13
Nodes (12): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+4 more)

### Community 13 - "Hooks"
Cohesion: 0.12
Nodes (3): CustomItems, Hooks, Stdlib

### Community 19 - "java.util.random.RandomGenerator"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 20 - "DatabaseType"
Cohesion: 0.11
Nodes (7): DatabaseConfig, DatabaseType, H2, MARIADB, MYSQL, POSTGRESQL, SQLITE

### Community 21 - "LootTable"
Cohesion: 0.18
Nodes (7): LootTable, Builder, Context, Drop, Entry, Pool, PoolBuilder

### Community 22 - "net.minecraft.world.item.ItemStack"
Cohesion: 0.18
Nodes (3): Gui, GuiClick, Nav

### Community 25 - "Override"
Cohesion: 0.16
Nodes (3): Store<>, Store<>, Store<>

### Community 38 - "EventBus"
Cohesion: 0.18
Nodes (5): EventBus, Cancellable, CancellableEvent, Handler, Subscription

### Community 44 - "java.sql.Connection"
Cohesion: 0.25
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 52 - ".end"
Cohesion: 0.27
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 60 - "Phase"
Cohesion: 0.40
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **26 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+21 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 296 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **41 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Hologram`, `ParticleEffect`, `Builder`, `Cuboid`, `.orient`, `Dialogue`, `Team`, `Datapack.java`, `java.util.random.RandomGenerator`, `Locations`, `Listener`, `map`, `Gui.java`, `.warmup`, `Area`?**
  _High betweenness centrality (0.158) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _26 weakly-connected nodes found - possible missing edges._
- **Should `CommandTree` be split into smaller, more focused modules?**
  _Cohesion score 0.05311871227364185 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Builder`, `Phase`, `Scheduler`, `Team`, `.end`, `Builder`, `Listener`, `Gui.java`?**
  _High betweenness centrality (0.095) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.05432595573440644 - nodes in this community are weakly interconnected._
- **Why does `Store` connect `Permissions` to `MongoRepository`, `Codec`, `ColumnType`, `Redis.java`, `MongoRepository.java`, `Override`, `map`, `Gui.java`?**
  _High betweenness centrality (0.078) - this node is a cross-community bridge._
- **Should `MongoRepository` be split into smaller, more focused modules?**
  _Cohesion score 0.06429070580013976 - nodes in this community are weakly interconnected._
