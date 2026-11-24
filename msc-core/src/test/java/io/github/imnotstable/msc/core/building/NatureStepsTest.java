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

/** flora (ADR 0079, 0103: your own plant mix, no presets), drawn over grass at y 0 with a stone wall at x 20. */
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
    void floraPlantsItsMixOnlyOnItsSoilInPatches() throws Exception {
        Canvas c = draw("flora 0,0,0 40,1,40 short_grass:40,fern:10,poppy:3,cornflower:2 soil=grass_block chance=50 seed=2");
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
        assertThat(ids).contains("minecraft:short_grass", "minecraft:fern", "minecraft:poppy");
        assertThat(all(draw("flora 0,0,0 10,1,10 short_grass soil=sand chance=100"))).isEmpty();
    }

    @Test
    void presetsAreGone() {
        assertThatThrownBy(() -> draw("flora 0,0,0 4,1,4 style=meadow")).isInstanceOf(ToolException.class)
                .hasMessageContaining("your own plant mix");
        for (String step : List.of("tree 0,1,0 kind=oak", "forest 0,0,0 4,1,4", "furnish 1,1,1 4,4,4 room=bedroom",
                "roof 0,0,0 4,0,4 oak_stairs", "feature 0,1,0 fancy_oak")) {
            assertThatThrownBy(() -> draw(step)).as(step).isInstanceOf(ToolException.class)
                    .hasMessageContaining("unknown step");
        }
    }
}
