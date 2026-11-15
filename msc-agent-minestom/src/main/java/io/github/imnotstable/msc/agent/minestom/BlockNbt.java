package io.github.imnotstable.msc.agent.minestom;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;
import net.minestom.server.instance.block.Block;

/**
 * Block entity data as SNBT on Minestom (ADR 0042), where it lives on the block itself ({@link Block#nbt()}), so a read
 * and a write here match {@code /data get block} and {@code /data merge block} on the vanilla platforms.
 */
final class BlockNbt {
    /** Keys that are not the block's data but its type and position, which the block decides. */
    private static final Set<String> METADATA = Set.of("id", "x", "y", "z");

    private BlockNbt() {
    }

    /** Whether a block of this kind keeps block entity data, as in vanilla. */
    static boolean holds(Block block) {
        return block.blockEntityType() != null;
    }

    /**
     * @throws IllegalArgumentException when the block keeps no block entity data or the SNBT is malformed
     */
    static CompoundBinaryTag parse(Block block, String snbt) {
        if (!holds(block)) {
            throw new IllegalArgumentException(block.state() + " has no block entity data; " + snbt + " cannot go on it");
        }
        CompoundBinaryTag tag;
        try {
            tag = TagStringIO.tagStringIO().asCompound(snbt);
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("bad block entity data " + snbt + ": " + e.getMessage(), e);
        }
        CompoundBinaryTag.Builder kept = CompoundBinaryTag.builder();
        for (Map.Entry<String, ? extends BinaryTag> e : tag) {
            if (!METADATA.contains(e.getKey())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return kept.build();
    }

    /** The block's data as SNBT, or null when it has none. */
    static String read(Block block) {
        CompoundBinaryTag nbt = block.nbt();
        if (nbt == null || nbt.size() == 0) {
            return null;
        }
        try {
            return TagStringIO.tagStringIO().asString(nbt);
        } catch (IOException e) {
            throw new IllegalStateException("cannot write the data of " + block.state() + " as SNBT", e);
        }
    }

    /** The block with the patch merged into its data as {@code /data merge block} does: compounds deeply, the rest replaced. */
    static Block merge(Block block, CompoundBinaryTag patch) {
        return block.withNbt(merge(block.nbtOrEmpty(), patch));
    }

    static CompoundBinaryTag merge(CompoundBinaryTag base, CompoundBinaryTag patch) {
        CompoundBinaryTag out = base;
        for (Map.Entry<String, ? extends BinaryTag> e : patch) {
            BinaryTag value = e.getValue();
            if (value instanceof CompoundBinaryTag inner && base.get(e.getKey()) instanceof CompoundBinaryTag existing) {
                value = merge(existing, inner);
            }
            out = out.put(e.getKey(), value);
        }
        return out;
    }
}
