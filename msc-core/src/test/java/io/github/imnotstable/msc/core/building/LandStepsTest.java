package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** terrain, path, scatter and clump= (ADR 0075), drawn over a flat world: grass at y 0, a flower on some of it. */
class LandStepsTest {
    /** Grass at y 0, a poppy above every 5th column, air elsewhere. */
    private static BlockGrid flat(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    blocks[r.index(x, y, z)] = y < 0 ? 3 : y == 0 ? 1 : y == 1 && Math.floorMod(x + z, 5) == 0 ? 2 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block", "minecraft:poppy", "minecraft:dirt"),
                blocks);
    }

    private static Canvas draw(String steps, BuildScript.StructureFiles files) throws Exception {
        List<Step> parsed = new BuildScript("world", files).parse(steps);
        Canvas c = new Canvas(new Step.Group(parsed).bounds(), LandStepsTest::flat);
        for (Step s : parsed) {
            s.draw(c);
        }
        return c;
    }

    private static Canvas draw(String steps) throws Exception {
        return draw(steps, new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String world) {
                return TextGrid.parse("legend: l=oak_log o=oak_leaves\ny=0\n...\n.l.\n...\ny=1\nooo\nolo\nooo", world,
                        new BlockPos(0, 0, 0));
            }

            @Override
            public BlockGrid read(Region region) {
                return flat(region);
            }
        });
    }

    /** The highest y the canvas sets in a column, or MIN_VALUE. */
    private static int top(Canvas c, int x, int z) {
        Region b = c.bounds();
        for (int y = b.max().y(); y >= b.min().y(); y--) {
            String s = c.planned(x, y, z);
            if (s != null && !s.equals("minecraft:air") && !s.equals("minecraft:water")) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    @Test
    void terrainRisesUnevenlyAndEasesToTheGroundAtItsRim() throws Exception {
        Canvas c = draw("terrain 0,0,0 63,30,63 stone top=grass_block under=dirt seed=4");
        Set<Integer> heights = new HashSet<>();
        int high = Integer.MIN_VALUE;
        for (int z = 16; z < 48; z++) {
            for (int x = 16; x < 48; x++) {
                int t = top(c, x, z);
                heights.add(t);
                high = Math.max(high, t);
            }
        }
        assertThat(heights).hasSizeGreaterThan(8);
        assertThat(high).isGreaterThan(10).isLessThanOrEqualTo(30);
        // The corners lie outside the wavy rim: the land there stays at the ground, y 0.
        assertThat(top(c, 0, 0)).isLessThanOrEqualTo(0);
        assertThat(top(c, 63, 63)).isLessThanOrEqualTo(0);
        // Same seed, same land: a dry run and the real build agree.
        Canvas again = draw("terrain 0,0,0 63,30,63 stone top=grass_block under=dirt seed=4");
        assertThat(top(again, 30, 33)).isEqualTo(top(c, 30, 33));
    }

    @Test
    void terrainLayersTopUnderBodyAndShowsRockOnCliffs() throws Exception {
        Canvas c = draw("terrain 0,0,0 63,40,63 stone top=grass_block under=dirt depth=2 cliff=andesite "
                + "noise=ridged scale=24 edge=0 seed=9");
        int cliffs = 0;
        int grass = 0;
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                int t = top(c, x, z);
                String s = c.planned(x, t, z);
                cliffs += s.equals("minecraft:andesite") ? 1 : 0;
                grass += s.equals("minecraft:grass_block") ? 1 : 0;
                if (s.equals("minecraft:grass_block") && t >= 3) {
                    assertThat(c.planned(x, t - 1, z)).isIn("minecraft:dirt", "minecraft:andesite");
                    assertThat(c.planned(x, t - 3, z)).isEqualTo("minecraft:stone");
                }
            }
        }
        assertThat(cliffs).isPositive();
        assertThat(grass).isPositive();
    }

    @Test
    void terrainDigsBelowTheGroundAndFillsWater() throws Exception {
        Canvas c = draw("terrain 0,-10,0 47,20,47 stone top=grass_block bed=sand ground=0 water=-1 edge=0 scale=20 "
                + "seed=2");
        int water = 0;
        int sandBeds = 0;
        for (int z = 0; z < 48; z++) {
            for (int x = 0; x < 48; x++) {
                if ("minecraft:water".equals(c.planned(x, -1, z))) {
                    water++;
                    int t = top(c, x, z);
                    sandBeds += "minecraft:sand".equals(c.planned(x, t, z)) ? 1 : 0;
                    assertThat(c.planned(x, 0, z)).isEqualTo("minecraft:air"); // dug out, above the water level
                }
            }
        }
        assertThat(water).isPositive();
        assertThat(sandBeds).isEqualTo(water);
    }

    @Test
    void terracedNoiseHasFlatShelves() throws Exception {
        Canvas c = draw("terrain 0,0,0 63,32,63 terracotta noise=terraced steps=4 edge=0 seed=1");
        Set<Integer> heights = new HashSet<>();
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                heights.add(top(c, x, z));
            }
        }
        // Shelves at multiples of 8, with few heights between them.
        long shelves = heights.stream().filter(h -> h % 8 == 0).count();
        assertThat(shelves).isGreaterThanOrEqualTo(2);
    }

    @Test
    void unknownNoiseAndBadWaterAreRefused() {
        assertThatThrownBy(() -> draw("terrain 0,0,0 9,9,9 stone noise=volcano")).isInstanceOf(ToolException.class)
                .hasMessageContaining("fbm, ridged, terraced, waves");
        assertThatThrownBy(() -> draw("terrain 0,0,0 9,9,9 stone water=40")).hasMessageContaining("water must be 0 to 0");
    }

    @Test
    void pathFollowsTheGroundCurvesAndClearsPlants() throws Exception {
        Canvas c = draw("path 0,0,0 60,0,0 gravel w=3 wander=6 seed=3");
        Set<Integer> rows = new HashSet<>();
        int placed = 0;
        for (int x = 0; x <= 60; x++) {
            for (int z = -10; z <= 10; z++) {
                if ("minecraft:gravel".equals(c.planned(x, 0, z))) {
                    rows.add(z);
                    placed++;
                    assertThat(c.planned(x, 1, z)).isIn(null, "minecraft:air"); // the poppy is cleared, nothing else set
                }
                assertThat(c.planned(x, 2, z)).isNull();
            }
        }
        assertThat(placed).isGreaterThan(100);
        // It wanders: the gravel spans more rows than a straight 3-wide line.
        assertThat(rows.size()).isGreaterThan(5);
        assertThat(c.planned(0, 0, 0)).isEqualTo("minecraft:gravel");
        assertThat(c.planned(60, 0, 0)).isEqualTo("minecraft:gravel");
    }

    @Test
    void aStreamIsDugAsABowlOverItsBed() throws Exception {
        Canvas c = draw("path 0,0,0 30,0,0 water w=5 wander=0 depth=2 bed=gravel");
        assertThat(c.planned(15, 0, 0)).isEqualTo("minecraft:water");
        assertThat(c.planned(15, -1, 0)).isEqualTo("minecraft:water");
        assertThat(c.planned(15, -2, 0)).isEqualTo("minecraft:gravel");
    }

    @Test
    void scatterPutsThingsOnTheGround() throws Exception {
        Canvas blocks = draw("scatter 0,-2,0 63,10,63 mossy_cobblestone spacing=8 seed=5");
        int placed = 0;
        for (int z = 0; z < 64; z++) {
            for (int x = 0; x < 64; x++) {
                if (blocks.planned(x, 1, z) != null) {
                    placed++;
                    assertThat(blocks.planned(x, 1, z)).isEqualTo("minecraft:mossy_cobblestone");
                }
            }
        }
        assertThat(placed).isBetween(40, 64); // one per 8x8 cell, minus spots where a flower stands
        Canvas trees = draw("scatter 0,-2,0 31,10,31 file=tree.txt spacing=10 seed=5");
        int logs = 0;
        for (int z = -2; z < 34; z++) {
            for (int x = -2; x < 34; x++) {
                if ("minecraft:oak_log".equals(trees.planned(x, 1, z))) {
                    logs++;
                    assertThat(trees.planned(x, 2, z)).isEqualTo("minecraft:oak_log");
                    assertThat(trees.planned(x, 0, z)).isNull(); // stands on the grass, which stays
                }
            }
        }
        assertThat(logs).isGreaterThan(4);
        assertThat(draw("scatter 0,-2,0 31,10,31 stone on=sand").placed()).isZero();
    }

    @Test
    void clumpKeepsTheWeightsButMakesPatches() {
        BlockPattern mix = BlockPattern.parse("grass_block:3,coarse_dirt:1", 7);
        BlockPattern patches = mix.withClump(6);
        int dirt = 0;
        int sameNeighbour = 0;
        int sameNeighbourNoClump = 0;
        int n = 0;
        for (int z = 0; z < 200; z++) {
            for (int x = 0; x < 200; x++) {
                dirt += patches.at(x, 0, z).equals("minecraft:coarse_dirt") ? 1 : 0;
                sameNeighbour += patches.at(x, 0, z).equals(patches.at(x + 1, 0, z)) ? 1 : 0;
                sameNeighbourNoClump += mix.at(x, 0, z).equals(mix.at(x + 1, 0, z)) ? 1 : 0;
                n++;
            }
        }
        assertThat(dirt / (double) n).isBetween(0.15, 0.35);
        assertThat(sameNeighbour).isGreaterThan(sameNeighbourNoClump + n / 10);
    }
}
