package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/** smooth, erode, blend, paint, river, tunnel and cave (ADR 0080) over a heightmap world of stone under grass. */
class BrushStepsTest {
    /** A world whose ground top at x, z is {@code height}: stone, then grass on top, air above. */
    private static Canvas.WorldReader land(IntBinaryOperator height) {
        return r -> {
            int[] blocks = new int[Math.toIntExact(r.volume())];
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        int h = height.applyAsInt(x, z);
                        blocks[r.index(x, y, z)] = y < h ? 1 : y == h ? 2 : 0;
                    }
                }
            }
            return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone", "minecraft:grass_block"), blocks);
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

    /** The top solid block's y after the canvas, reading the world under it. */
    private static int top(Canvas c, Canvas.WorldReader world, int x, int z) throws Exception {
        BlockGrid g = world.read(c.bounds());
        for (int y = c.bounds().max().y(); y >= c.bounds().min().y(); y--) {
            String s = c.planned(x, y, z);
            if (s == null) {
                s = g.stateAt(x, y, z);
            }
            if (!s.equals("minecraft:air") && !s.equals("minecraft:water")) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private static String at(Canvas c, Canvas.WorldReader world, int x, int y, int z) throws Exception {
        String s = c.planned(x, y, z);
        return s != null ? s : world.read(Region.of("world", x, y, z, x, y, z)).stateAt(x, y, z);
    }

    @Test
    void smoothLowersASpikeAndKeepsGrassOnTop() throws Exception {
        Canvas.WorldReader spike = land((x, z) -> x == 10 && z == 10 ? 20 : 5);
        Canvas c = draw("smooth 0,0,0 20,30,20 radius=2 strength=3", spike);
        assertThat(top(c, spike, 10, 10)).isLessThan(12);
        assertThat(at(c, spike, 10, top(c, spike, 10, 10), 10)).isEqualTo("minecraft:grass_block");
        // Flat ground far from the spike stays put.
        assertThat(top(c, spike, 2, 2)).isEqualTo(5);
    }

    @Test
    void erodeMeltsALoneBlockAndFillsAPit() throws Exception {
        Canvas.WorldReader bumpy = land((x, z) -> x == 5 && z == 5 ? 6 : x == 8 && z == 8 ? 3 : 4);
        Canvas melted = draw("erode 0,0,0 12,10,12 mode=melt faces=4", bumpy);
        assertThat(top(melted, bumpy, 5, 5)).isEqualTo(4); // the 2-block spike is gone
        Canvas filled = draw("erode 0,0,0 12,10,12 mode=fill faces=5", bumpy);
        assertThat(top(filled, bumpy, 8, 8)).isEqualTo(4);
        assertThatThrownBy(() -> draw("erode 0,0,0 4,4,4 mode=chew", bumpy)).isInstanceOf(ToolException.class)
                .hasMessageContaining("melt");
    }

    @Test
    void blendSwallowsALoneOddBlock() throws Exception {
        Canvas.WorldReader flat = land((x, z) -> 4);
        Canvas c = draw("set 5,2,5 dirt\nblend 3,0,3 7,4,7", flat);
        assertThat(at(c, flat, 5, 2, 5)).isEqualTo("minecraft:stone");
    }

    @Test
    void paintBySlopeTouchesOnlySteepGround() throws Exception {
        Canvas.WorldReader cliff = land((x, z) -> x < 10 ? 4 : 14);
        Canvas c = draw("paint 0,0,0 20,20,20 andesite slope=60-90", cliff);
        assertThat(at(c, cliff, 9, 4, 5)).isEqualTo("minecraft:andesite");
        assertThat(at(c, cliff, 3, 4, 5)).isEqualTo("minecraft:grass_block");
        Canvas all = draw("paint 0,0,0 20,20,20 podzol depth=2", cliff);
        assertThat(at(all, cliff, 3, 4, 5)).isEqualTo("minecraft:podzol");
        assertThat(at(all, cliff, 3, 3, 5)).isEqualTo("minecraft:podzol");
        assertThat(at(all, cliff, 3, 2, 5)).isEqualTo("minecraft:stone");
    }

    @Test
    void riverKeepsOneWaterLevelThatOnlyFallsDownstream() throws Exception {
        // Ground falls from 20 at x 0 to 8 at x 60.
        Canvas.WorldReader slope = land((x, z) -> 20 - x / 5);
        Canvas c = draw("river 0,20,10 30,14,10 60,8,10 w=5 depth=2 wander=0 bed=gravel", slope);
        int last = Integer.MAX_VALUE;
        for (int x = 2; x <= 58; x += 4) {
            int water = Integer.MIN_VALUE;
            for (int y = 30; y >= -5; y--) {
                if ("minecraft:water".equals(c.planned(x, y, 10))) {
                    water = y;
                    break;
                }
            }
            assertThat(water).as("water at x=" + x).isGreaterThan(Integer.MIN_VALUE).isLessThanOrEqualTo(last);
            last = water;
            // One level across the channel.
            for (int z = 9; z <= 11; z++) {
                assertThat(c.planned(x, water, z)).isEqualTo("minecraft:water");
                assertThat(c.planned(x, water + 1, z)).isNotEqualTo("minecraft:water");
            }
        }
    }

    @Test
    void aRiverBedIsTheAgentsOwnChoice() {
        assertThatThrownBy(() -> draw("river 0,20,10 60,8,10", land((x, z) -> 20)))
                .hasMessageContaining("river needs bed=");
    }

    @Test
    void tunnelAndCavesCarveInsideTheGround() throws Exception {
        Canvas.WorldReader deep = land((x, z) -> 40);
        Canvas t = draw("tunnel 0,20,0 20,15,10 40,20,0 r=3", deep);
        assertThat(t.planned(20, 15, 10)).isEqualTo("minecraft:air");
        Canvas k = draw("cave 0,0,0 40,40,40 density=60 seed=4", deep);
        long carved = k.counts().getOrDefault("air", 0L);
        assertThat(carved).isGreaterThan(500);
        // The roof stays whole: nothing carved within 4 of the surface.
        for (int x = 0; x <= 40; x++) {
            for (int z = 0; z <= 40; z++) {
                assertThat(k.planned(x, 37, z)).isNull();
            }
        }
    }
}
