package io.github.imnotstable.msc.agent.common;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpRouter;

/** Wires every operation in {@link Ops} to its handler. */
public final class AgentRoutes {
    private AgentRoutes() {
    }

    public static OpRouter router(ServerOps server, WorldOps<?> world) {
        return new OpRouter()
                .on(Ops.SERVER_INFO, p -> server.info())
                .on(Ops.SERVER_COMMAND, server::command)
                .on(Ops.WORLD_READ, world::read)
                .on(Ops.WORLD_ENTITIES, world::entities)
                .on(Ops.WORLD_HEIGHTMAP, world::heightmap)
                .on(Ops.WORLD_WRITE, world::write)
                .on(Ops.WORLD_UNDO, world::undo)
                .on(Ops.PACK_APPLY, server::applyPack);
    }

    /**
     * Only the server operations, for a server whose world the agent cannot edit safely (Folia, ADR 0045): the core
     * sees the world operations missing from the agent's list and says so instead of calling them.
     */
    public static OpRouter serverOnly(ServerOps server) {
        return new OpRouter()
                .on(Ops.SERVER_INFO, p -> server.info())
                .on(Ops.SERVER_COMMAND, server::command)
                .on(Ops.PACK_APPLY, server::applyPack);
    }
}
