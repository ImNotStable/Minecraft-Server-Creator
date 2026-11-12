package io.github.imnotstable.msc.agent.minestom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.time.Duration;
import java.util.List;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.StringBinaryTag;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

/** World operations on a real, empty Minestom instance (no network, no ticking needed). */
@EnvTest
class WorldOpsTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Instance world;
    private WorldOps ops;

    private void setUp(Env env) {
        world = env.createEmptyInstance();
        MscAgent.name(world, "world");
        ops = new WorldOps(new Worlds(() -> env.process().instance().getInstances()), MainThread.inline(),
                new EditHistory(1_000_000), TIMEOUT);
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("world", x1, y1, z1, x2, y2, z2);
    }

    /** One block over the whole region, through {@code world.write}, the only block-writing op. */
    private Ops.EditResult fill(Region region, String state) {
        return write(region, state, false);
    }

    private Ops.EditResult write(Region region, String state, boolean physics) {
        int[] cells = new int[Math.toIntExact(region.volume())];
        return ops.write(new Ops.WriteParams(new BlockGrid(region, List.of(state), cells), physics));
    }

    private Block at(int x, int y, int z) {
        return world.getBlock(x, y, z);
    }

    private void set(int x, int y, int z, Block block) {
        world.loadChunk(x >> 4, z >> 4).join();
        world.setBlock(x, y, z, block);
    }

    @Test
    void writeChangesEveryBlockAndCountsOnlyRealChanges(Env env) {
        setUp(env);
        set(1, 70, 1, Block.STONE);

        Ops.EditResult result = fill(box(0, 70, 0, 2, 71, 2), "minecraft:stone");

        assertThat(result.changed()).isEqualTo(17);
        assertThat(at(0, 70, 0)).isEqualTo(Block.STONE);
        assertThat(at(2, 71, 2)).isEqualTo(Block.STONE);
        assertThat(at(3, 70, 0)).isEqualTo(Block.AIR);
    }

    @Test
    void undoRestoresTheEditAndCanItselfBeUndone(Env env) {
        setUp(env);
        set(0, 70, 0, Block.DIRT);
        Ops.EditResult fill = fill(box(0, 70, 0, 1, 70, 0), "minecraft:stone");

        Ops.EditResult undo = ops.undo(new Ops.UndoParams(fill.editId()));

        assertThat(undo.changed()).isEqualTo(2);
        assertThat(at(0, 70, 0)).isEqualTo(Block.DIRT);
        assertThat(at(1, 70, 0)).isEqualTo(Block.AIR);

        ops.undo(new Ops.UndoParams(undo.editId()));
        assertThat(at(0, 70, 0)).isEqualTo(Block.STONE);
        assertThatThrownBy(() -> ops.undo(new Ops.UndoParams(fill.editId())))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void writeSkipsCellsMarkedMinusOne(Env env) {
        setUp(env);
        Region r = box(0, 70, 0, 1, 70, 1);
        // Y-Z-X order: (0,0) (1,0) (0,1) (1,1)
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:stone", "minecraft:oak_planks"), new int[]{0, -1, 1, 0});

        Ops.EditResult result = ops.write(new Ops.WriteParams(grid, false));

        assertThat(result.changed()).isEqualTo(3);
        assertThat(at(0, 70, 0)).isEqualTo(Block.STONE);
        assertThat(at(1, 70, 0)).isEqualTo(Block.AIR);
        assertThat(at(0, 70, 1)).isEqualTo(Block.OAK_PLANKS);
        assertThat(at(1, 70, 1)).isEqualTo(Block.STONE);
    }

    @Test
    void writeKeepsBlockStateProperties(Env env) {
        setUp(env);
        fill(box(5, 70, 5, 5, 70, 5), "minecraft:oak_stairs[facing=east]");
        fill(box(40, 70, 5, 40, 70, 5), "minecraft:glass");

        assertThat(at(5, 70, 5).getProperty("facing")).isEqualTo("east");
        assertThat(at(40, 70, 5)).isEqualTo(Block.GLASS);
    }

    @Test
    void readReportsSignTextFromBlockNbtOnlyWhenAsked(Env env) {
        setUp(env);
        CompoundBinaryTag front = CompoundBinaryTag.builder().put("messages", ListBinaryTag.builder()
                .add(StringBinaryTag.stringBinaryTag("Shop"))
                .add(CompoundBinaryTag.builder().putString("text", "Op")
                        .put("extra", ListBinaryTag.builder().add(StringBinaryTag.stringBinaryTag("en")).build()).build())
                .add(StringBinaryTag.stringBinaryTag(""))
                .add(StringBinaryTag.stringBinaryTag("")).build()).build();
        set(1, 70, 1, Block.OAK_SIGN.withNbt(CompoundBinaryTag.builder().put("front_text", front).build()));
        set(2, 70, 1, Block.OAK_SIGN);

        Ops.ReadResult with = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), false, true));
        Ops.ReadResult without = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), false));

        assertThat(with.signs()).containsExactly(new Ops.SignText(1, 70, 1, List.of("Shop", "Open", "", ""), null));
        assertThat(without.signs()).isEmpty();
    }

    @Test
    void readReturnsAPaletteGridInYzxOrder(Env env) {
        setUp(env);
        set(1, 70, 0, Block.STONE);
        set(0, 71, 1, Block.GLASS);

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
    void readOutsideTheBuildHeightGivesVoidAir(Env env) {
        setUp(env);
        int top = world.getCachedDimensionType().maxY();
        BlockGrid grid = ops.read(new Ops.ReadParams(box(0, top - 1, 0, 0, top, 0), false)).grid();
        assertThat(grid.stateAt(0, top - 1, 0)).isEqualTo("minecraft:air");
        assertThat(grid.stateAt(0, top, 0)).isEqualTo(ReadJob.OUTSIDE_WORLD);
    }

    @Test
    void readThenWriteCopiesAStructure(Env env) {
        setUp(env);
        fill(box(0, 70, 0, 2, 72, 2), "minecraft:bricks");
        fill(box(1, 72, 1, 1, 72, 1), "minecraft:glowstone");
        BlockGrid copy = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 72, 2), false)).grid();

        Region target = box(30, 70, 0, 32, 72, 2);
        ops.write(new Ops.WriteParams(new BlockGrid(target, copy.palette(), copy.blocks()), false));

        assertThat(at(30, 70, 0)).isEqualTo(Block.BRICKS);
        assertThat(at(31, 72, 1)).isEqualTo(Block.GLOWSTONE);
    }

    @Test
    void heightmapGivesTheTopBlockPerColumn(Env env) {
        setUp(env);
        set(0, 80, 0, Block.STONE);
        set(1, 75, 0, Block.STONE);
        set(0, 10, 1, Block.STONE);

        Ops.HeightmapResult result = ops.heightmap(new Ops.HeightmapParams("world", new BlockPos(1, 0, 0), new BlockPos(0, 0, 1)));

        assertThat(result.minX()).isZero();
        assertThat(result.sizeX()).isEqualTo(2);
        assertThat(result.sizeZ()).isEqualTo(2);
        assertThat(result.heights()).containsExactly(80, 75, 10, world.getCachedDimensionType().minY() - 1);
    }

    @Test
    void entitiesReportHealthAndTextDisplayText(Env env) {
        setUp(env);
        world.loadChunk(0, 0).join();
        var zombie = new net.minestom.server.entity.LivingEntity(net.minestom.server.entity.EntityType.ZOMBIE);
        zombie.setInstance(world, new net.minestom.server.coordinate.Pos(2.5, 70, 2.5)).join();
        zombie.setHealth(5);
        var label = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.TEXT_DISPLAY);
        ((net.minestom.server.entity.metadata.display.TextDisplayMeta) label.getEntityMeta())
                .setText(net.kyori.adventure.text.Component.text("Wave 3"));
        label.setInstance(world, new net.minestom.server.coordinate.Pos(1.5, 71, 1.5)).join();

        Ops.EntitiesResult all = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), null, 0));
        Ops.EntitiesResult zombies = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), "minecraft:zombie", 5));

        assertThat(all.total()).isEqualTo(2);
        assertThat(all.entities().getFirst().name()).isEqualTo("Wave 3");
        assertThat(all.entities().getFirst().health()).isNull();
        assertThat(zombies.entities()).singleElement().satisfies(e -> {
            assertThat(e.health()).isEqualTo(5.0);
            assertThat(e.maxHealth()).isEqualTo(20.0);
        });
    }

    @Test
    void readsEntitiesInTheRegion(Env env) {
        setUp(env);
        world.loadChunk(0, 0).join();
        var zombie = new net.minestom.server.entity.Entity(net.minestom.server.entity.EntityType.ZOMBIE);
        zombie.set(net.minestom.server.component.DataComponents.CUSTOM_NAME, net.kyori.adventure.text.Component.text("Bob"));
        zombie.setInstance(world, new net.minestom.server.coordinate.Pos(2.5, 70, 2.5)).join();

        List<Ops.EntitySummary> inside = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), true)).entities();
        List<Ops.EntitySummary> outside = ops.read(new Ops.ReadParams(box(5, 70, 5, 6, 71, 6), true)).entities();

        assertThat(inside).singleElement().satisfies(e -> {
            assertThat(e.type()).isEqualTo("minecraft:zombie");
            assertThat(e.name()).isEqualTo("Bob");
        });
        assertThat(outside).isEmpty();
    }

    @Test
    void reportsClearErrors(Env env) {
        setUp(env);
        assertThatThrownBy(() -> fill(Region.of("nether", 0, 0, 0, 1, 1, 1), "minecraft:stone"))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
        assertThatThrownBy(() -> fill(box(0, 0, 0, 200, 100, 200), "minecraft:stone"))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThatThrownBy(() -> fill(box(0, 70, 0, 0, 70, 0), "minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        int top = world.getCachedDimensionType().maxY();
        assertThatThrownBy(() -> fill(box(0, top, 0, 0, top, 0), "minecraft:stone"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("build height");
    }
}
