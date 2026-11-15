# ADR 0050: An agent attaches only to its own workspace

Status: accepted, 2026-11-15, decided by Claude (#726). Amends [ADR 0005](0005-core-tools.md) point 8 and
[ADR 0014](0014-detach.md) point 4.

## Context

With a fixed `MSC_CORE_ADDRESS` and `MSC_TOKEN` (msc-bench sets both), every workspace a session opens listens on the
same port with the same token. After `server_detach` in workspace A and `msc_workspace` to B, A's detached server lost
its connection when A's bench closed, and its `AgentLink` reconnected to the same address within a second. B's bench
was listening there and took it: "any agent may attach, the newest connection wins" (ADR 0005). From then on B's world
tools, `build_batch` and `server_command` acted on A's server, while B's `msc_status` said `server: stopped`. ADR 0014
assumed this could not happen because "the agent's address is random per core"; with a fixed address it is the normal
path, since #725 made every such switch work.

The class: an agent must be accepted only by the workspace whose server it belongs to. Options:

1. The agent reports its server folder or workspace in its `hello`, and the hub compares. A protocol message change, a
   change in every agent (Paper, mod agents, Minestom), and nothing for agents already installed in server folders.
   It also needs a rule for which folders count as "another workspace's", since a server attached on purpose (the
   benchmark's) runs from any folder.
2. The hub accepts only an agent it expects (as it does without a fixed address). That ends attach mode, which the
   benchmark needs.
3. **The servers a workspace starts get a token of their own.** The core already proves identity with the token
   (ADR 0043); it just has to be per workspace.

## Decision

Option 3.

1. A server the core starts gets `MSC_TOKEN` = HMAC-SHA256(token, `"msc-workspace-server\n"` + the workspace's real
   path), as hex (`CoreConfig.serverToken()`). The token is the session's (`MSC_TOKEN`, or a random one); the label
   keeps it apart from the handshake proofs.
2. A workspace's hub takes an agent that proves either the shared token (a server started outside MSC and attached
   through `MSC_CORE_ADDRESS`, as before) or its own workspace's token, and no other. `CoreServer.start` gains an
   overload taking several tokens; the core proves itself back with the one the agent proved, so the agent sees no
   difference. The wire protocol, `ProtocolVersion` and every agent are unchanged.
3. Another workspace's agent fails the proof and gets the `unauthorized` goodbye, so its `AgentLink` stops trying (it
   logs `msc-core refused the connection`), as for any wrong token. The hub counts these, and `msc_status` ends its
   agent line with `refused N agent(s) without this session's token (a server another workspace or an earlier session
   started)`. Amended 2026-11-15 (#734): the first wording said "another workspace's server", but with a fixed
   `MSC_CORE_ADDRESS` and no `MSC_TOKEN` each session has its own random token, so a server an earlier session of
   this same workspace detached is refused too, and the hub cannot tell the two apart. When this workspace has such a
   leftover, the line adds that `server_start` or `dev_cycle` restarts it under this session.
4. An agent connected while this session runs no server (an attached one) is marked in `msc_status` with
   `of a server started outside this session`, so `server: stopped` beside a live agent is explained.
5. The same workspace reopened later (a switch back, or a new session with the same `MSC_TOKEN`) derives the same token,
   so its own server could attach again: that is the workspace it belongs to.

Rejected: option 1 (a protocol change in every agent for what the token already does); option 2 (ends attach mode).

## Consequences

- B's tools never reach A's server, with or without a fixed address. A's detached server keeps running and keeps
  ADR 0037's ownership; it no longer has an agent connection once it was refused.
- Agents and installed agent jars need no update. A server started by an MSC core older than this change carries the
  shared token and can still attach anywhere with that token, until it is restarted.
- `CoreServer` checks a proof against every token it holds, so the time taken says nothing about which one matched.
