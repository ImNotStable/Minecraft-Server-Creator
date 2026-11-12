package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpRouter;

/** Wires every operation in {@link Ops} to its handler. */
final class AgentRoutes {
    private AgentRoutes() {
    }

    static OpRouter router(ServerOps server, WorldOps world) {
        return new OpRouter()
                .on(Ops.SERVER_INFO, p -> server.info())
                .on(Ops.SERVER_COMMAND, server::command)
                .on(Ops.WORLD_READ, world::read)
                .on(Ops.WORLD_ENTITIES, world::entities)
                .on(Ops.WORLD_HEIGHTMAP, world::heightmap)
                .on(Ops.WORLD_FILL, world::fill)
                .on(Ops.WORLD_WRITE, world::write)
                .on(Ops.WORLD_SET_BLOCKS, world::setBlocks)
                .on(Ops.WORLD_UNDO, world::undo)
                .on(Ops.PACK_APPLY, server::applyPack);
    }
}
