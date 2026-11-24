package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * ADR 0099 and 0101: the review fails run 6's plank-box boat and pit harbour and passes ones built the way the guide
 * says, here placed block by block as an agent would.
 */
class WaterReviewTest {
    /** A world box: water up to {@code sea} over sand, then whatever the test sets. */
    private static final class World {
        final Region r;
        final List<String> palette = new ArrayList<>(List.of("minecraft:air"));
        final Map<String, Integer> ids = new HashMap<>(Map.of("minecraft:air", 0));
        final int[] blocks;

        World(Region r, int bed, int sea) {
            this.r = r;
            blocks = new int[Math.toIntExact(r.volume())];
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        set(x, y, z, y <= bed ? "stone" : y <= sea ? "water" : "air");
                    }
                }
            }
        }

        void set(int x, int y, int z, String block) {
            if (r.contains(x, y, z)) {
                blocks[r.index(x, y, z)] = ids.computeIfAbsent("minecraft:" + block, k -> {
                    palette.add(k);
                    return palette.size() - 1;
                });
            }
        }

        BlockGrid grid() {
            return new BlockGrid(r, palette, blocks);
        }
    }

    private static final Region SEA = Region.of("world", -14, 55, -8, 14, 80, 8);

    /** A 15-long boat along x on water at y 60: tapered hull, keel at 59, dry hold, deck at 61, mast, bellied sail. */
    private static BlockGrid goodBoat() {
        World w = new World(SEA, 45, 60);
        int[] half = {1, 1, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1, 0, 0};
        for (int u = 0; u < half.length; u++) {
            int x = u - 7;
            for (int y = 59; y <= 61; y++) {
                int h = y == 59 ? Math.max(0, half[u] - 1) : half[u];
                for (int z = -h; z <= h; z++) {
                    boolean skin = y == 59 || y == 61 || Math.abs(z) == h || u == 0 || u == half.length - 1;
                    w.set(x, y, z, y == 61 && !skin ? "oak_planks" : skin ? "spruce_planks" : "air");
                }
            }
        }
        for (int y = 60; y <= 75; y++) {
            w.set(0, y, 0, "spruce_log");
        }
        // A square sail in front of the mast, its middle a block further forward.
        for (int y = 64; y <= 73; y++) {
            for (int z = -3; z <= 3; z++) {
                boolean belly = Math.abs(z) <= 1 && y >= 66 && y <= 71;
                w.set(belly ? 2 : 1, y, z, "white_wool");
                if (Math.abs(z) == 2 && y >= 66 && y <= 71 || Math.abs(z) <= 1 && (y == 65 || y == 72)) {
                    w.set(2, y, z, "white_wool");
                }
            }
        }
        return w.grid();
    }

    @Test
    void aBoatBuiltTheWayTheGuideSaysPasses() {
        String review = BuildReview.review(goodBoat());
        assertThat(review).doesNotContain("box hull", "floating hull", "bare mast", "stump mast", "flat sails");
    }

    @Test
    void runSixsPlankBoxFailsEveryBoatCheck() {
        World w = new World(Region.of("world", -10, 55, -6, 10, 70, 6), 45, 60);
        for (int x = -6; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                w.set(x, 61, z, "oak_planks");
                w.set(x, 62, z, "oak_planks");
            }
        }
        for (int y = 63; y <= 66; y++) {
            w.set(0, y, 0, "oak_fence");
        }
        assertThat(BuildReview.review(w.grid())).contains("box hull: 1 boat", "floating hull: 1 boat",
                "bare mast: 1 boat", "stump mast: 1 boat");
    }

    @Test
    void aFlatSheetOfASailFails() {
        World w = new World(SEA, 45, 60);
        BlockGrid good = goodBoat();
        for (int i = 0; i < good.blocks().length; i++) {
            String s = good.palette().get(good.blocks()[i]);
            int x = SEA.min().x() + i % SEA.sizeX();
            int z = SEA.min().z() + i / SEA.sizeX() % SEA.sizeZ();
            int y = SEA.min().y() + i / (SEA.sizeX() * SEA.sizeZ());
            // Flatten the belly back into the plane at x 1.
            w.set(s.endsWith("wool") ? 1 : x, y, z, s.substring("minecraft:".length()));
        }
        assertThat(BuildReview.review(w.grid())).contains("flat sails: 1 boat");
    }

    @Test
    void aSlopedRaggedShorePassesAndAPitFails() {
        Region r = Region.of("world", 0, 50, 0, 40, 70, 30);
        World sloped = new World(r, 63, -100);
        World pit = new World(r, 63, -100);
        for (int z = 0; z <= 30; z++) {
            for (int x = 0; x <= 40; x++) {
                int edge = Math.min(Math.min(x, 40 - x), Math.min(z, 30 - z));
                // A ragged line: the bank's distance in shifts by up to 2 blocks along the shore.
                int ragged = (int) Math.round(Math.sin(x * 0.7) + Math.cos(z * 0.9));
                int top = Math.max(56, 63 - Math.max(0, edge + ragged) / 2);
                for (int y = 64; y > top; y--) {
                    sloped.set(x, y, z, y <= 60 ? "water" : "air");
                }
                boolean inPit = x >= 5 && x <= 30 && z >= 5 && z <= 25;
                for (int y = 63; y >= 58 && inPit; y--) {
                    pit.set(x, y, z, y <= 61 ? "water" : "air");
                }
            }
        }
        assertThat(BuildReview.review(sloped.grid())).doesNotContain("sheer banks", "square basin");
        assertThat(BuildReview.review(pit.grid())).contains("sheer banks: 1 body", "square basin: 1 body");
    }
}
