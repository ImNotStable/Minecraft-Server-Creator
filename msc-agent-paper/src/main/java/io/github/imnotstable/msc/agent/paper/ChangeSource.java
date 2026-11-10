package io.github.imnotstable.msc.agent.paper;

import org.bukkit.block.data.BlockData;

/** A cursor over the blocks an edit wants to set. */
interface ChangeSource {
    /** Move to the next change; {@code false} when there are no more. */
    boolean advance();

    int x();

    int y();

    int z();

    BlockData target();

    /** Upper bound on the number of changes, used to size buffers. */
    long size();
}
