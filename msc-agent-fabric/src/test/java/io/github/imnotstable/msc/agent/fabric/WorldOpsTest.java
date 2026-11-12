package io.github.imnotstable.msc.agent.fabric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldOpsTest {
    private TestLevel world;
    private WorldOps ops;

    @BeforeAll
    static void boot() {
        Minecraft.bootstrap();
    }

    @BeforeEach
    void setUp() {
        world = new TestLevel();
        ops = new WorldOps(world, MainThread.inline(), new EditHistory(1_000_000));
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("world", x1, y1, z1, x2, y2, z2);
    }

    private Block at(int x, int y, int z) {
        return world.get(x, y, z).getBlock();
    }

    private void put(int x, int y, int z, Block block) {
        world.set(x, y, z, block.defaultBlockState(), false);
    }

    @Test
    void fillChangesEveryBlockAndCountsOnlyRealChanges() {
        put(1, 70, 1, Blocks.STONE);

        Ops.EditResult result = ops.fill(new Ops.FillParams(box(0, 70, 0, 2, 71, 2), "minecraft:stone", null));

        assertThat(result.changed()).isEqualTo(17);
        assertThat(at(0, 70, 0)).isEqualTo(Blocks.STONE);
        assertThat(at(2, 71, 2)).isEqualTo(Blocks.STONE);
        assertThat(at(3, 70, 0)).isEqualTo(Blocks.AIR);
    }

    @Test
    void undoRestoresTheEditAndCanItselfBeUndone() {
        put(0, 70, 0, Blocks.DIRT);
        Ops.EditResult fill = ops.fill(new Ops.FillParams(box(0, 70, 0, 1, 70, 0), "minecraft:stone", null));

        Ops.EditResult undo = ops.undo(new Ops.UndoParams(fill.editId()));

        assertThat(undo.changed()).isEqualTo(2);
        assertThat(at(0, 70, 0)).isEqualTo(Blocks.DIRT);
        assertThat(at(1, 70, 0)).isEqualTo(Blocks.AIR);

        ops.undo(new Ops.UndoParams(undo.editId()));
        assertThat(at(0, 70, 0)).isEqualTo(Blocks.STONE);
        assertThatThrownBy(() -> ops.undo(new Ops.UndoParams(fill.editId())))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void fillWithOnlyReplaceLeavesOtherBlocks() {
        put(0, 70, 0, Blocks.DIRT);
        put(1, 70, 0, Blocks.STONE);

        Ops.EditResult result = ops.fill(new Ops.FillParams(box(0, 70, 0, 2, 70, 0), "minecraft:gold_block",
                List.of("minecraft:dirt")));

        assertThat(result.changed()).isEqualTo(1);
        assertThat(at(0, 70, 0)).isEqualTo(Blocks.GOLD_BLOCK);
        assertThat(at(1, 70, 0)).isEqualTo(Blocks.STONE);
        assertThat(at(2, 70, 0)).isEqualTo(Blocks.AIR);
    }

    @Test
    void writeSkipsCellsMarkedMinusOne() {
        Region r = box(0, 70, 0, 1, 70, 1);
        // Y-Z-X order: (0,0) (1,0) (0,1) (1,1)
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:stone", "minecraft:oak_planks"), new int[]{0, -1, 1, 0});

        Ops.EditResult result = ops.write(new Ops.WriteParams(grid, false));

        assertThat(result.changed()).isEqualTo(3);
        assertThat(at(0, 70, 0)).isEqualTo(Blocks.STONE);
        assertThat(at(1, 70, 0)).isEqualTo(Blocks.AIR);
        assertThat(at(0, 70, 1)).isEqualTo(Blocks.OAK_PLANKS);
        assertThat(at(1, 70, 1)).isEqualTo(Blocks.STONE);
    }

    @Test
    void setBlocksKeepsBlockStateProperties() {
        ops.setBlocks(new Ops.SetBlocksParams("world", List.of(
                new Ops.BlockChange(5, 70, 5, "minecraft:oak_stairs[facing=east]"),
                new Ops.BlockChange(6, 70, 5, "minecraft:glass")), false));

        assertThat(BlockStates.serialize(world.get(5, 70, 5))).contains("facing=east");
        assertThat(at(6, 70, 5)).isEqualTo(Blocks.GLASS);
    }

    @Test
    void readReturnsAPaletteGridInYzxOrder() {
        put(1, 70, 0, Blocks.STONE);
        put(0, 71, 1, Blocks.GLASS);

        Ops.ReadResult result = ops.read(new Ops.ReadParams(box(0, 70, 0, 1, 71, 1), false));

        BlockGrid grid = result.grid();
        assertThat(grid.blocks()).hasSize(8);
        assertThat(grid.stateAt(1, 70, 0)).isEqualTo("minecraft:stone");
        assertThat(grid.stateAt(0, 71, 1)).isEqualTo("minecraft:glass");
        assertThat(grid.stateAt(0, 70, 0)).isEqualTo("minecraft:air");
        assertThat(grid.palette()).hasSize(3);
        assertThat(result.entities()).isEmpty();
    }

    @Test
    void readOutsideTheBuildHeightGivesVoidAir() {
        int top = world.maxY();
        BlockGrid grid = ops.read(new Ops.ReadParams(box(0, top - 1, 0, 0, top, 0), false)).grid();
        assertThat(grid.stateAt(0, top, 0)).isEqualTo(ReadJob.OUTSIDE_WORLD);
    }

    @Test
    void readThenWriteCopiesAStructure() {
        ops.fill(new Ops.FillParams(box(0, 70, 0, 2, 72, 2), "minecraft:bricks", null));
        ops.setBlocks(new Ops.SetBlocksParams("world", List.of(new Ops.BlockChange(1, 72, 1, "minecraft:glowstone")), false));
        BlockGrid copy = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 72, 2), false)).grid();

        Region target = box(10, 70, 0, 12, 72, 2);
        ops.write(new Ops.WriteParams(new BlockGrid(target, copy.palette(), copy.blocks()), false));

        assertThat(at(10, 70, 0)).isEqualTo(Blocks.BRICKS);
        assertThat(at(11, 72, 1)).isEqualTo(Blocks.GLOWSTONE);
    }

    @Test
    void heightmapGivesTheTopBlockPerColumn() {
        put(0, 80, 0, Blocks.STONE);
        put(1, 75, 0, Blocks.STONE);

        Ops.HeightmapResult result = ops.heightmap(new Ops.HeightmapParams("world", new BlockPos(1, 0, 0), new BlockPos(0, 0, 1)));

        assertThat(result.minX()).isZero();
        assertThat(result.sizeX()).isEqualTo(2);
        assertThat(result.sizeZ()).isEqualTo(2);
        assertThat(result.heights()[0]).isEqualTo(80);
        assertThat(result.heights()[1]).isEqualTo(75);
    }

    @Test
    void reportsClearErrors() {
        assertThatThrownBy(() -> ops.fill(new Ops.FillParams(Region.of("nether", 0, 0, 0, 1, 1, 1), "minecraft:stone", null)))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
        assertThatThrownBy(() -> ops.fill(new Ops.FillParams(box(0, 0, 0, 200, 100, 200), "minecraft:stone", null)))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThatThrownBy(() -> ops.fill(new Ops.FillParams(box(0, 70, 0, 0, 70, 0), "minecraft:not_a_block", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        int top = world.maxY();
        assertThatThrownBy(() -> ops.fill(new Ops.FillParams(box(0, top, 0, 0, top, 0), "minecraft:stone", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("build height");
    }

    @Test
    void physicsFollowsTheRequestAndFillNeverUsesIt() {
        ops.setBlocks(new Ops.SetBlocksParams("world", List.of(new Ops.BlockChange(0, 70, 0, "minecraft:sand")), true));
        assertThat(world.lastPhysics).isTrue();

        ops.fill(new Ops.FillParams(box(0, 71, 0, 0, 71, 0), "minecraft:sand", null));
        assertThat(world.lastPhysics).isFalse();
    }

    @Test
    void readListsEntitiesInsideTheRegionOnlyWhenAsked() {
        world.entities.add(new Ops.EntitySummary("minecraft:pig", 1.5, 70, 1.5, null));
        world.entities.add(new Ops.EntitySummary("minecraft:cow", 50.5, 70, 50.5, "Bessie"));

        assertThat(ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), true)).entities())
                .extracting(Ops.EntitySummary::type).containsExactly("minecraft:pig");
        assertThat(ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), false)).entities()).isEmpty();
    }

    @Test
    void entitiesAreSortedFilteredAndCounted() {
        world.entityInfos.add(new Ops.EntityInfo("minecraft:zombie", "b", 2.5, 70, 2.5, "Brute", List.of("wave"), 7.0, 20.0));
        world.entityInfos.add(new Ops.EntityInfo("minecraft:pig", "a", 1.5, 70, 1.5, null, List.of(), 10.0, 10.0));
        world.entityInfos.add(new Ops.EntityInfo("minecraft:zombie", "c", 1.5, 70, 1.5, null, List.of(), 20.0, 20.0));
        world.entityInfos.add(new Ops.EntityInfo("minecraft:zombie", "d", 50.5, 70, 50.5, null, List.of(), 20.0, 20.0));

        Ops.EntitiesResult all = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), null, 0));
        Ops.EntitiesResult zombies = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), "minecraft:zombie", 1));

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.entities()).extracting(Ops.EntityInfo::uuid).containsExactly("a", "c", "b");
        assertThat(zombies.total()).isEqualTo(2);
        assertThat(zombies.entities()).extracting(Ops.EntityInfo::uuid).containsExactly("c");
    }
}
