package io.github.imnotstable.msc.agent.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The blocks an edit replaced, so the edit can be undone. States are kept in a palette.
 *
 * @param <S> the platform's block state type
 */
public record UndoRecord<S>(String world, long[] positions, int[] states, List<S> palette) {

    public int size() {
        return positions.length;
    }

    /** Changes that put every recorded block back. */
    public ChangeSource<S> restore() {
        return new ChangeSource<>() {
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
            public S target() {
                return palette.get(states[i]);
            }

            @Override
            public long size() {
                return positions.length;
            }
        };
    }

    /** Collects replaced blocks while an edit runs. States are told apart by {@code equals}. */
    public static final class Builder<S> {
        private final String world;
        private long[] positions;
        private int[] states;
        private int size;
        private final Map<S, Integer> index = new HashMap<>();
        private final List<S> palette = new ArrayList<>();

        public Builder(String world, long expected) {
            this.world = world;
            int capacity = (int) Math.min(Math.max(expected, 16), 1 << 16);
            positions = new long[capacity];
            states = new int[capacity];
        }

        public void add(int x, int y, int z, S previous) {
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

        public int size() {
            return size;
        }

        public UndoRecord<S> build() {
            return new UndoRecord<>(world, Arrays.copyOf(positions, size), Arrays.copyOf(states, size), List.copyOf(palette));
        }
    }
}
