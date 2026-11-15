# ADR 0043: Mutual proof on the agent link

Status: accepted, 2026-11-14, decided by Claude.

## Context

The core listens on loopback TCP and every agent (Paper, Fabric, NeoForge, Forge through msc-agent-vanilla, Minestom)
connects with `MSC_CORE_ADDRESS` and `MSC_TOKEN` through `AgentLink` and `AgentClient` ([ADR 0002](0002-protocol.md),
[ADR 0024](0024-agent-common.md)). The link carries `server.command` (anything the console can do), `world.write` and
every world read, so whoever holds it controls the server. Machines that matter: the owner's PC, and a shared Linux
box (an Ubuntu server with other user accounts).

Audit of protocol 1:

| Question | Finding |
| --- | --- |
| Is the token checked before any op? | Yes: the first frame must be a `hello` with the right version and token, compared with `MessageDigest.isEqual` (constant time); nothing else is read before. |
| Is the token strong? | The core's default is a random UUID (122 bits, `SecureRandom`); the bench uses 128 random bits. A user-set `MSC_TOKEN` can be anything, even empty. |
| Can another local process send ops or take the agent slot? | Not without the token. The hub keeps the newest *authenticated* agent. |
| Can a browser reach it (DNS rebinding, HTTP POST to the loopback port)? | Its first line is `POST / HTTP/1.1`, which fails as a hello; it cannot learn the token. But the core answered with a JSON goodbye. |
| Pre-auth limits | The handshake read allowed a 16 MiB line, the timeout was per read (a peer sending a byte every few seconds held a thread and buffer for ever), and connections waiting for their handshake were not capped: memory exhaustion from any local user. |
| Does the core bind non-loopback? | No: `CoreServer` always binds `InetAddress.getLoopbackAddress()`; the host in `MSC_CORE_ADDRESS` is ignored by the core, only its port is used. Bench's fixed port 25590 and the "any agent may attach" mode ([ADR 0005](0005-core-tools.md)) are loopback too and still need the token. |
| How does the token reach the agent? | Environment variable of the server process. On Linux `/proc/<pid>/environ` is readable by the process's own user only, so other users cannot read it. The documented alternative `-Dmsc.token=` is on the command line, which `ps` shows to **every** user. The bench writes it into the attempt's `mcp.json` with default permissions. The core writes it to no file. |
| Does the agent authenticate the core? | **No.** The agent sent the token in clear to whatever listened on the address and then served any request after any `welcome`. A detached server ([ADR 0014](0014-detach.md)) keeps reconnecting to its old core's port after that core exits, and a fixed port (25590, attach mode) is free whenever no core runs: another user can listen there, receive the token, and drive the server with `server.command`. |
| Resource-pack host | Serves only `GET /pack-<sha1>.zip`, read-only, the pack players download anyway; binds `MSC_PACK_HOST` (loopback by default). Nothing to authenticate; unchanged. |

## Decision

1. **The token never crosses the wire; each side proves it holds it.** Protocol version 2 handshake:

   ```
   agent -> core  hello     {protocolVersion: 2, nonce: Na, agent}
   core  -> agent challenge {nonce: Nc}
   agent -> core  proof     {proof: HMAC-SHA256(token, "msc-agent-proof\n" Na "\n" Nc)}
   core  -> agent welcome   {protocolVersion: 2, sessionId, proof: HMAC-SHA256(token, "msc-core-proof\n" Na "\n" Nc)}
   ```

   Nonces are 32 random bytes as hex (`HandshakeProof`); proofs are compared in constant time. The agent proves
   first, so a process that merely connects to the core gets nothing to guess the token against; the core's nonce is
   new per connection, so a recorded proof cannot be replayed; the two labels keep one side's proof from passing as
   the other's. The agent handles no request until the core's proof checks out; a core that fails it is a protocol
   error, so `AgentLink` keeps retrying and may reach the real core later.
2. **Pre-auth limits on both sides**: handshake frames are at most 64 KiB (`Limits.MAX_HANDSHAKE_FRAME_BYTES`), the
   whole handshake has one deadline (`HandshakeDeadline`, the core's 10 s; the agent's connect timeout), and the core
   lets at most 16 connections wait for their handshake at once.
3. **A first line that is not JSON gets no answer**: an HTTP request (or any other stranger) is closed silently and
   reported to the listener as such.
4. **Loopback only, as before**, now stated in the code; an empty token is refused at start, and a blank `MSC_TOKEN`
   means "pick a random one". The core's own token is now 256 random bits.
5. **Token off the command line**: agents read `-Dmsc.token.file=<path>` (a file only its owner can read) as well as
   `-Dmsc.token`; the docs point hand-started servers on shared machines to the file. `CoreAddress` no longer prints
   the token. The bench writes `mcp.json` owner-only.
6. `ProtocolVersion.CURRENT` is 2. A protocol-1 agent left in a server folder is refused with `version_mismatch`
   (the core checks the version before anything else), and a new agent meeting an old core gets the same; core and
   agents come from one install, so `server_setup` replaces the jar.

Rejected: TLS (a library or keystore for a loopback link, and certificates to manage); Unix domain sockets with
file permissions (Windows support and every agent's transport would change); per-frame MACs after the handshake
(see residual risks).

## Consequences

- Threats closed: token theft and server takeover by a fake core on a free port, token in clear on the wire,
  pre-auth memory and thread exhaustion, HTTP-to-TCP requests getting an answer, token visible in `ps` for
  hand-started servers that use the file, bench token readable by other users.
- Residual risks, accepted: a process of the **same user** can read the environment, attach a debugger or read the
  files anyway, so it is out of scope. After the handshake, frames are neither encrypted nor MACed: a party that sits
  in the middle of a loopback connection (it would have to own the agent's target port *and* relay to the real core)
  could read or inject. A weak user-chosen `MSC_TOKEN` can be guessed offline by a fake core from one agent proof;
  the default tokens are random. A fake core can still refuse service (the agent gives up after a `goodbye`).
- Live check needed on real Paper, Fabric, NeoForge and Forge servers: the agents compile against the same
  `AgentClient`, so nothing platform-specific changed, but the jars installed in existing server folders must be
  replaced (`server_setup`) before they connect.
