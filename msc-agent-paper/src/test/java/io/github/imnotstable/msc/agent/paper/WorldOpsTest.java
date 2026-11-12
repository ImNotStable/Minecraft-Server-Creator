package io.github.imnotstable.msc.agent.paper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

class WorldOpsTest {
    private ServerMock server;
    private World world;
    private WorldOps ops;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        WorldMock w = new WorldMock(Material.AIR, 0);
        w.setName("world");
        server.addWorld(w);
        world = w;
        ops = new WorldOps(server, MainThread.inline(), new EditHistory(1_000_000));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("world", x1, y1, z1, x2, y2, z2);
    }

    private Material at(int x, int y, int z) {
        return world.getBlockAt(x, y, z).getType();
    }

    @Test
    void fillChangesEveryBlockAndCountsOnlyRealChanges() {
        world.getBlockAt(1, 70, 1).setType(Material.STONE);

        Ops.EditResult result = ops.fill(new Ops.FillParams(box(0, 70, 0, 2, 71, 2), "minecraft:stone", null));

        assertThat(result.changed()).isEqualTo(17);
        assertThat(at(0, 70, 0)).isEqualTo(Material.STONE);
        assertThat(at(2, 71, 2)).isEqualTo(Material.STONE);
        assertThat(at(3, 70, 0)).isEqualTo(Material.AIR);
    }

    @Test
    void undoRestoresTheEditAndCanItselfBeUndone() {
        world.getBlockAt(0, 70, 0).setType(Material.DIRT);
        Ops.EditResult fill = ops.fill(new Ops.FillParams(box(0, 70, 0, 1, 70, 0), "minecraft:stone", null));

        Ops.EditResult undo = ops.undo(new Ops.UndoParams(fill.editId()));

        assertThat(undo.changed()).isEqualTo(2);
        assertThat(at(0, 70, 0)).isEqualTo(Material.DIRT);
        assertThat(at(1, 70, 0)).isEqualTo(Material.AIR);

        ops.undo(new Ops.UndoParams(undo.editId()));
        assertThat(at(0, 70, 0)).isEqualTo(Material.STONE);
        assertThatThrownBy(() -> ops.undo(new Ops.UndoParams(fill.editId())))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void fillWithOnlyReplaceLeavesOtherBlocks() {
        world.getBlockAt(0, 70, 0).setType(Material.DIRT);
        world.getBlockAt(1, 70, 0).setType(Material.STONE);

        Ops.EditResult result = ops.fill(new Ops.FillParams(box(0, 70, 0, 2, 70, 0), "minecraft:gold_block",
                List.of("minecraft:dirt")));

        assertThat(result.changed()).isEqualTo(1);
        assertThat(at(0, 70, 0)).isEqualTo(Material.GOLD_BLOCK);
        assertThat(at(1, 70, 0)).isEqualTo(Material.STONE);
        assertThat(at(2, 70, 0)).isEqualTo(Material.AIR);
    }

    @Test
    void writeSkipsCellsMarkedMinusOne() {
        Region r = box(0, 70, 0, 1, 70, 1);
        // Y-Z-X order: (0,0) (1,0) (0,1) (1,1)
        BlockGrid grid = new BlockGrid(r, List.of("minecraft:stone", "minecraft:oak_planks"), new int[]{0, -1, 1, 0});

        Ops.EditResult result = ops.write(new Ops.WriteParams(grid, false));

        assertThat(result.changed()).isEqualTo(3);
        assertThat(at(0, 70, 0)).isEqualTo(Material.STONE);
        assertThat(at(1, 70, 0)).isEqualTo(Material.AIR);
        assertThat(at(0, 70, 1)).isEqualTo(Material.OAK_PLANKS);
        assertThat(at(1, 70, 1)).isEqualTo(Material.STONE);
    }

    @Test
    void setBlocksKeepsBlockStateProperties() {
        ops.setBlocks(new Ops.SetBlocksParams("world", List.of(
                new Ops.BlockChange(5, 70, 5, "minecraft:oak_stairs[facing=east]"),
                new Ops.BlockChange(6, 70, 5, "minecraft:glass")), false));

        assertThat(world.getBlockAt(5, 70, 5).getBlockData().getAsString()).contains("facing=east");
        assertThat(at(6, 70, 5)).isEqualTo(Material.GLASS);
    }

    @Test
    void readReturnsAPaletteGridInYzxOrder() {
        world.getBlockAt(1, 70, 0).setType(Material.STONE);
        world.getBlockAt(0, 71, 1).setType(Material.GLASS);

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
    void entitiesReportNameTagsAndHealthAndFilterByType() {
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 2.5, 70, 2.5), EntityType.ZOMBIE);
        zombie.customName(Component.text("Brute"));
        zombie.addScoreboardTag("wave");
        zombie.addScoreboardTag("boss");
        zombie.setHealth(7);
        world.spawnEntity(new Location(world, 1.5, 70, 1.5), EntityType.ZOMBIE);
        world.spawnEntity(new Location(world, 3.5, 70, 3.5), EntityType.PIG);
        world.spawnEntity(new Location(world, 50.5, 70, 50.5), EntityType.ZOMBIE);

        Ops.EntitiesResult all = ops.entities(new Ops.EntitiesParams(box(0, 60, 0, 10, 80, 10), null, 0));
        Ops.EntitiesResult zombies = ops.entities(new Ops.EntitiesParams(box(0, 60, 0, 10, 80, 10), "minecraft:zombie", 1));

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.entities()).extracting(Ops.EntityInfo::type)
                .containsExactly("minecraft:pig", "minecraft:zombie", "minecraft:zombie");
        Ops.EntityInfo brute = all.entities().get(2);
        assertThat(brute.name()).isEqualTo("Brute");
        assertThat(brute.tags()).containsExactly("boss", "wave");
        assertThat(brute.health()).isEqualTo(7.0);
        assertThat(brute.maxHealth()).isEqualTo(20.0);
        assertThat(brute.uuid()).isEqualTo(zombie.getUniqueId().toString());
        assertThat(zombies.total()).isEqualTo(2);
        assertThat(zombies.entities()).hasSize(1);
        assertThat(zombies.entities().getFirst().x()).isEqualTo(1.5);
    }

    @Test
    void readOutsideTheBuildHeightGivesVoidAir() {
        int top = world.getMaxHeight();
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

        assertThat(at(10, 70, 0)).isEqualTo(Material.BRICKS);
        assertThat(at(11, 72, 1)).isEqualTo(Material.GLOWSTONE);
    }

    @Test
    void heightmapGivesTheTopBlockPerColumn() {
        world.getBlockAt(0, 80, 0).setType(Material.STONE);
        world.getBlockAt(1, 75, 0).setType(Material.STONE);

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
        int top = world.getMaxHeight();
        assertThatThrownBy(() -> ops.fill(new Ops.FillParams(box(0, top, 0, 0, top, 0), "minecraft:stone", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("build height");
    }
}
