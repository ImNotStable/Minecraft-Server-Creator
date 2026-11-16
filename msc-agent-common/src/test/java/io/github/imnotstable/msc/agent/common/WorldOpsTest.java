package io.github.imnotstable.msc.agent.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorldOpsTest {
    private static final String STONE = "minecraft:stone";

    private MapWorld world;
    private WorldOps<String> ops;

    @BeforeEach
    void setUp() {
        world = new MapWorld();
        ops = new WorldOps<>(world, MainThread.inline(), new EditHistory<>(1_000_000));
    }

    private static Region box(int x1, int y1, int z1, int x2, int y2, int z2) {
        return Region.of("world", x1, y1, z1, x2, y2, z2);
    }

    private Ops.EditResult fill(Region region, String state) {
        return write(region, state, false);
    }

    private Ops.EditResult write(Region region, String state, boolean physics) {
        int[] cells = new int[Math.toIntExact(region.volume())];
        return ops.write(new Ops.WriteParams(new BlockGrid(region, List.of(state), cells), physics));
    }

    @Test
    void writeChangesEveryBlockAndCountsOnlyRealChanges() {
        world.set(1, 70, 1, STONE, false);

        Ops.EditResult result = fill(box(0, 70, 0, 2, 71, 2), STONE);

        assertThat(result.changed()).isEqualTo(17);
        assertThat(world.get(0, 70, 0)).isEqualTo(STONE);
        assertThat(world.get(2, 71, 2)).isEqualTo(STONE);
        assertThat(world.get(3, 70, 0)).isEqualTo(MapWorld.AIR);
        assertThat(world.loads).containsExactly("area 0,0..2,2");
    }

    @Test
    void undoRestoresTheEditAndCanItselfBeUndone() {
        world.set(0, 70, 0, "minecraft:dirt", false);
        Ops.EditResult fill = fill(box(0, 70, 0, 1, 70, 0), STONE);

        Ops.EditResult undo = ops.undo(new Ops.UndoParams(fill.editId()));

        assertThat(undo.changed()).isEqualTo(2);
        assertThat(world.get(0, 70, 0)).isEqualTo("minecraft:dirt");
        assertThat(world.get(1, 70, 0)).isEqualTo(MapWorld.AIR);
        assertThat(world.loads).endsWith("positions 2");

        ops.undo(new Ops.UndoParams(undo.editId()));
        assertThat(world.get(0, 70, 0)).isEqualTo(STONE);
        assertThatThrownBy(() -> ops.undo(new Ops.UndoParams(fill.editId())))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND));
    }

    @Test
    void failedUndoKeepsTheRecordSoARetryUndoesTheRest() {
        boolean[] timeOut = {false};
        MainThread inline = MainThread.inline();
        EditHistory<String> history = new EditHistory<>(1_000_000);
        ops = new WorldOps<>(world, new MainThread() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                return inline.call(task);
            }

            @Override
            public <T> T run(SlicedJob<T> job) {
                if (timeOut[0]) {
                    timeOut[0] = false;
                    throw new OpException(ErrorCodes.TIMEOUT, "main thread did not finish");
                }
                return inline.run(job);
            }
        }, history);
        Ops.EditResult fill = fill(box(0, 70, 0, 1, 70, 0), STONE);

        timeOut[0] = true;
        assertThatThrownBy(() -> ops.undo(new Ops.UndoParams(fill.editId())))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TIMEOUT));
        assertThat(history.rememberedBlocks()).isEqualTo(2);

        Ops.EditResult retry = ops.undo(new Ops.UndoParams(fill.editId()));
        assertThat(retry.changed()).isEqualTo(2);
        assertThat(world.get(0, 70, 0)).isEqualTo(MapWorld.AIR);
    }

    @Test
    void aWriteThatTimesOutMidWayKeepsItsChangedBlocksUndoable() {
        // The real timeout path: a JobQueue the "main thread" steps once, then MainThread.await gives up and cancels.
        JobQueue queue = new JobQueue();
        MainThread inline = MainThread.inline();
        EditHistory<String> history = new EditHistory<>(1_000_000);
        ops = new WorldOps<>(world, new MainThread() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                return inline.call(task);
            }

            @Override
            public <T> T run(SlicedJob<T> job) {
                java.util.concurrent.CompletableFuture<T> future = queue.add(job);
                queue.step(java.time.Duration.ZERO);
                return MainThread.await(future, java.time.Duration.ofMillis(1));
            }
        }, history);

        Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(() -> fill(box(0, 70, 0, 19, 70, 19), STONE));

        assertThat(thrown).isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TIMEOUT));
        String id = PartialEdit.editId(((OpException) thrown).detail()).orElseThrow();
        long placed = world.blocks.values().stream().filter(STONE::equals).count();
        assertThat(placed).isEqualTo(256);
        queue.step(java.time.Duration.ofSeconds(1));
        assertThat(world.blocks.values().stream().filter(STONE::equals).count()).isEqualTo(placed);

        WorldOps<String> patient = new WorldOps<>(world, inline, history);
        assertThat(patient.undo(new Ops.UndoParams(id)).changed()).isEqualTo(256);
        assertThat(world.blocks.values()).doesNotContain(STONE);
    }

    @Test
    void aWriteThatFailsBeforeChangingAnythingHasNoPartialEdit() {
        ops = new WorldOps<>(world, new MainThread() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                return task.get();
            }

            @Override
            public <T> T run(SlicedJob<T> job) {
                throw new OpException(ErrorCodes.SHUTDOWN, "server is shutting down");
            }
        }, new EditHistory<>(1_000_000));

        assertThatThrownBy(() -> fill(box(0, 70, 0, 1, 70, 0), STONE))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.detail()).isEqualTo("server is shutting down"));
    }

    @Test
    void oneAgentWaitIsTheTimeoutEveryAgentUses() {
        assertThat(AgentDefaults.OP_TIMEOUT).isEqualTo(Limits.AGENT_WAIT);
    }

    @Test
    void undoPutsBackBlockEntityDataTheStateLeavesOut() {
        world.set(0, 70, 0, "minecraft:oak_sign", false);
        Ops.SignText text = new Ops.SignText(0, 70, 0, List.of("Shop"), null);
        world.signs.put(PackedPos.pack(0, 70, 0), text);
        Ops.EditResult fill = fill(box(0, 70, 0, 1, 70, 0), STONE);
        assertThat(world.sign(0, 70, 0)).isNull();

        ops.undo(new Ops.UndoParams(fill.editId()));

        assertThat(world.get(0, 70, 0)).isEqualTo("minecraft:oak_sign");
        assertThat(world.sign(0, 70, 0)).isEqualTo(text);
    }

    @Test
    void writeRefusesXzThatUndoCannotRecord() {
        int edge = PackedPos.MAX_XZ;
        fill(box(edge, 70, -edge - 1, edge, 70, -edge - 1), STONE);
        assertThat(world.get(edge, 70, -edge - 1)).isEqualTo(STONE);
        int writes = world.writes;
        assertThatThrownBy(() -> fill(box(40_000_000, 70, 0, 40_000_000, 70, 0), STONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("40000000");
        assertThatThrownBy(() -> fill(box(0, 70, -edge - 2, 0, 70, -edge - 2), STONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(world.writes).isEqualTo(writes);
    }

    @Test
    void writeSkipsCellsMarkedMinusOne() {
        Region r = box(0, 70, 0, 1, 70, 1);
        // Y-Z-X order: (0,0) (1,0) (0,1) (1,1)
        BlockGrid grid = new BlockGrid(r, List.of(STONE, "minecraft:oak_planks"), new int[]{0, -1, 1, 0});

        Ops.EditResult result = ops.write(new Ops.WriteParams(grid, false));

        assertThat(result.changed()).isEqualTo(3);
        assertThat(world.get(0, 70, 0)).isEqualTo(STONE);
        assertThat(world.get(1, 70, 0)).isEqualTo(MapWorld.AIR);
        assertThat(world.get(0, 70, 1)).isEqualTo("minecraft:oak_planks");
        assertThat(world.get(1, 70, 1)).isEqualTo(STONE);
    }

    @Test
    void readReturnsAPaletteGridInYzxOrder() {
        world.set(1, 70, 0, STONE, false);
        world.set(0, 71, 1, "minecraft:glass", false);

        Ops.ReadResult result = ops.read(new Ops.ReadParams(box(0, 70, 0, 1, 71, 1), false));

        BlockGrid grid = result.grid();
        assertThat(grid.blocks()).hasSize(8);
        assertThat(grid.stateAt(1, 70, 0)).isEqualTo(STONE);
        assertThat(grid.stateAt(0, 71, 1)).isEqualTo("minecraft:glass");
        assertThat(grid.stateAt(0, 70, 0)).isEqualTo(MapWorld.AIR);
        assertThat(grid.palette()).hasSize(3);
        assertThat(result.entities()).isEmpty();
        assertThat(result.signs()).isEmpty();
        assertThat(world.loads).containsExactly("area 0,0..1,1", "released");
    }

    @Test
    void readOutsideTheBuildHeightGivesVoidAir() {
        int top = world.maxY();
        BlockGrid grid = ops.read(new Ops.ReadParams(box(0, top - 1, 0, 0, top, 0), false)).grid();
        assertThat(grid.stateAt(0, top, 0)).isEqualTo(ReadJob.OUTSIDE_WORLD);
    }

    @Test
    void readReportsSignTextOnlyWhereTheGridHasASign() {
        world.set(1, 70, 1, "minecraft:oak_sign", false);
        world.signs.put(PackedPos.pack(1, 70, 1), new Ops.SignText(1, 70, 1, List.of("Shop"), null));
        // Text left behind where there is no sign block must not be reported.
        world.signs.put(PackedPos.pack(2, 70, 1), new Ops.SignText(2, 70, 1, List.of("stale"), null));

        Ops.ReadResult with = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), false, true));
        Ops.ReadResult without = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), false));

        assertThat(with.signs()).containsExactly(new Ops.SignText(1, 70, 1, List.of("Shop", "", "", ""), null));
        assertThat(without.signs()).isEmpty();
    }

    @Test
    void readThenWriteCopiesAStructure() {
        fill(box(0, 70, 0, 2, 72, 2), "minecraft:bricks");
        fill(box(1, 72, 1, 1, 72, 1), "minecraft:glowstone");
        BlockGrid copy = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 72, 2), false)).grid();

        ops.write(new Ops.WriteParams(new BlockGrid(box(10, 70, 0, 12, 72, 2), copy.palette(), copy.blocks()), false));

        assertThat(world.get(10, 70, 0)).isEqualTo("minecraft:bricks");
        assertThat(world.get(11, 72, 1)).isEqualTo("minecraft:glowstone");
    }

    // ---- block entity data (ADR 0042) ----

    private static final String CHEST = "minecraft:chest[facing=north]";

    @Test
    void readWithBlockDataGivesEachChestItsDataAndLeavesOtherReadsAlone() {
        world.set(0, 70, 0, CHEST, false);
        world.set(1, 70, 0, CHEST, false);
        world.set(2, 70, 0, CHEST, false);
        world.data.put(PackedPos.pack(0, 70, 0), "{Items:[a]}");
        world.data.put(PackedPos.pack(1, 70, 0), "{Items:[a]}");

        BlockGrid with = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 70, 0), false, false, true)).grid();
        BlockGrid without = ops.read(new Ops.ReadParams(box(0, 70, 0, 2, 70, 0), false)).grid();

        assertThat(with.stateAt(0, 70, 0)).isEqualTo(CHEST + "{Items:[a]}");
        assertThat(with.blocks()[1]).as("equal data shares an entry").isEqualTo(with.blocks()[0]);
        assertThat(with.stateAt(2, 70, 0)).isEqualTo(CHEST);
        assertThat(without.palette()).containsExactly(CHEST);
    }

    @Test
    void writeMergesDataEvenWhereTheStateIsThereAndUndoPutsTheOldDataBack() {
        world.set(0, 70, 0, CHEST, false);
        world.data.put(PackedPos.pack(0, 70, 0), "{Items:[old]}");
        Region r = box(0, 70, 0, 1, 70, 0);

        Ops.EditResult edit = ops.write(new Ops.WriteParams(new BlockGrid(r, List.of(CHEST + "{Items:[new]}"),
                new int[] {0, 0}), false));

        assertThat(edit.changed()).isEqualTo(2);
        assertThat(world.blockData(0, 70, 0)).isEqualTo("{Items:[new]}");
        assertThat(world.blockData(1, 70, 0)).isEqualTo("{Items:[new]}");
        ops.undo(new Ops.UndoParams(edit.editId()));
        assertThat(world.blockData(0, 70, 0)).isEqualTo("{Items:[old]}");
        assertThat(world.get(1, 70, 0)).isEqualTo(MapWorld.AIR);
        assertThat(world.blockData(1, 70, 0)).isNull();
    }

    @Test
    void dataOnABlockThatKeepsNoneIsRefusedBeforeAnythingChanges() {
        Region r = box(0, 70, 0, 1, 70, 0);
        BlockGrid grid = new BlockGrid(r, List.of(STONE, STONE + "{a:1}"), new int[] {0, 1});

        assertThatThrownBy(() -> ops.write(new Ops.WriteParams(grid, false)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no block entity data");
        assertThat(world.writes).isZero();
    }

    @Test
    void aReadWhoseDataIsTooLargeFailsInsteadOfCuttingIt() {
        world.set(0, 70, 0, CHEST, false);
        world.data.put(PackedPos.pack(0, 70, 0), "{a:\"" + "x".repeat(Limits.MAX_BLOCK_DATA_CHARS) + "\"}");

        assertThatThrownBy(() -> ops.read(new Ops.ReadParams(box(0, 70, 0, 0, 70, 0), false, false, true)))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
    }

    @Test
    void blockDataSplitsAtTheFirstBraceOutsideTheProperties() {
        assertThat(BlockData.split("minecraft:chest[facing=north]{Items:[{id:\"a[b\"}]}"))
                .isEqualTo(new BlockData("minecraft:chest[facing=north]", "{Items:[{id:\"a[b\"}]}"));
        assertThat(BlockData.split("minecraft:stone")).isEqualTo(new BlockData("minecraft:stone", null));
        assertThat(BlockData.join("minecraft:stone", null)).isEqualTo("minecraft:stone");
    }

    @Test
    void heightmapGivesTheTopBlockPerColumn() {
        world.set(0, 80, 0, STONE, false);
        world.set(1, 75, 0, STONE, false);

        Ops.HeightmapResult result = ops.heightmap(new Ops.HeightmapParams("world", new BlockPos(1, 0, 0), new BlockPos(0, 0, 1)));

        assertThat(result.minX()).isZero();
        assertThat(result.sizeX()).isEqualTo(2);
        assertThat(result.sizeZ()).isEqualTo(2);
        assertThat(result.heights()).containsExactly(80, 75, -65, -65);
        assertThat(world.loads).containsExactly("area 0,0..1,1", "released");
    }

    @Test
    void reportsClearErrors() {
        assertThatThrownBy(() -> fill(Region.of("nether", 0, 0, 0, 1, 1, 1), STONE))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.NOT_FOUND))
                .hasMessageContaining("worlds: [world]");
        assertThatThrownBy(() -> fill(box(0, 0, 0, 200, 100, 200), STONE))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThatThrownBy(() -> ops.heightmap(new Ops.HeightmapParams("world", new BlockPos(0, 0, 0), new BlockPos(2000, 0, 2000))))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThatThrownBy(() -> ops.read(new Ops.ReadParams(Region.of("world", -2_000_000_000, 64, 0, 2_000_000_000, 64, 0), false)))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThatThrownBy(() -> ops.heightmap(new Ops.HeightmapParams("world",
                new BlockPos(Integer.MIN_VALUE, 0, Integer.MIN_VALUE), new BlockPos(Integer.MAX_VALUE, 0, Integer.MAX_VALUE))))
                .isInstanceOfSatisfying(OpException.class, e -> assertThat(e.code()).isEqualTo(ErrorCodes.TOO_LARGE));
        assertThat(world.loads).isEmpty();
        assertThatThrownBy(() -> fill(box(0, 70, 0, 0, 70, 0), "minecraft:not_a_block"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not_a_block");
        int top = world.maxY();
        assertThatThrownBy(() -> fill(box(0, top, 0, 0, top, 0), STONE))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("build height");
        assertThat(world.writes).isZero();
    }

    @Test
    void physicsFollowsTheRequestAndUndoNeverUsesIt() {
        Ops.EditResult edit = write(box(0, 70, 0, 0, 70, 0), "minecraft:sand", true);
        assertThat(world.lastPhysics).isTrue();

        ops.undo(new Ops.UndoParams(edit.editId()));
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

        Ops.EntitiesResult all = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), " ", 0));
        Ops.EntitiesResult zombies = ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 3, 71, 3), "minecraft:zombie", 1));

        assertThat(all.total()).isEqualTo(3);
        assertThat(all.entities()).extracting(Ops.EntityInfo::uuid).containsExactly("a", "c", "b");
        assertThat(zombies.total()).isEqualTo(2);
        assertThat(zombies.entities()).extracting(Ops.EntityInfo::uuid).containsExactly("c");
    }

    @Test
    void routerServesEveryOperation() {
        var router = AgentRoutes.router(new ServerOps(new FakeServer(), MainThread.inline()), ops);
        assertThat(router.operations()).containsExactlyInAnyOrderElementsOf(Ops.names());
    }

    /** Counts the agent's waits: each main-thread hand-off and each chunk load (a wait on Minestom). */
    private int[] countWaits() {
        int[] waits = {0};
        MainThread inline = MainThread.inline();
        ops = new WorldOps<>(world, new MainThread() {
            @Override
            public <T> T call(java.util.function.Supplier<T> task) {
                waits[0]++;
                return inline.call(task);
            }

            @Override
            public <T> T run(SlicedJob<T> job) {
                waits[0]++;
                return inline.run(job);
            }
        }, new EditHistory<>(1_000_000));
        return waits;
    }

    private long chunkWaits() {
        return world.loads.stream().filter(l -> !l.equals("released")).count();
    }

    @Test
    void aReadWithEntitiesAndSignsStaysWithinTheAgentWaitsTheCoreAllowsFor() {
        world.set(1, 70, 1, "minecraft:oak_sign", false);
        world.signs.put(PackedPos.pack(1, 70, 1), new Ops.SignText(1, 70, 1, List.of("Shop"), null));
        world.entities.add(new Ops.EntitySummary("minecraft:pig", 1.5, 70, 1.5, null));
        int[] waits = countWaits();

        Ops.ReadResult result = ops.read(new Ops.ReadParams(box(0, 70, 0, 3, 71, 3), true, true));

        assertThat(result.signs()).hasSize(1);
        assertThat(result.entities()).hasSize(1);
        assertThat(waits[0] + chunkWaits()).isLessThanOrEqualTo(Limits.AGENT_WAITS_PER_OP);
    }

    @Test
    void everyOtherWorldOpStaysWithinTheAgentWaitsTheCoreAllowsFor() {
        int[] waits = countWaits();
        Ops.EditResult fill = fill(box(0, 70, 0, 1, 70, 1), STONE);
        assertThat(waits[0] + chunkWaits()).isLessThanOrEqualTo(Limits.AGENT_WAITS_PER_OP);

        for (Runnable op : List.<Runnable>of(
                () -> ops.undo(new Ops.UndoParams(fill.editId())),
                () -> ops.heightmap(new Ops.HeightmapParams("world", new BlockPos(0, 0, 0), new BlockPos(1, 0, 1))),
                () -> ops.entities(new Ops.EntitiesParams(box(0, 70, 0, 1, 70, 1), null, 10)))) {
            waits[0] = 0;
            world.loads.clear();
            op.run();
            assertThat(waits[0] + chunkWaits()).isLessThanOrEqualTo(Limits.AGENT_WAITS_PER_OP);
        }
    }

    @Test
    void aReadLeavesEntitiesAndSignsToTheNextStepEvenWhenThatStepIsPastItsDeadline() {
        world.entities.add(new Ops.EntitySummary("minecraft:pig", 1.5, 70, 1.5, null));
        WorldReadJob<String> job = new WorldReadJob<>(world, new Ops.ReadParams(box(0, 70, 0, 1, 70, 1), true, true));

        assertThat(job.step(System.nanoTime() - 1)).isFalse();
        assertThat(job.step(System.nanoTime() - 1)).isTrue();
        assertThat(job.result().entities()).hasSize(1);
        assertThat(job.result().grid().blocks()).hasSize(4);
    }

    @Test
    void aReadFinishesWithinTwoStepsPastTheirDeadlineAndAtOnceWithoutExtras() {
        world.entities.add(new Ops.EntitySummary("minecraft:pig", 1.5, 70, 1.5, null));
        for (boolean entities : new boolean[] {false, true}) {
            for (boolean signs : new boolean[] {false, true}) {
                WorldReadJob<String> job = new WorldReadJob<>(world,
                        new Ops.ReadParams(box(0, 70, 0, 1, 70, 1), entities, signs));
                int steps = 1;
                while (steps < 10 && !job.step(System.nanoTime() - 1)) {
                    steps++;
                }
                assertThat(steps).as("entities=%s signs=%s", entities, signs).isEqualTo(entities || signs ? 2 : 1);
                assertThat(job.result().entities()).hasSize(entities ? 1 : 0);
            }
        }
    }
}
