package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Seeded property tests: decode(encode(x)) equals x for every world encoding. */
class EncodingRoundTripTest {
    private static final int SEEDS = 300;
    private static final UnaryOperator<String> FULL_STATE = s -> BlockNames.isAir(s) ? "minecraft:air" : BlockNames.full(s);
    private static final UnaryOperator<String> FULL_ID = s -> FULL_STATE.apply(BlockNames.id(s));

    @TempDir
    Path dir;

    @Test
    void textGridWithStatesRoundTrips() {
        for (long seed = 0; seed < SEEDS; seed++) {
            BlockGrid g = RandomGrids.grid(new Random(seed), TextGrid.SYMBOLS.length(), true);
            BlockGrid back = TextGrid.parse(TextGrid.render(g, true, Integer.MAX_VALUE), "world", g.region().min());
            assertThat(back.region()).as("seed %d", seed).isEqualTo(g.region());
            assertThat(RandomGrids.cells(back, UnaryOperator.identity())).as("seed %d", seed)
                    .isEqualTo(RandomGrids.cells(g, FULL_STATE));
        }
    }

    @Test
    void textGridWithIdsKeepsTheIds() {
        for (long seed = 0; seed < SEEDS; seed++) {
            BlockGrid g = RandomGrids.grid(new Random(seed), TextGrid.SYMBOLS.length(), true);
            BlockGrid back = TextGrid.parse(TextGrid.render(g, false, Integer.MAX_VALUE), "world", g.region().min());
            assertThat(back.region()).as("seed %d", seed).isEqualTo(g.region());
            assertThat(RandomGrids.cells(back, UnaryOperator.identity())).as("seed %d", seed)
                    .isEqualTo(RandomGrids.cells(g, FULL_ID));
        }
    }

