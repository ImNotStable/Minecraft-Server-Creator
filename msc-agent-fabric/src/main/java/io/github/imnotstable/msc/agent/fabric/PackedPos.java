package io.github.imnotstable.msc.agent.fabric;

/** Packs a block position into one long: 26 bits X, 26 bits Z, 12 bits Y, all signed. */
final class PackedPos {
    private PackedPos() {
    }

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    static int x(long packed) {
        return (int) (packed >> 38);
    }

    static int z(long packed) {
        return (int) (packed << 26 >> 38);
    }

    static int y(long packed) {
        return (int) (packed << 52 >> 52);
    }
}
