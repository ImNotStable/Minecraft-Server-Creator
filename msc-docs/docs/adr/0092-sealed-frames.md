# ADR 0092: Sealed frames on the agent link, and the link and NBT audits redone

Status: accepted, 2026-11-23. Decided by Claude, item 6 of Jeremiah's gap list: "agent-link auth plus NBT audit".

## Context

Both were done before: the agent link's mutual proof and pre-auth limits (ADR 0043), tokens per workspace (ADR 0050),
and block entity data through `build_batch`, `world_slice data=true`, undo and structure files (ADR 0042). Redone
against today's code:

| Question | Finding |
| --- | --- |
| Token on the wire, fake core, pre-auth exhaustion, HTTP to the port, loopback only, token in `ps` | Closed as ADR 0043 says; `HandshakeAuthTest`, `HandshakeProofTest` and the core's tests still cover each. |
| An agent attaching to another workspace's core | Closed by ADR 0050's per-workspace token. |
| Frames after the handshake | **Open**: ADR 0043's accepted residual risk. Once the welcome is sent, frames carry no proof of where they come from: a process that gets between the agent and the core on loopback (it holds the port the agent connects to, or relays a connection) can inject `server.command` or `world.write` requests, replay one, or drop answers. |
| Block entity NBT | Holds on real servers: a chest with a written book and a sign, set with SNBT, read back by `world_slice data=true`, restored by `build_undo` after a fill, saved to `.schem` and placed again with the same data (Paper); a barrel's items on Fabric. Entities and items stay with `server_command` (`data`, `summon`, `give`), as ADR 0042 decided. |

## Decision

1. **Protocol 3 seals every frame after the welcome** (`FrameSeal`): both sides derive a session key,
   `HMAC-SHA256(token, "msc-session-key\n" + Na + "\n" + Nc)` (a third label, so it is neither proof), and send each
   frame as `<64 hex MAC> <json>`, the MAC over `"<direction>\n<seq>\n" + json` with direction `agent>core` or
   `core>agent` and a per-direction sequence number. The receiver checks it in constant time; a frame that fails
   (injected, replayed, reordered, after a dropped one, or reflected back) is a protocol error: a `bad_frame` goodbye
   and the connection ends. The writer seals under its lock, so sequence numbers follow the wire order; the frame limit
   counts the MAC.
2. **No encryption**: the link is loopback and carries the user's own world and commands; confidentiality from a
   process that can already sit on loopback ports would need TLS and keys for little gain.
3. `ProtocolVersion.CURRENT` is 3. Older agents left in a server folder get `version_mismatch`; `server_start`
   refreshes the installed agent jar, as it did here on Paper ("agent jar updated").

Verified on omen with protocol 3: the Paper agent and the Fabric agent connected, built, read and undid through the
sealed link; `ConnectionTest` sends a bare frame, a replayed frame and a frame sealed for the other direction after a
real handshake, and each ends the connection unhandled.

## Consequences

- ADR 0043's residual risk is closed: whoever gets between the agent and the core can no longer drive the server.
- About 65 bytes and one HMAC per frame.
- Still accepted: a process of the same user (it can read the token anyway), denial of service by closing the link,
  and reading frames on loopback.
