# ADR 0030: Operation results go between record and frame as JSON text

Status: accepted, 2026-11-13; amended the same day for request params (#360). Decided by Claude under ADR 0029, with
the campaign owner's condition that the protocol API changes only if a measurement shows at least 15 percent on a
full-region round trip. Closes the rest of #160.

## Context

A `world.read` of one full-size region (128 x 64 x 128 = 1,048,576 blocks, about 2.1 MB of JSON) paid for two
`JsonElement` trees on top of the frame itself, because `Response.result` was a `JsonElement`: the agent built a tree
of the result record (`OpSpec.encodeResult`, one `JsonPrimitive` per block) and wrote it out; the core parsed the
frame's result into a tree and walked that tree into the record (`OpSpec.decodeResult`). PR #188 already removed the
per-byte frame read and the message-level trees. Every split read and write (`world_render`, `world_summary`,
`build_batch`, the test player) pays this per piece.

## Decision

1. `Response.result` is a `RawJson`: one JSON value kept as its text. `OpSpec.encodeResult` writes the result record
   straight to text with Gson, `OpSpec.decodeResult` reads the record straight from that text. `RequestHandler.handle`
   returns `RawJson`.
2. `MessageCodec` writes a `RawJson` into the frame as is (`JsonWriter.jsonValue`). It reads a response frame with a
   small scanner that only skips over the result value to find where it ends, in any field order; anything unusual
   (truncated frame, duplicate `type`, trailing text) goes through the old tree path, which still names the error.
3. Because the read loop no longer parses the result, a result whose brackets and strings balance but that is not
   valid JSON fails only that call, with `bad_frame`, instead of closing the connection. A frame that does not balance
   still fails the tree path and closes the connection, as before.
4. The wire format does not change: `ResultWireTest` holds frames recorded from main before the change and checks that
   the new code writes the same bytes and reads them back, so an old agent jar still talks to a new core and the reverse.
   The protocol version stays 1.

## Consequences

- Measured with `RegionRoundTripBench` (msc-protocol test sources, a `main`, not part of the test run): core calls
  `world.read` on an agent over loopback, 10 warm-up and 40 timed round trips of one full-size region, old and new
  classes run alternately on the same machine (4 CPUs, other builds running):

  | | median | min |
  | --- | --- | --- |
  | before (main) | 390 ms, 432 ms | 257 ms, 243 ms |
  | after | 185 ms, 211 ms | 156 ms, 154 ms |

  About 50 percent less time per full-region round trip.
- Source callers are unchanged: agents register handlers with `OpRouter.on` and the core calls `Connection.call`,
  both typed. Only code that used `Response.result()` or wrote a `RequestHandler` by hand sees `RawJson`;
  `Response.ok(id, JsonElement)` keeps that easy (a `RawJson.tree()` helper was removed later: nothing used it).
- Event data stays `JsonObject`: every event is small. Request params were kept as `JsonObject` on the same premise,
  which was false for `world.write` (see the amendment below).

## Amendment (2026-11-13, #360): request params are JSON text too

`world.write` carries a `BlockGrid` of up to 1,048,576 cells in its params, the same 2.1 MB as a full `world.read`
result, and it still paid both trees: the core built one with `OpSpec.encodeParams` (`toJsonTree`), the agent's read
loop parsed the frame's params into a `JsonObject`, and `OpRouter` walked that tree into the record. The other ops'
params (`server.command`, `world.read`, `world.entities`, `world.heightmap`, `world.undo`, `pack.apply`,
`server.info`) are small, but one mechanism for all of them is simpler than a special path for one op.

1. `Request.params` is a `RawJson`, like `Response.result`. `OpSpec.encodeParams` writes the params record straight to
   text and `OpSpec.decodeParams` reads it straight from text. `RequestHandler.handle` takes `RawJson`. `Connection`
   and `Request` keep `JsonObject` overloads for hand-built requests.
2. The codec's response scanner (`ResponseReader`, now `RawFrameReader`) reads request frames too: it skips over the
   params value and keeps its text; anything unusual still goes through the tree path, which names the error.
3. Params that balance but are not valid JSON (or are not an object) fail only that request with `invalid_params`,
   instead of closing the connection.
4. `int[]` (a grid's blocks, a heightmap) has its own Gson adapter: written as one string of digits rather than a
   `String` per element, read into an `int[]` rather than a boxed `Integer` per element. Same JSON both ways. This
   speeds up `world.read` results as well.
5. The wire does not change: `ParamsWireTest` holds a request frame for every op recorded from main before this change
   and checks the new code writes the same bytes and reads them back. It also fails if a full-region `world.write`
   is decoded through a tree again (the params text keeps the frame's own spacing, and the agent-side decode must
   allocate under half of what a tree of the params alone takes).

Measured with the issue's harness (`WriteBench`, outside the repo: 128 x 64 x 128 grid, write frame 2,097,481 bytes,
10 warm-up and 30 timed rounds, round trip over loopback with `Connection.call`), before (main at 06a2ee3) and after
run alternately, twice each, on the same 4-CPU machine at load 17 to 21:

| | write round trip, median | write round trip, min | read round trip, median | agent-side write decode, median |
| --- | --- | --- | --- | --- |
| before | 707 ms, 480 ms | 400 ms, 302 ms | 463 ms, 251 ms | 231 ms, 279 ms |
| after | 42 ms, 38 ms | 31 ms, 32 ms | 43 ms, 43 ms | 22 ms, 19 ms |

A full-region write now costs the same as a full-region read, and both are about a tenth of before. Allocation in
`ParamsWireTest`: decoding the write on the agent side takes about 10 MB against 98 MB for a tree of its params alone;
encoding it on the core side about 13 MB against 89 MB for the old tree path.