    @Test
    void runLengthFormsComeUpAndRoundTrip() {
        int repeatedRows = 0;
        int layerRuns = 0;
        int uniform = 0;
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            BlockGrid g = RandomGrids.grid(rnd, 1 + rnd.nextInt(3), false);
            String text = TextGrid.render(g, true, Integer.MAX_VALUE);
            repeatedRows += text.contains(" (x") ? 1 : 0;
            layerRuns += text.contains("..") ? 1 : 0;
            uniform += text.contains(" all ") ? 1 : 0;
            BlockGrid back = TextGrid.parse(text, "world", g.region().min());
            assertThat(back.region()).as("seed %d", seed).isEqualTo(g.region());
            assertThat(RandomGrids.cells(back, UnaryOperator.identity())).as("seed %d", seed)
                    .isEqualTo(RandomGrids.cells(g, FULL_STATE));
        }
        assertThat(List.of(repeatedRows, layerRuns, uniform)).allMatch(n -> n > 10);
    }

    @Test
    void aGridOfUniformLayersKeepsRowsInItsLowestLayer() {
        BlockGrid g = new BlockGrid(Region.of("world", 0, 5, 0, 2, 7, 1), List.of("minecraft:stone", "minecraft:glass"),
                new int[] {0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0});
        String text = TextGrid.render(g, false, Integer.MAX_VALUE);
        assertThat(text).endsWith("y=5\n### (x2)\ny=6 all a\ny=7 all #");
        assertThat(TextGrid.parse(text, "world", g.region().min()).blocks()).isEqualTo(g.blocks());
    }

    @Test
    void schematicV2RoundTripsEveryPaletteForm() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            // Up to 400 kinds: indices above 127 take two varint bytes; equal states share one file index.
            BlockGrid g = RandomGrids.grid(rnd, rnd.nextInt(4) == 0 ? 400 : 20, true);
            Path file = dir.resolve(seed + ".schem");
            Schematic.save(g, file);
            BlockGrid back = Schematic.load(file, "world", g.region().min());
            assertSameBlocks(seed, g, back);
        }
    }

    @Test
    void schematicV3LayoutLoadsLikeV2() throws Exception {
        for (long seed = 0; seed < 100; seed++) {
            Random rnd = new Random(seed);
            BlockGrid g = RandomGrids.grid(rnd, rnd.nextInt(4) == 0 ? 400 : 20, true);
            Path v2 = dir.resolve(seed + ".schem");
            Schematic.save(g, v2);
            Map<String, Object> root;
            try (InputStream in = Files.newInputStream(v2)) {
                root = Nbt.read(in);
            }
            Map<String, Object> blocks = new LinkedHashMap<>();
            blocks.put("Palette", root.remove("Palette"));
            blocks.put("Data", root.remove("BlockData"));
            root.remove("PaletteMax");
            root.remove("BlockEntities");
            root.put("Version", 3);
            root.put("Blocks", blocks);
            Path v3 = dir.resolve(seed + "-v3.schem");
            try (OutputStream out = Files.newOutputStream(v3)) {
                Nbt.write(out, "", Map.of("Schematic", root));
            }
            assertSameBlocks(seed, g, Schematic.load(v3, "world", g.region().min()));
        }
    }

    @Test
    void nbtRoundTripsEveryWrittenType() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Map<String, Object> root = compound(rnd, 0);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Nbt.write(bytes, "r" + seed, root);
            assertThat(Nbt.read(new ByteArrayInputStream(bytes.toByteArray()))).as("seed %d", seed)
                    .usingRecursiveComparison().isEqualTo(root);
        }
    }

    @Test
    void coordinatesRoundTrip() {
        Random rnd = new Random(7);
        for (int i = 0; i < SEEDS; i++) {
            BlockPos a = new BlockPos(xz(rnd), rnd.nextInt(2 * Coords.MAX_Y + 1) - Coords.MAX_Y, xz(rnd));
            BlockPos b = new BlockPos(xz(rnd), rnd.nextInt(2 * Coords.MAX_Y + 1) - Coords.MAX_Y, xz(rnd));
            assertThat(Coords.pos(Coords.show(a))).isEqualTo(a);
            Region r = Region.of("world", a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
            assertThat(Coords.region("world", Coords.show(r.min()) + " " + Coords.show(r.max()))).isEqualTo(r);
        }
    }

    private static int xz(Random rnd) {
        return rnd.nextInt(2 * Coords.MAX_XZ + 1) - Coords.MAX_XZ;
    }

    private static void assertSameBlocks(long seed, BlockGrid expected, BlockGrid actual) {
        assertThat(actual.region()).as("seed %d", seed).isEqualTo(expected.region());
        List<String> want = new ArrayList<>(RandomGrids.cells(expected, UnaryOperator.identity()));
        want.replaceAll(s -> s == null ? "minecraft:air" : s);
        assertThat(RandomGrids.cells(actual, UnaryOperator.identity())).as("seed %d", seed).isEqualTo(want);
    }

    /** A random compound of the value types {@link Nbt#write} supports. */
    private static Map<String, Object> compound(Random rnd, int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        int n = rnd.nextInt(depth == 0 ? 8 : 4);
        for (int i = 0; i < n; i++) {
            String key = rnd.nextInt(5) == 0 ? "ké😀\u0000" + i : "k" + i;
            map.put(key, switch (rnd.nextInt(depth < 4 ? 7 : 6)) {
                case 0 -> (short) rnd.nextInt();
                case 1 -> rnd.nextInt();
                case 2 -> {
                    byte[] b = new byte[rnd.nextInt(40)];
                    rnd.nextBytes(b);
                    yield b;
                }
                case 3 -> "s" + rnd.nextLong() + (rnd.nextBoolean() ? "\u0000ÿ" : "");
                case 4 -> List.of();
                case 5 -> rnd.ints(rnd.nextInt(20)).toArray();
                default -> compound(rnd, depth + 1);
            });
        }
        return map;
    }
}
