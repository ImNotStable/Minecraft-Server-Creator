package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.agent.common.EditHistory;
import io.github.imnotstable.msc.agent.common.MainThread;
import io.github.imnotstable.msc.agent.common.WorldOps;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared operations on real Minecraft block states (parsing, serializing, identity). The
 * operations themselves are tested in msc-agent-common.
 */
class WorldOpsTest {
    private TestLevel world;
    private WorldOps<?> ops;

    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @BeforeEach
    void setUp() {
        world = new TestLevel();
        ops = new WorldOps<>(world, MainThread.inline(), new EditHistory<>(1_000_000));
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("world", x1, y1, z1, x2, y2, z2);
    }

    private Ops.EditResult fill(Region region, String state) {
        int[] cells = new int[Math.toIntExact(region.volume())];
        return ops.write(new Ops.WriteParams(new BlockGrid(region, List.of(state), cells), false));
    }

    @Test
    void writeKeepsBlockStatePropertiesAndSkipsUnchangedBlocks() {
        fill(box(5, 70, 5, 5, 70, 5), "minecraft:oak_stairs[facing=east]");
        fill(box(6, 70, 5, 6, 70, 5), "glass");

        assertThat(BlockStates.serialize(world.get(5, 70, 5))).contains("facing=east");
        assertThat(world.get(6, 70, 5).getBlock()).isEqualTo(Blocks.GLASS);
        assertThat(fill(box(5, 70, 5, 6, 70, 5), "minecraft:glass").changed()).isEqualTo(1);
    }

    @Test
    void readThenWriteCopiesAStructure() {
        fill(box(0, 70, 0, 2, 72, 2), "minecraft:bricks");
        fill(box(1, 72, 1, 1, 72, 1), "minecraft:oak_stairs[facing=north]");
        BlockGrid copy = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 72, 2), false)).grid();

        ops.write(new Ops.WriteParams(new BlockGrid(box(10, 70, 0, 12, 72, 2), copy.palette(), copy.blocks()), false));

        assertThat(copy.palette()).contains("minecraft:bricks");
        assertThat(world.get(10, 70, 0).getBlock()).isEqualTo(Blocks.BRICKS);
        assertThat(world.get(11, 72, 1)).isSameAs(world.get(1, 72, 1));
    }

    @Test
    void anUnknownBlockNamesItself() {
        assertThatThrownBy(() -> fill(box(0, 70, 0, 0, 70, 0), "minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
    }
}
