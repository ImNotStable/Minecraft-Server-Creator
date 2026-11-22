package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatReplaceReadTest {
    /** The world: air everywhere except stone at x=4. */
    private static BlockGrid world(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int x = r.min().x(); x <= r.max().x(); x++) {
            if (x == 4) {
                blocks[r.index(x, r.min().y(), r.min().z())] = 1;
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone"), blocks);
    }

    @Test
    void repeatedReplaceStepReadsTheWorldOnce() throws Exception {
        List<Step> steps = new BuildScript("world", new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String world) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) {
                throw new AssertionError();
            }
        }).parse("set 0,65,0 lantern[hanging=false] replace=air repeat=8 step=1,0,0");
        List<Region> reads = new ArrayList<>();
        Canvas c = new Canvas(new Step.Group(steps).bounds(), r -> {
            reads.add(r);
            return world(r);
        });
        for (Step s : steps) {
            s.draw(c);
        }
        assertThat(reads).hasSize(1);
        assertThat(c.placed()).isEqualTo(7);
        assertThat(c.planned(4, 65, 0)).isNull();
        assertThat(c.planned(7, 65, 0)).isEqualTo("minecraft:lantern[hanging=false]");
    }

    @Test
    void stepsOutsideAReadGroupStillReadTheirOwnBounds() throws Exception {
        List<Region> reads = new ArrayList<>();
        Canvas c = new Canvas(Region.of("world", 0, 65, 0, 9, 65, 0), r -> {
            reads.add(r);
            return world(r);
        });
        new Step.Set("world", List.of(new io.github.imnotstable.msc.protocol.world.BlockPos(4, 65, 0)),
                List.of("minecraft:lantern"), List.of("minecraft:air")).draw(c);
        assertThat(reads).containsExactly(Region.of("world", 4, 65, 0, 4, 65, 0));
        assertThat(c.placed()).isZero();
    }

    @Test
    void aStepAfterARepeatReadsOnlyItsOwnBounds() throws Exception {
        BuildScript script = new BuildScript("world", new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String world) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) {
                throw new AssertionError();
            }
        });
        List<Step> steps = script.parse("fill 0,64,0 9,64,9 stone_bricks repeat=6 step=0,1,0\n"
                + "set 5,68,5 torch replace=air");
        List<Region> reads = new ArrayList<>();
        Canvas c = new Canvas(new Step.Group(steps).bounds(), r -> {
            reads.add(r);
            return world(r);
        });
        for (Step s : steps) {
            s.draw(c);
        }
        assertThat(reads).containsExactly(Region.of("world", 5, 68, 5, 5, 68, 5));
    }
}
