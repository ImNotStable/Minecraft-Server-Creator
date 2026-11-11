package io.github.imnotstable.msc.agent.fabric;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

/** The blocks an edit replaced, so the edit can be undone. States are kept in a palette. */
record UndoRecord(String world, long[] positions, int[] states, List<BlockState> palette) {

    int size() {
        return positions.length;
    }

    /** Changes that put every recorded block back. */
    ChangeSource restore() {
        return new ChangeSource() {
            private int i = -1;

            @Override
            public boolean advance() {
                return ++i < positions.length;
            }

            @Override
            public int x() {
                return PackedPos.x(positions[i]);
            }

            @Override
            public int y() {
                return PackedPos.y(positions[i]);
            }

            @Override
            public int z() {
                return PackedPos.z(positions[i]);
            }

            @Override
            public BlockState target() {
                return palette.get(states[i]);
            }

            @Override
            public long size() {
                return positions.length;
            }
        };
    }

    /** Collects replaced blocks while an edit runs. */
    static final class Builder {
        private final String world;
        private long[] positions;
        private int[] states;
        private int size;
        private final Map<BlockState, Integer> index = new IdentityHashMap<>();
        private final List<BlockState> palette = new ArrayList<>();

        Builder(String world, long expected) {
            this.world = world;
            int capacity = (int) Math.min(Math.max(expected, 16), 1 << 16);
            positions = new long[capacity];
            states = new int[capacity];
        }

        void add(int x, int y, int z, BlockState previous) {
            if (size == positions.length) {
                positions = Arrays.copyOf(positions, size * 2);
                states = Arrays.copyOf(states, size * 2);
            }
            Integer id = index.get(previous);
            if (id == null) {
                id = palette.size();
                palette.add(previous);
                index.put(previous, id);
            }
            positions[size] = PackedPos.pack(x, y, z);
            states[size] = id;
            size++;
        }

        int size() {
            return size;
        }

        UndoRecord build() {
            return new UndoRecord(world, Arrays.copyOf(positions, size), Arrays.copyOf(states, size), List.copyOf(palette));
        }
    }
}
