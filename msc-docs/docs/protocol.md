# Core-to-agent protocol, version 1

Source of truth is the code in `msc-protocol` (`io.github.imnotstable.msc.protocol`). This page is the overview; design reasons are in [ADR 0002](adr/0002-protocol.md).

## Connection

1. The core listens on `127.0.0.1` (`CoreServer.start`) and starts the game server with two environment variables:
   - `MSC_CORE_ADDRESS`: `host:port` of the core
   - `MSC_TOKEN`: a random token for this session
2. The agent connects through `AgentLink` in `msc-agent-common` ([ADR 0024](adr/0024-agent-common.md)), which finds the core with `CoreAddress.resolve()`: the two environment variables, or else the system properties `msc.core.address` (`host:port`) and `msc.token` for servers started by hand and for tests. If neither is set, the agent stays idle. `AgentLink` retries with backoff, reconnects when the core drops it, and sends `server.ready` once both the connection is up and the server has finished starting.
3. Every frame is one JSON object on one line, UTF-8, ending in `\n`, at most 16 MiB (`Limits.MAX_FRAME_BYTES`). The `type` field names the message. See [Size limits](#size-limits) for what happens to a message that would not fit.

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

In the code a response's `result` and a request's `params` are a `RawJson`, the value's JSON text: `OpSpec` writes the
record straight to text and reads it straight back, and the read loop only skips over it, so a large result or a large
`world.write` never becomes a `JsonElement` tree ([ADR 0030](adr/0030-protocol-result-encoding.md)). A result that
balances but is not valid JSON fails that call with `bad_frame`, and such params fail that request with
`invalid_params`; both leave the connection open. A frame that does not balance closes it, like any bad frame.

Error codes: `unknown_op`, `invalid_params`, `not_found`, `too_large`, `timeout`, `internal`, `unauthorized`, `version_mismatch`, `bad_frame`, `shutdown`.

### Size limits

No message is dropped silently. A message that cannot be sent while the connection is fine (its frame is over
16 MiB, or it cannot be encoded) is never written, and:

- an answer is replaced by a small error answer for the same request id: `too_large` ("the server.command result is N
  bytes, over the 16777216-byte frame limit; ask for less"), or `internal` when it could not be encoded. An error
  answer that is itself too large keeps its code, the first 1024 characters of its message and its `PartialEdit`
  undo id. So the caller fails at once instead of waiting out its timeout.
- a request fails its future at once with `FrameTooLargeException` (or another `ProtocolException`), naming the op.
- an event throws the same from `Connection.sendEvent`; `AgentLink` logs it as a warning.

Results whose size the server decides, not the request, are cut where they are made, with a marker, so they fit
(`Limits`):

| result | limit | marker |
| --- | --- | --- |
| `server.command` `output` | 4 Mi characters in all, each line counting one more (`MAX_RESULT_TEXT_CHARS`) | the line that crosses it ends in ` [cut: N more chars]`; a last line `[output cut at 4194304 characters: N more lines not shown]` when lines were left out |
| `world.entities` | 1000 entities (`EntitiesResult.MAX_ENTITIES`) and 4 Mi characters of names and tags | fewer `entities` than `total` |
| entity `name`, each tag (also `world.read` entities) | 1024 characters (`MAX_TEXT_CHARS`) | ends in ` [cut: N more chars]` |
| sign lines in `world.read` | 1000 signs (`SignText.MAX_SIGNS`), 256 characters a line (`MAX_SIGN_LINE_CHARS`) | a cut line ends in ` [cut: N more chars]` |

The other results are bounded by their request: a region of at most `MAX_REGION_VOLUME` blocks or columns. The wire
format is unchanged: cut text is ordinary text, so an older core or agent reads it as before.

## Operations (core asks, agent answers)

Defined with their Java types in `Ops`. Block states are vanilla strings such as `minecraft:oak_stairs[facing=north]`.

| op | params | result |
| --- | --- | --- |
| `server.info` | `{}` | `platform, platformVersion, minecraftVersion, worlds[], onlinePlayers, tps` |
| `server.command` | `command` | `success, output[]` |
| `world.read` | `region, includeEntities, includeSigns?, includeBlockData?` | `grid, entities[], signs[{x, y, z, front[4], back[4]?}]` (signs only when `includeSigns`, only signs with text, at most 1000; `back` omitted when blank). With `includeBlockData`, a block with block entity data has its own palette entry, the state followed by the data as SNBT (`minecraft:chest[facing=north]{Items:[...]}`); a read whose data passes 4 Mi characters fails with `too_large` |
| `world.entities` | `region, type?, limit` | `entities[{type, uuid, x, y, z, name?, tags[], health?, maxHealth?}], total` (no blocks read, so no volume limit; loaded chunks only) |
| `world.heightmap` | `world, min, max` (x/z only) | `minX, minZ, sizeX, sizeZ, heights[]` (Z then X) |
| `world.write` | `grid, updatePhysics` | `editId, changed` (the only block-writing op; every build is a grid). A palette entry may end in SNBT, merged into the placed block's data as `/data merge block` does, also where the state was already there (that counts as changed, and the undo puts the old data back); data on a block that keeps none is `invalid_params` before anything changes |
| `world.undo` | `editId` | `editId, changed` |
| `pack.apply` | `url, sha1, required` | `playersSent` |

A `world.write` that fails after changing blocks (its main-thread wait timed out, the server stopped, a block failed) keeps those blocks undoable: its error message ends with the undo id of what it changed (`PartialEdit.note`, read back with `PartialEdit.editId`), and the core undoes it with the rest of the build. Each agent wait lasts at most `Limits.AGENT_WAIT` (2 min), an operation makes at most `Limits.AGENT_WAITS_PER_OP` of them (Minestom waits for chunks, then for one main-thread job: the edit, or the whole read with its entities and signs), and the core waits `Limits.CORE_CALL_TIMEOUT` (250 s), longer than all of them, so the agent's answer always arrives first.

### Regions and grids

- `region` = `{"world":"world","min":{"x":0,"y":64,"z":0},"max":{"x":15,"y":70,"z":15}}`, inclusive, corners sorted on decode.
- `grid` = `{"region":..., "palette":["minecraft:air","minecraft:stone"], "blocks":[0,1,1,...]}`.
- `blocks` is ordered Y, then Z, then X (X fastest): index = `((y - minY) * sizeZ + (z - minZ)) * sizeX + (x - minX)`.
- In writes, `-1` leaves a block unchanged.
- An entity is in a region when its position is (`Region.containsPoint`), not when its hitbox touches it, on every platform; so the pieces of a split read hold each entity once.
- `includeBlockData` is left out of the frame when off, so a read without it is the same bytes as before ([ADR 0042](adr/0042-block-entity-data.md)); a palette entry without `{` is a plain state, as before.
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
AgentLink link = new AgentLink(info, router, log); // CoreAddress.resolve(): env vars, then msc.core.address / msc.token
link.start();          // connects on a virtual thread
link.serverLoaded();   // when the server has started: sends server.ready once connected
link.stop();           // on shutdown: sends server.stopping and closes
```

Handlers run on virtual threads, so they may block while waiting for the server's main thread. Throw `OpException(code, message)` to answer with a specific error code; `IllegalArgumentException` maps to `invalid_params` and anything else to `internal`.

## Calling an agent from the core

```java
CoreServer server = CoreServer.start(0, token, RequestHandler.none(), listener, Duration.ofSeconds(10));
// pass server.address() and token to the game server's environment
Ops.EditResult r = session.connection().call(Ops.WORLD_WRITE, new Ops.WriteParams(grid, false), Duration.ofSeconds(30)).get();
```
