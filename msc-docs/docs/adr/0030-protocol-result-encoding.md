# ADR 0030: Operation results go between record and frame as JSON text

Status: accepted, 2026-11-13. Decided by Claude under ADR 0029, with the campaign owner's condition that the protocol
API changes only if a measurement shows at least 15 percent on a full-region round trip. Closes the rest of #160.

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
  both typed. Only code that used `Response.result()` or wrote a `RequestHandler` by hand sees `RawJson`; `RawJson.tree()`
  and `Response.ok(id, JsonElement)` keep that easy.
- Request params and event data stay `JsonObject`: they are small.
