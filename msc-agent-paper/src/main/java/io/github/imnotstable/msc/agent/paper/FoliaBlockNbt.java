package io.github.imnotstable.msc.agent.paper;

import java.lang.reflect.Method;
import org.bukkit.World;
import org.bukkit.block.BlockState;

/**
 * {@link BlockNbt} on Folia (ADR 0100), which leaves out {@code /data} and refuses commands from a region thread: the
 * block entity's own NBT through the server's block state ({@code CraftBlockEntityState.getSnapshotNBT} and
 * {@code loadData}) and Minecraft's SNBT parser, by reflection, on the region thread that owns the block.
 * shortcut: reflection on server internals the Paper API does not offer; Paper renaming them breaks block entity data on
 * Folia only (a clear error), so recheck on each Folia bump (verified on Folia 26.3 build 27).
 */
final class FoliaBlockNbt implements BlockNbt {
    @Override
    public String get(World world, int x, int y, int z) {
        BlockState state = world.getBlockAt(x, y, z).getState();
        Object tag = snapshot(state);
        return tag == null ? null : tag.toString();
    }

    @Override
    public void merge(World world, int x, int y, int z, String snbt) {
        BlockState state = world.getBlockAt(x, y, z).getState();
        Object tag = snapshot(state);
        if (tag == null) {
            throw new IllegalArgumentException(state.getType().getKey() + " at " + x + "," + y + "," + z
                    + " has no block entity data; " + snbt + " cannot go on it");
        }
        try {
            Class<?> compound = tag.getClass();
            Object patch = parse(compound.getClassLoader(), snbt);
            compound.getMethod("merge", compound).invoke(tag, patch);
            state.getClass().getMethod("loadData", compound).invoke(state, tag);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalArgumentException("data merge at " + x + "," + y + "," + z + " failed: "
                    + e.getCause().getMessage(), e.getCause());
        } catch (ReflectiveOperationException e) {
            throw unsupported(e);
        }
        state.update(true, false);
    }

    /** The block entity's NBT (with id and position), or null when the block has none. */
    private static Object snapshot(BlockState state) {
        Method m;
        try {
            m = state.getClass().getMethod("getSnapshotNBT");
        } catch (NoSuchMethodException e) {
            return null; // not a block entity: CraftBlockState has no snapshot
        }
        try {
            return m.invoke(state);
        } catch (ReflectiveOperationException e) {
            throw unsupported(e);
        }
    }

    private static Object parse(ClassLoader loader, String snbt) throws ReflectiveOperationException {
        Class<?> parser = Class.forName("net.minecraft.nbt.TagParser", true, loader);
        for (String name : new String[] {"parseCompoundFully", "parseTag"}) {
            try {
                return parser.getMethod(name, String.class).invoke(null, snbt);
            } catch (NoSuchMethodException e) {
                // older or newer name: try the next
            }
        }
        throw new NoSuchMethodException("TagParser.parseCompoundFully");
    }

    private static IllegalStateException unsupported(Exception e) {
        return new IllegalStateException("block entity data on this Folia build is not supported by the msc agent: " + e, e);
    }
}
