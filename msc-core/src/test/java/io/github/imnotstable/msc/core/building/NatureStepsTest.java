package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.TextGrid;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** tree, forest and flora (ADR 0079), drawn over grass at y 0 with a stone wall at x 20. */
class NatureStepsTest {
    private static BlockGrid world(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    blocks[r.index(x, y, z)] = y < 0 ? 2 : y == 0 ? 1 : x == 20 && y < 12 ? 3 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:grass_block", "minecraft:dirt", "minecraft:stone"),
                blocks);
    }

    private static Canvas draw(String steps) throws Exception {
        BuildScript.StructureFiles files = new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String world) {
                return TextGrid.parse("y=0\n#", world, new BlockPos(0, 0, 0));
            }

            @Override
            public BlockGrid read(Region region) {
                return world(region);
            }
        };
        List<Step> parsed = new BuildScript("world", files).parse(steps);
        return Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, NatureStepsTest::world);
    }

    private static List<String> all(Canvas c) {
        List<String> out = new ArrayList<>();
        Region b = c.bounds();
        for (int y = b.min().y(); y <= b.max().y(); y++) {
            for (int z = b.min().z(); z <= b.max().z(); z++) {
                for (int x = b.min().x(); x <= b.max().x(); x++) {
                    String s = c.planned(x, y, z);
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        }
        return out;
    }

    @Test
    void everyKindAndSizeGrowsPlaceableBlocks() throws Exception {
        for (String kind : Nature.KINDS) {
            for (String size : Nature.SIZES) {
                Canvas c = draw("tree 0,1,0 kind=" + kind + " size=" + size + " seed=3");
                List<String> blocks = all(c);
                assertThat(blocks).as(kind + " " + size).isNotEmpty();
                // Every block says how it is placed, so build_batch would take the tree as it is.
                BlockRules.require(blocks);
                assertThat(blocks).as(kind).noneMatch(s -> s.contains("leaves") && !s.contains("persistent=true"));
            }
        }
    }

    @Test
    void trunkStandsOnTheBaseAndLogsTurnAlongBranches() throws Exception {
        Canvas c = draw("tree 0,1,0 kind=oak size=huge seed=8");
        assertThat(c.planned(0, 1, 0)).isEqualTo("minecraft:oak_log[axis=y]");
        assertThat(all(c)).anyMatch(s -> s.equals("minecraft:oak_log[axis=x]") || s.equals("minecraft:oak_log[axis=z]"));
    }

    @Test
    void theSeedVariesTheTreeAndRepeatsIt() throws Exception {
        assertThat(all(draw("tree 0,1,0 kind=cherry seed=1"))).isEqualTo(all(draw("tree 0,1,0 kind=cherry seed=1")));
        assertThat(all(draw("tree 0,1,0 kind=cherry seed=1"))).isNotEqualTo(all(draw("tree 0,1,0 kind=cherry seed=2")));
    }

    @Test
    void leavesDoNotEatAWall() throws Exception {
        Canvas c = draw("tree 18,1,0 kind=oak size=large seed=4");
        for (int y = 1; y < 12; y++) {
            for (int z = -10; z <= 10; z++) {
                String s = c.planned(20, y, z);
                assertThat(s == null || s.contains("log")).as("x=20 y=" + y + " z=" + z + ": " + s).isTrue();
            }
        }
    }

    @Test
    void logAndLeavesCanBeSwapped() throws Exception {
        List<String> blocks = all(draw("tree 0,1,0 kind=oak size=small log=stripped_birch_log leaves=cherry_leaves"));
        assertThat(blocks).contains("minecraft:stripped_birch_log[axis=y]");
        assertThat(blocks).anyMatch(s -> s.startsWith("minecraft:cherry_leaves"));
    }

    @Test
    void forestStandsTreesOnTheGroundOnly() throws Exception {
        Canvas c = draw("forest 0,0,0 40,0,40 kind=oak:2,spruce:1,bush:1 spacing=8 chance=100 seed=5");
        int trunks = 0;
        for (int z = 0; z <= 40; z++) {
            for (int x = 0; x <= 40; x++) {
                String s = c.planned(x, 1, z);
                if (s != null && s.contains("log")) {
                    trunks++;
                }
                assertThat(c.planned(x, 0, z)).as("ground at " + x + "," + z).isNull();
            }
        }
        assertThat(trunks).isGreaterThan(8);
    }

    @Test
    void floraPlantsOnlyOnItsSoilInPatches() throws Exception {
        Canvas c = draw("flora 0,0,0 40,1,40 style=meadow chance=50 seed=2");
        Set<String> ids = new HashSet<>();
        int count = 0;
        for (int z = 0; z <= 40; z++) {
            for (int x = 0; x <= 40; x++) {
                String s = c.planned(x, 1, z);
                if (s != null) {
                    count++;
                    ids.add(s.replaceAll("\\[.*", ""));
                }
            }
        }
        assertThat(count).isBetween(200, 1400);
        assertThat(ids).contains("minecraft:short_grass").hasSizeGreaterThan(4);
        // Sand does not take meadow plants.
        assertThat(all(draw("flora 0,0,0 10,1,10 style=meadow soil=sand chance=100"))).isEmpty();
    }

    @Test
    void unknownKindsAndStylesAreNamed() {
        assertThatThrownBy(() -> draw("tree 0,1,0 kind=baobab")).isInstanceOf(ToolException.class)
                .hasMessageContaining("willow");
        assertThatThrownBy(() -> draw("flora 0,0,0 4,1,4 style=moon")).isInstanceOf(ToolException.class)
                .hasMessageContaining("meadow");
        assertThatThrownBy(() -> draw("flora 0,0,0 4,1,4")).isInstanceOf(ToolException.class)
                .hasMessageContaining("style=");
    }
}
