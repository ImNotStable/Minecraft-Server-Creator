package io.github.imnotstable.msc.textures;

/**
 * Textures drawn by hand for tests, the way an agent draws them (ADR 0106: msc ships no stock shapes or patterns).
 */
final class Drawn {
    private Drawn() {
    }

    /** A 16x16 cut gem in the darkest-to-lightest shades of {@code ramp} (5 or more), outline at (4, 2). */
    static Texture gem(Ramp ramp) {
        String[] rows = {
                "................",
                "................",
                "....00000000....",
                "...0333444430...",
                "..033344444430..",
                ".03322344443330.",
                ".02222233333320.",
                ".01222223333210.",
                "..012222333210..",
                "...0122223210...",
                "....01222210....",
                ".....012210.....",
                "......0110......",
                ".......00.......",
                "................",
                "................"};
        Texture t = new Texture(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                char c = rows[y].charAt(x);
                if (c != '.') {
                    t.set(x, y, ramp.get(Math.min(c - '0', ramp.size() - 1)));
                }
            }
        }
        return t;
    }

    /** A 16x16 tile that uses every shade of {@code ramp}, in a different order per {@code seed}. */
    static Texture tile(Ramp ramp, int seed) {
        Texture t = new Texture(16, 16);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                t.set(x, y, ramp.get(Math.floorMod(x * 7 + y * 13 + seed * 5 + (x * y) % 3, ramp.size())));
            }
        }
        return t;
    }

    /** {@link #tile} of {@code stone} with a few flecks of {@code ore}, every shade of it. */
    static Texture ore(Ramp stone, Ramp ore, int seed) {
        Texture t = tile(stone, seed);
        for (int i = 0; i < 12; i++) {
            t.set(3 + i % 4 + (i / 4) * 3, 4 + (i * 5 + seed) % 8, ore.get(i % ore.size()));
        }
        return t;
    }
}
