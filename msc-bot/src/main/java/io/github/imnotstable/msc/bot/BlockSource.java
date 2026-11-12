package io.github.imnotstable.msc.bot;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;

/** Reads blocks with their states; the core reads them through the server's agent. */
@FunctionalInterface
public interface BlockSource {
    BlockGrid read(Region region) throws Exception;
}
