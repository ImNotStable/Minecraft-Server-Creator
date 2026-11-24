package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.BuildReview;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/** boat, pier and harbor (ADR 0099), and the review's boat and basin checks against them and against crude ones. */
class VesselStepsTest {
    /** Sand up to y 40, water to {@code sea}, air above; land where {@code height} is above the sea. */
    private static Canvas.WorldReader world(int sea, IntBinaryOperator height) {
        return r -> {
            int[] blocks = new int[Math.toIntExact(r.volume())];
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        int h = height.applyAsInt(x, z);
                        blocks[r.index(x, y, z)] = y < h ? 1 : y == h ? 2 : y <= sea ? 3 : 0;
                    }
                }
            }
            return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone", "minecraft:grass_block",
                    "minecraft:water"), blocks);
        };
    }

    private static Canvas draw(String steps, Canvas.WorldReader world) throws Exception {
        BuildScript.StructureFiles files = new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String w) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) throws InterruptedException {
                return world.read(region);
            }
        };
        List<Step> parsed = new BuildScript("world", files).parse(steps);
        return Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, world);
    }

    /** The world in {@code r} once the canvas is written. */
    private static BlockGrid after(Canvas c, Canvas.WorldReader world, Region r) throws Exception {
        BlockGrid g = world.read(r);
        List<String> palette = new ArrayList<>();
        Map<String, Integer> ids = new HashMap<>();
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    String s = c.planned(x, y, z);
                    String state = s != null ? s : g.stateAt(x, y, z);
                    blocks[r.index(x, y, z)] = ids.computeIfAbsent(state, k -> {
                        palette.add(k);
                        return palette.size() - 1;
                    });
                }
            }
        }
        return new BlockGrid(r, palette, blocks);
    }

    /** Blocks of {@code id} the canvas sets, whatever their properties. */
    private static long count(Canvas c, String id) {
        return c.counts().entrySet().stream().filter(e -> e.getKey().equals(id) || e.getKey().startsWith(id + "["))
                .mapToLong(Map.Entry::getValue).sum();
    }

    private static int width(Canvas c, int x, int y) {
        int n = 0;
        for (int z = -10; z <= 10; z++) {
            String s = c.planned(x, y, z);
            n += s != null && !s.equals("minecraft:air") ? 1 : 0;
        }
        return n;
    }

    private static final Canvas.WorldReader SEA = world(60, (x, z) -> 40);

    @Test
    void aSloopTapersSitsInTheWaterWithADryHoldAndCarriesItsSail() throws Exception {
        Canvas c = draw("boat 0,60,0 kind=sloop facing=east len=14", SEA);
        // Midship is wider than the bow and the stern, at the deck.
        int deck = 61;
        assertThat(width(c, 0, deck)).isGreaterThan(width(c, 6, deck)).isGreaterThan(width(c, 7, deck - 1));
        // The keel is below the waterline and the hold above it is dry.
        assertThat(c.planned(0, 59, 0)).startsWith("minecraft:spruce_log");
        assertThat(c.planned(0, 60, 0)).isEqualTo("minecraft:air");
        assertThat(count(c, "white_wool")).isGreaterThan(30);
        // The mast stands at least the hull's length above the deck, roughly.
        int top = 0;
        for (int y = 60; y < 100; y++) {
            String s = c.planned(1, y, 0);
            top = s != null && (s.contains("_log") || s.contains("_fence")) ? y : top;
        }
        assertThat(top - deck).isGreaterThanOrEqualTo(11);
        String review = BuildReview.review(after(c, SEA, Region.of("world", -14, 55, -8, 14, 80, 8)));
        assertThat(review).doesNotContain("box hull", "floating hull", "bare mast", "stump mast");
    }

    @Test
    void everyKindAndFacingDraws() throws Exception {
        for (String kind : List.of("rowboat", "sloop", "ship")) {
            for (String facing : List.of("north", "east", "south", "west")) {
                Canvas c = draw("boat 0,60,0 kind=" + kind + " facing=" + facing, SEA);
                assertThat(c.placed()).as(kind + " " + facing).isGreaterThan(20);
            }
        }
        Canvas ship = draw("boat 0,60,0 kind=ship facing=north", SEA);
        assertThat(count(ship, "white_wool")).isGreaterThan(150);
        String review = BuildReview.review(after(ship, SEA, Region.of("world", -10, 55, -24, 10, 90, 26)));
        assertThat(review).doesNotContain("box hull", "floating hull", "bare mast", "stump mast", "dark rooms");
        assertThatThrownBy(() -> draw("boat 0,60,0 kind=galleon", SEA)).isInstanceOf(ToolException.class)
                .hasMessageContaining("rowboat");
        assertThatThrownBy(() -> draw("boat 0,60,0 facing=up", SEA)).hasMessageContaining("facing");
    }

    @Test
    void aPlankBoxWithAStumpMastFailsTheReview() throws Exception {
        // Run 6's boat: a 12x5 box of planks laid on the water and a fence post 3 tall.
        Canvas c = draw("fill -6,61,-2 5,62,2 oak_planks\nfill 0,63,0 0,66,0 oak_fence[waterlogged=false]", SEA);
        String review = BuildReview.review(after(c, SEA, Region.of("world", -10, 55, -6, 10, 70, 6)));
        assertThat(review).contains("box hull: 1 boat", "floating hull: 1 boat", "bare mast: 1 boat", "stump mast: 1 boat");
    }

    @Test
    void aHarbourSlopesItsBanksAndAPitDoesNot() throws Exception {
        Canvas.WorldReader land = world(-100, (x, z) -> 64);
        Canvas h = draw("harbor 0,60,0 40,60,30 quay=north piers=2 seed=3", land);
        BlockGrid g = after(h, land, Region.of("world", -4, 50, -4, 44, 72, 34));
        assertThat(BuildReview.review(g)).doesNotContain("sheer banks", "square basin");
        // Water at the level, ladders down the quay, piers on posts.
        assertThat(h.planned(20, 60, 15)).isEqualTo("minecraft:water");
        assertThat(count(h, "ladder")).isGreaterThan(4);
        assertThat(count(h, "spruce_log")).isGreaterThan(8);
        // The south bank drops at most one block per column on the way down.
        int last = 64;
        for (int z = 30; z >= 15; z--) {
            int top = 50;
            for (int y = 64; y >= 50; y--) {
                String s = h.planned(20, y, z);
                s = s != null ? s : land.read(Region.of("world", 20, y, z, 20, y, z)).stateAt(20, y, z);
                if (!s.equals("minecraft:air") && !s.equals("minecraft:water")) {
                    top = y;
                    break;
                }
            }
            assertThat(last - top).as("z=" + z).isLessThanOrEqualTo(1);
            last = top;
        }
        Canvas pit = draw("fill 5,58,5 30,64,25 air\nfill 5,58,5 30,61,25 water", land);
        String review = BuildReview.review(after(pit, land, Region.of("world", 0, 50, 0, 35, 70, 30)));
        assertThat(review).contains("sheer banks: 1 body", "square basin: 1 body");
    }

    @Test
    void aPierStandsOnPostsDownToTheBed() throws Exception {
        Canvas p = draw("pier 0,61,-1 12,61,1", SEA);
        assertThat(p.planned(4, 61, 0)).isEqualTo("minecraft:spruce_planks");
        for (int y = 41; y <= 60; y++) {
            assertThat(p.planned(4, y, -1)).as("post at y " + y).startsWith("minecraft:spruce_log");
        }
        assertThat(count(p, "lantern")).isGreaterThanOrEqualTo(2);
        assertThatThrownBy(() -> draw("pier 0,61,0 12,62,1", SEA)).hasMessageContaining("one height");
        assertThatThrownBy(() -> draw("harbor 0,60,0 10,60,10 piers=2", SEA)).hasMessageContaining("quay=");
    }
}
