
## Corpus Check
- 69 files · ~31,280 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1232 nodes · 2728 edges · 59 communities (21 shown, 38 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 182 edges (avg confidence: 0.85)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- net.kyori.adventure.audience.Audience
- Permissions
- Gui
- MongoRepository
- MinestomScheduler
- DatabaseType
- Datapack
- Cache
- Vec3
- Redis
- Scheduler
- Gui.java
- Database
- Format
- Redis.java
- Query
- Hologram
- java.util.random.RandomGenerator
- net.minestom.server.coordinate.Point
- Codec
- Override
- LootTable
- Game
- Team
- Dialogue
- Repository
- net.minestom.server.entity.Player
- Row
- CommandTree
- java.sql.Connection
- Cuboid
- Arena
- .update
- Cmd
- EventBus
- .end
- Leaderboard
- DatabaseConfig
- Listener
- .dispatch
- Players.java
- Durations
- Builder
- Npcs
- Randoms
- .Fail
- Cooldowns
- Phase
- RateLimiter

## God Nodes (most connected - your core abstractions)
1. `Vec3` - 85 edges
2. `Game` - 46 edges
3. `Database` - 41 edges
4. `Repository` - 41 edges
5. `Permissions` - 33 edges
6. `Shapes` - 29 edges
7. `Datapack` - 28 edges
8. `Scheduler` - 27 edges
9. `Store` - 26 edges
10. `Cache` - 22 edges

## Surprising Connections (you probably didn't know these)
- `Cmd` --references--> `Permissions`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Cmd.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/perm/Permissions.java
- `Definition` --references--> `Cooldowns`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/CustomItems.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/time/Cooldowns.java
- `MinestomScheduler` --implements--> `Scheduler`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/MinestomScheduler.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/sched/Scheduler.java
- `Npcs` --references--> `Dialogue`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/Npcs.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/npc/Dialogue.java
- `ParticleEffect` --references--> `Vec3`  [EXTRACTED]
  src/msc-stdlib-minestom/io/github/imnotstable/msc/stdlib/minestom/ParticleEffect.java → src/msc-stdlib/io/github/imnotstable/msc/stdlib/geom/Vec3.java

## Import Cycles
- None detected.

## Communities (59 total, 38 thin omitted)

### Community 0 - "net.kyori.adventure.audience.Audience"
Cohesion: 0.05
Nodes (17): Messages, Bar, Feedback, Sounds, CLICK, DEFEAT, FAIL, GO (+9 more)

### Community 1 - "Permissions"
Cohesion: 0.06
Nodes (7): Store, WriteBehind, Group, GroupEdit, Permissions, User, PlayerData

### Community 2 - "Gui"
Cohesion: 0.07
Nodes (6): CustomItems, Definition, Gui, GuiClick, Menu, ItemBuilder

### Community 3 - "MongoRepository"
Cohesion: 0.06
Nodes (6): Messenger, Messenger, Subscription, Mongo, Store<>, MongoRepository

### Community 4 - "MinestomScheduler"
Cohesion: 0.09
Nodes (7): Boards, Shown, MinestomScheduler, Task, Pending, Prompt, Stdlib

### Community 5 - "DatabaseType"
Cohesion: 0.07
Nodes (20): ColumnType, BOOLEAN, BYTES, DOUBLE, INSTANT, INT, LONG, STRING (+12 more)

### Community 6 - "Datapack"
Cohesion: 0.10
Nodes (3): Config, Validated, Datapack

### Community 7 - "Cache"
Cohesion: 0.10
Nodes (10): Builder, Cache, Cause, EXPIRED, INVALIDATED, REPLACED, SIZE, Entry (+2 more)

### Community 9 - "Redis"
Cohesion: 0.12
Nodes (5): Lock, Redis, JedisPubSub, Messenger, Store<>

### Community 10 - "Scheduler"
Cohesion: 0.12
Nodes (5): Scheduler, Task, Sequence, Scheduler.Task, Step

### Community 12 - "Database"
Cohesion: 0.12
Nodes (3): Database, PoolStats, RowMapper

### Community 13 - "Format"
Cohesion: 0.12
Nodes (4): Id, Indexed, Length, Format

### Community 17 - "java.util.random.RandomGenerator"
Cohesion: 0.13
Nodes (4): Region, Region, Region, WeightedRandom

### Community 20 - "Codec"
Cohesion: 0.17
Nodes (3): Codec, Codec<>, FileStore

### Community 21 - "Override"
Cohesion: 0.15
Nodes (3): Store<>, Store<>, Store<>

### Community 22 - "LootTable"
Cohesion: 0.19
Nodes (7): Builder, Context, Drop, Entry, LootTable, Pool, PoolBuilder

### Community 26 - "Dialogue"
Cohesion: 0.22
Nodes (4): Builder, Choice, Dialogue, Page

### Community 32 - "java.sql.Connection"
Cohesion: 0.21
Nodes (3): DatabaseException, SqlFunction, Schema

### Community 41 - "EventBus"
Cohesion: 0.21
Nodes (5): Cancellable, CancellableEvent, EventBus, Handler, Subscription

### Community 42 - ".end"
Cohesion: 0.21
Nodes (7): Reason, LAST_STANDING, SCORE, STOPPED, TIME, Result, Winner

### Community 56 - "Phase"
Cohesion: 0.33
Nodes (5): Phase, ENDING, RUNNING, STARTING, WAITING

## Knowledge Gaps
- **37 isolated node(s):** `EXPIRED`, `SIZE`, `REPLACED`, `INVALIDATED`, `STRING` (+32 more)
  These have ≤1 connection - possible missing edges. (Counts symbols only; 270 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **38 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Vec3` connect `Vec3` to `Cuboid`, `Regions.java`, `Arena`, `.orient`, `.join`, `Hologram`, `java.util.random.RandomGenerator`, `net.minestom.server.coordinate.Point`, `map`, `Team`?**
  _High betweenness centrality (0.124) - this node is a cross-community bridge._
- **What connects `EXPIRED`, `SIZE`, `REPLACED` to the rest of the system?**
  _37 weakly-connected nodes found - possible missing edges._
- **Should `net.kyori.adventure.audience.Audience` be split into smaller, more focused modules?**
  _Cohesion score 0.052917232021709636 - nodes in this community are weakly interconnected._
- **Why does `Repository` connect `Repository` to `DatabaseType`, `.update`, `Database`, `Format`, `Redis.java`, `Query`, `MongoRepository.java`, `Row`?**
  _High betweenness centrality (0.105) - this node is a cross-community bridge._
- **Should `Permissions` be split into smaller, more focused modules?**
  _Cohesion score 0.06241519674355495 - nodes in this community are weakly interconnected._
- **Why does `Game` connect `Game` to `Regions.java`, `Arena`, `.end`, `Scheduler`, `Listener`, `.join`, `Builder`, `Phase`, `Team`?**
  _High betweenness centrality (0.080) - this node is a cross-community bridge._
- **Should `Gui` be split into smaller, more focused modules?**
  _Cohesion score 0.06558441558441558 - nodes in this community are weakly interconnected._
