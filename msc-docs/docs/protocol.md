# Core-to-agent protocol, version 1

Source of truth is the code in `msc-protocol` (`io.github.imnotstable.msc.protocol`). This page is the overview; design reasons are in [ADR 0002](adr/0002-protocol.md).

## Connection

1. The core listens on `127.0.0.1` (`CoreServer.start`) and starts the game server with two environment variables:
   - `MSC_CORE_ADDRESS`: `host:port` of the core
   - `MSC_TOKEN`: a random token for this session
2. The agent connects (`AgentClient.connectFromEnvironment`). If the variables are missing, the server was started by hand and the agent stays idle.
3. Every frame is one JSON object on one line, UTF-8, ending in `\n`, at most 16 MiB. The `type` field names the message.

## Handshake

```
agent -> core  {"type":"hello","protocolVersion":1,"token":"...","agent":{"platform":"paper","platformVersion":"26.3.build.231-stable","minecraftVersion":"26.3","operations":["server.info","world.read"]}}
core -> agent  {"type":"welcome","protocolVersion":1,"sessionId":"6f1c..."}
```

On failure the core sends `{"type":"goodbye","code":"unauthorized","reason":"bad token"}` and closes. Codes: `version_mismatch`, `unauthorized`, `bad_frame`.

## Messages after the handshake

| type | fields | notes |
| --- | --- | --- |
| request | `id`, `op`, `params` | `id` unique per sender; either side may send |
| response | `id`, `result` or `error {code, message}` | exactly one of `result` / `error` |
| event | `name`, `data` | one-way, no answer |
| ping / pong | `nonce` | pong echoes the nonce |
| goodbye | `code`, `reason` | sender closes after sending |

Error codes: `unknown_op`, `invalid_params`, `not_found`, `too_large`, `timeout`, `internal`, `unauthorized`, `version_mismatch`, `bad_frame`, `shutdown`.

## Operations (core asks, agent answers)

Defined with their Java types in `Ops`. Block states are vanilla strings such as `minecraft:oak_stairs[facing=north]`.

| op | params | result |
| --- | --- | --- |
| `server.info` | `{}` | `platform, platformVersion, minecraftVersion, worlds[], onlinePlayers, tps` |
| `server.command` | `command` | `success, output[]` |
| `world.read` | `region, includeEntities, includeSigns?` | `grid, entities[], signs[{x, y, z, front[4], back[4]?}]` (signs only when `includeSigns`, only signs with text, at most 1000; `back` omitted when blank) |
| `world.entities` | `region, type?, limit` | `entities[{type, uuid, x, y, z, name?, tags[], health?, maxHealth?}], total` (no blocks read, so no volume limit; loaded chunks only) |
| `world.heightmap` | `world, min, max` (x/z only) | `minX, minZ, sizeX, sizeZ, heights[]` (Z then X) |
| `world.write` | `grid, updatePhysics` | `editId, changed` (the only block-writing op; every build is a grid) |
| `world.undo` | `editId` | `editId, changed` |
| `pack.apply` | `url, sha1, required` | `playersSent` |

### Regions and grids

- `region` = `{"world":"world","min":{"x":0,"y":64,"z":0},"max":{"x":15,"y":70,"z":15}}`, inclusive, corners sorted on decode.
- `grid` = `{"region":..., "palette":["minecraft:air","minecraft:stone"], "blocks":[0,1,1,...]}`.
- `blocks` is ordered Y, then Z, then X (X fastest): index = `((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)`.
- In writes, `-1` leaves a block unchanged.
- `world.fill` and `world.setBlocks` were removed in [ADR 0019](adr/0019-smaller-tool-surface.md): a core and its agents come from one install, so the protocol version stays 1.
- One call covers at most 1,048,576 blocks (`Limits.MAX_REGION_VOLUME`). The core splits larger jobs.

## Events (agent tells core)

`server.ready`, `server.stopping`, `player.join`, `player.quit` (see `Events`).

## Writing an agent

```java
OpRouter router = new OpRouter()
        .on(Ops.SERVER_INFO, p -> new Ops.ServerInfo("paper", paperVersion, mcVersion, worlds, online, tps))
        .on(Ops.WORLD_WRITE, p -> write(p.grid(), p.updatePhysics()));
AgentInfo info = new AgentInfo("paper", paperVersion, mcVersion, router.operations());
AgentClient.Session session = AgentClient.connectFromEnvironment(info, router, new Connection.Listener() {}, Duration.ofSeconds(10));
```

Handlers run on virtual threads, so they may block while waiting for the server's main thread. Throw `OpException(code, message)` to answer with a specific error code; `IllegalArgumentException` maps to `invalid_params` and anything else to `internal`.

## Calling an agent from the core

```java
CoreServer server = CoreServer.start(0, token, RequestHandler.none(), listener, Duration.ofSeconds(10));
// pass server.address() and token to the game server's environment
Ops.EditResult r = session.connection().call(Ops.WORLD_WRITE, new Ops.WriteParams(grid, false), Duration.ofSeconds(30)).get();
```
