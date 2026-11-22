package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.world.Coords;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Seeded property tests of the world tools through the fake agent: every operation changes exactly the blocks inside
 * its inclusive bounds, undo restores exactly, copies and pasted slices equal their source, and summaries and diffs
 * agree with the blocks.
 */
class WorldOperationsPropertyTest {
    private static final int SEEDS = 25;
    /** Blocks the random worlds are made of; some carry states, so copies must keep them. */
    private static final String[] KINDS = {"minecraft:stone", "minecraft:dirt", "minecraft:air", "minecraft:glass",
            "minecraft:oak_stairs[facing=north,half=bottom]", "minecraft:oak_stairs[facing=east,half=top]",
            "minecraft:cave_air", "minecraft:oak_log[axis=x]"};

    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace).withAgent();
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void fillAndItsShapesTouchExactlyTheCellsInsideTheInclusiveBox() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region box = box(rnd, 6);
            Region margin = grow(box, 1);
            scatter(rnd, margin);
            Map<BlockPos, String> before = snapshot(margin);
            String shape = List.of("", " hollow", " outline", " walls").get(rnd.nextInt(4));
            // Corners in any order name the same box.
            String corners = rnd.nextBoolean() ? corners(box) : show(box.max()) + " " + show(box.min());
            h.ok("build_batch", "steps", "fill " + corners + " gold_block" + shape);
            for (BlockPos p : before.keySet()) {
                String expected = expectedFill(box, p, shape.strip(), before.get(p));
                assertThat(h.agent.get(p.x(), p.y(), p.z())).as("seed %d %s at %s", seed, shape, p).isEqualTo(expected);
            }
        }
    }

    private static String expectedFill(Region box, BlockPos p, String shape, String old) {
        if (!box.contains(p.x(), p.y(), p.z())) {
            return old;
        }
        boolean side = p.x() == box.min().x() || p.x() == box.max().x() || p.z() == box.min().z() || p.z() == box.max().z();
        boolean shell = side || p.y() == box.min().y() || p.y() == box.max().y();
        return switch (shape) {
            case "hollow" -> shell ? "minecraft:gold_block" : "minecraft:air";
            case "outline" -> shell ? "minecraft:gold_block" : old;
            case "walls" -> side ? "minecraft:gold_block" : old;
            default -> "minecraft:gold_block";
        };
    }

    @Test
    void replaceTouchesExactlyTheMatchingCellsInsideTheBox() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region box = box(rnd, 6);
            Region margin = grow(box, 1);
            scatter(rnd, margin);
            Map<BlockPos, String> before = snapshot(margin);
            // air matches every kind of air; oak_stairs without states matches every stair; [half=top] only some.
            String replace = List.of("dirt", "air", "oak_stairs", "oak_stairs[half=top]", "stone,glass").get(rnd.nextInt(5));
            h.ok("build_batch", "steps", "fill " + corners(box) + " gold_block replace=" + replace);
            for (BlockPos p : before.keySet()) {
                String old = before.get(p);
                boolean hit = box.contains(p.x(), p.y(), p.z()) && matches(old, replace);
                assertThat(h.agent.get(p.x(), p.y(), p.z())).as("seed %d replace=%s at %s", seed, replace, p)
                        .isEqualTo(hit ? "minecraft:gold_block" : old);
            }
        }
    }

    private static boolean matches(String state, String replace) {
        for (String r : replace.split(",(?![^\\[]*])")) {
            String id = r.contains("[") ? r.substring(0, r.indexOf('[')) : r;
            boolean air = id.equals("air") && state.matches("minecraft:(cave_|void_)?air");
            if (air || state.startsWith("minecraft:" + id) && (state.length() == ("minecraft:" + id).length()
                    || state.charAt(("minecraft:" + id).length()) == '[')
                    && (!r.contains("[") || state.contains(r.substring(r.indexOf('[') + 1, r.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void buildThenUndoRestoresTheWorldExactlyInOrder() {
        Region area = Region.of("world", 0, 58, 0, 23, 71, 23);
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            scatter(rnd, area);
            Map<BlockPos, String> original = snapshot(area);
            String first = h.ok("build_batch", "steps", randomSteps(rnd));
            Map<BlockPos, String> afterFirst = snapshot(area);
            String second = h.ok("build_batch", "steps", randomSteps(rnd));
            String secondId = editId(second);
            assertThat(h.ok("build_undo")).as("seed %d", seed).startsWith("undid " + secondId + ":");
            assertThat(snapshot(area)).as("seed %d after undoing %s", seed, secondId).isEqualTo(afterFirst);
            assertThat(h.ok("build_undo", "edit", editId(first))).as("seed %d", seed).startsWith("undid " + editId(first));
            assertThat(snapshot(area)).as("seed %d after undoing both", seed).isEqualTo(original);
        }
        assertThat(h.error("build_undo")).contains("nothing to undo");
    }

    /**
     * #695 (ADR 0049): random builds and undos, of the latest edit or any other, over a small area so they often
     * overlap. An undo is refused, naming the later applied edits that set any of its positions newest first, and
     * changes nothing; any other undo goes through. After every call the world is what replaying the edits still
     * applied, oldest first, onto the starting world gives.
     */
    @Test
    void undoInAnyOrderLeavesTheWorldAsTheEditsStillAppliedMakeIt() {
        Region area = Region.of("world", 0, 62, 0, 4, 65, 4);
        String[] blocks = {"gold_block", "glass", "dirt", "air"};
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            scatter(rnd, area);
            Map<BlockPos, String> start = snapshot(area);
            java.util.SequencedMap<String, Map<BlockPos, String>> applied = new LinkedHashMap<>();
            for (int call = 0; call < 16; call++) {
                if (applied.isEmpty() || rnd.nextInt(5) < 3) {
                    Map<BlockPos, String> sets = new LinkedHashMap<>();
                    StringBuilder steps = new StringBuilder();
                    for (int s = 1 + rnd.nextInt(2); s > 0; s--) {
                        BlockPos a = new BlockPos(rnd.nextInt(5), 62 + rnd.nextInt(4), rnd.nextInt(5));
                        BlockPos b = new BlockPos(rnd.nextInt(5), 62 + rnd.nextInt(4), rnd.nextInt(5));
                        String block = blocks[rnd.nextInt(blocks.length)];
                        String shape = List.of("", " outline", "set").get(rnd.nextInt(3));
                        if (shape.equals("set")) {
                            steps.append("set ").append(show(a)).append(' ').append(block).append('\n');
                            sets.put(a, "minecraft:" + block);
                            continue;
                        }
                        Region box = new Region("world", a, b);
                        steps.append("fill ").append(show(a)).append(' ').append(show(b)).append(' ').append(block)
                                .append(shape).append('\n');
                        for (BlockPos p : snapshot(box).keySet()) {
                            if (!expectedFill(box, p, shape.strip(), "kept").equals("kept")) {
                                sets.put(p, "minecraft:" + block);
                            }
                        }
                    }
                    applied.put(editId(h.ok("build_batch", "steps", steps.toString())), sets);
                } else {
                    List<String> ids = new ArrayList<>(applied.keySet());
                    boolean latest = rnd.nextBoolean();
                    String target = latest ? ids.getLast() : ids.get(rnd.nextInt(ids.size()));
                    List<String> over = new ArrayList<>();
                    for (String later : ids.subList(ids.indexOf(target) + 1, ids.size())) {
                        if (applied.get(later).keySet().stream().anyMatch(applied.get(target)::containsKey)) {
                            over.addFirst(later);
                        }
                    }
                    if (over.isEmpty()) {
                        String out = latest ? h.ok("build_undo") : h.ok("build_undo", "edit", target);
                        assertThat(out).as("seed %d", seed).startsWith("undid " + target + ":");
                        applied.remove(target);
                    } else {
                        assertThat(h.error("build_undo", "edit", target)).as("seed %d", seed)
                                .startsWith("error: " + target + " not undone: later edit"
                                        + (over.size() == 1 ? " " : "s ") + String.join(", ", over) + " set ")
                                .contains("(build_undo edit=" + over.getFirst() + "), then " + target + ".");
                    }
                }
                Map<BlockPos, String> replay = new HashMap<>(start);
                applied.values().forEach(replay::putAll);
                assertThat(snapshot(area)).as("seed %d after call %d, applied %s", seed, call, applied.keySet())
                        .isEqualTo(replay);
            }
            while (!applied.isEmpty()) {
                assertThat(h.ok("build_undo")).startsWith("undid " + applied.lastEntry().getKey() + ":");
                applied.pollLastEntry();
            }
            assertThat(snapshot(area)).as("seed %d, all undone", seed).isEqualTo(start);
        }
    }

    /**
     * #697 (ADR 0049): a build and an undo of overlapping blocks sent at once do not interleave. The agent holds the
     * first call's write or undo while the second call is sent; once released, both answer, and the world is still
     * what replaying the edits still applied, oldest first, gives.
     */
    @Test
    void overlappingBuildsAndUndosSentAtOnceKeepTheWorldAsTheAppliedEditsMakeIt() throws Exception {
        Region area = Region.of("world", 0, 62, 0, 4, 63, 4);
        Map<BlockPos, String> start = snapshot(area);
        String gold = "fill 0,62,0 2,62,2 gold_block";
        String glass = "fill 0,62,0 4,63,4 glass";
        // An older edit undone while a later overlapping build writes: the undo waits, then is refused.
        String e1 = editId(h.ok("build_batch", "steps", gold));
        // A write that places blocks, not the build's check of its block names.
        java.util.function.Predicate<Object> placing = p -> p instanceof Ops.WriteParams w
                && java.util.Arrays.stream(w.grid().blocks()).anyMatch(b -> b >= 0);
        String[] answers = whileHeld(placing, () -> h.ok("build_batch", "steps", glass),
                () -> h.error("build_undo", "edit", e1));
        String e2 = editId(answers[0]);
        assertThat(answers[1]).startsWith("error: " + e1 + " not undone: later edit " + e2 + " set ");
        assertThat(snapshot(area)).isEqualTo(replay(start, gold, glass));
        assertThat(h.ok("build_undo")).startsWith("undid " + e2 + ":");
        assertThat(h.ok("build_undo")).startsWith("undid " + e1 + ":");
        assertThat(snapshot(area)).isEqualTo(start);
        // A build while an undo of an overlapping edit runs: the build waits, then lands on the undone world.
        String e3 = editId(h.ok("build_batch", "steps", glass));
        answers = whileHeld(p -> p instanceof Ops.UndoParams, () -> h.ok("build_undo"),
                () -> h.ok("build_batch", "steps", gold));
        assertThat(answers[0]).startsWith("undid " + e3 + ":");
        String e4 = editId(answers[1]);
        assertThat(snapshot(area)).isEqualTo(replay(start, gold));
        assertThat(h.ok("build_undo")).startsWith("undid " + e4 + ":");
        assertThat(snapshot(area)).isEqualTo(start);
        // A replace= build sent while another build writes masks against the world that build leaves (#704): here
        // the cells it may replace are glass only once the first build is done.
        answers = whileHeld(placing, () -> h.ok("build_batch", "steps", glass),
                () -> h.ok("build_batch", "steps", gold + " replace=glass"));
        String e5 = editId(answers[0]);
        assertThat(answers[1]).startsWith("edit e6: 9 blocks changed");
        assertThat(snapshot(area)).isEqualTo(replay(start, glass, gold));
        assertThat(h.ok("build_undo")).startsWith("undid e6:");
        assertThat(h.ok("build_undo")).startsWith("undid " + e5 + ":");
        assertThat(snapshot(area)).isEqualTo(start);
        // And one whose mask is the world before the first build: nothing is stone there any more.
        answers = whileHeld(placing, () -> h.ok("build_batch", "steps", glass),
                () -> h.ok("build_batch", "steps", gold + " replace=stone"));
        assertThat(answers[1]).isEqualTo("nothing to place: every step was empty or masked out by replace=");
        assertThat(snapshot(area)).isEqualTo(replay(start, glass));
        assertThat(h.ok("build_undo")).startsWith("undid " + editId(answers[0]) + ":");
        assertThat(snapshot(area)).isEqualTo(start);
        assertThat(h.error("build_undo")).contains("nothing to undo");
    }

    /**
     * Run {@code first}, hold it at the agent at the first write or undo {@code held} matches, send {@code second},
     * give it time to reach the agent, then let both finish; their answers, in that order.
     */
    private String[] whileHeld(java.util.function.Predicate<Object> held, java.util.concurrent.Callable<String> first,
                               java.util.concurrent.Callable<String> second) throws Exception {
        java.util.concurrent.CountDownLatch reached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean once = new java.util.concurrent.atomic.AtomicBoolean();
        h.agent.beforeChange = p -> {
            if (held.test(p) && once.compareAndSet(false, true)) {
                reached.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try (var calls = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var a = calls.submit(first);
            assertThat(reached.await(30, java.util.concurrent.TimeUnit.SECONDS)).as("first call reached the agent").isTrue();
            var b = calls.submit(second);
            Thread.sleep(300);
            release.countDown();
            return new String[] {a.get(30, java.util.concurrent.TimeUnit.SECONDS), b.get(30, java.util.concurrent.TimeUnit.SECONDS)};
        } finally {
            h.agent.beforeChange = p -> { };
        }
    }

    /** {@code start} with each fill step ("fill A B block", whole boxes) placed in order. */
    private static Map<BlockPos, String> replay(Map<BlockPos, String> start, String... fills) {
        Map<BlockPos, String> out = new HashMap<>(start);
        for (String fill : fills) {
            String[] w = fill.split(" ");
            Region box = Coords.region("world", w[1] + " " + w[2]);
            out.replaceAll((p, s) -> box.contains(p.x(), p.y(), p.z()) ? "minecraft:" + w[3] : s);
        }
        return out;
    }

    /** A few steps of every kind, all inside 4..19 on x and z and 60..69 on y, so they stay inside the test's area. */
    private static String randomSteps(Random rnd) {
        // One block that is always placed, so every batch is an edit with an id.
        StringBuilder out = new StringBuilder("set 4,60,4 bedrock\n");
        int n = 1 + rnd.nextInt(4);
        for (int i = 0; i < n; i++) {
            int x = 8 + rnd.nextInt(8);
            int y = 63 + rnd.nextInt(3);
            int z = 8 + rnd.nextInt(8);
            String b = List.of("sandstone", "glass:2,dirt:1", "oak_stairs[facing=west,half=bottom]", "air").get(rnd.nextInt(4));
            out.append(switch (rnd.nextInt(8)) {
                case 0 -> "fill " + x + "," + y + "," + z + " " + (x + rnd.nextInt(4)) + "," + (y + rnd.nextInt(4)) + ","
                        + (z + rnd.nextInt(4)) + " " + b;
                case 1 -> "set " + x + "," + y + "," + z + " " + (b.contains(":") ? "sandstone" : b);
                case 2 -> "sphere " + x + "," + y + "," + z + " r=" + rnd.nextInt(4) + " " + b;
                case 3 -> "line " + x + "," + y + "," + z + " " + (x - 4 + rnd.nextInt(8)) + "," + (y + rnd.nextInt(3)) + ","
                        + (z - 4 + rnd.nextInt(8)) + " " + b + " w=" + (1 + rnd.nextInt(4));
                case 4 -> "cylinder " + x + "," + y + "," + z + " r=" + rnd.nextInt(4) + " h=" + (1 + rnd.nextInt(4)) + " " + b;
                case 5 -> "copy " + (x - 4) + "," + (y - 3) + "," + (z - 4) + " " + x + "," + y + "," + z + " to="
                        + (x - 2) + "," + y + "," + (z - 2) + " rotate=" + 90 * rnd.nextInt(4);
                case 6 -> "fill " + x + "," + y + "," + z + " " + (x + 3) + "," + (y + 2) + "," + (z + 3) + " " + b
                        + " replace=air";
                default -> "structure " + x + "," + y + "," + z + "\nlegend: #=" + (b.contains(":") ? "sandstone" : b)
                        + "\ny=0\n#_.\n.#_\ny=1 same as y=0\nend";
            }).append('\n');
        }
        return out.toString();
    }

    @Test
    void copyThenEqualsTheSourceAndTurningBackRestoresStates() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region source = box(rnd, 7);
            scatter(rnd, source);
            Map<BlockPos, String> src = snapshot(source);
            BlockPos to = new BlockPos(source.min().x() + 40, source.min().y(), source.min().z() + 40);
            Region target = new Region("world", to, to.offset(source.sizeX() - 1, source.sizeY() - 1, source.sizeZ() - 1));
            Region margin = grow(target, 1);
            scatter(rnd, margin);
            Map<BlockPos, String> before = snapshot(margin);
            boolean air = rnd.nextBoolean();
            h.ok("build_batch", "steps", "copy " + corners(source) + " to=" + show(to) + " air=" + air);
            for (BlockPos p : before.keySet()) {
                String expected = before.get(p);
                if (target.contains(p.x(), p.y(), p.z())) {
                    String s = src.get(p.offset(-40, 0, -40));
                    expected = air || !s.matches("minecraft:(cave_|void_)?air") ? s : before.get(p);
                }
                assertThat(h.agent.get(p.x(), p.y(), p.z())).as("seed %d air=%s at %s", seed, air, p).isEqualTo(expected);
            }
            // A quarter turn there and three back is the source again, block states included.
            int q = 1 + rnd.nextInt(3);
            BlockPos turned = new BlockPos(source.min().x() + 80, source.min().y(), source.min().z());
            h.ok("build_batch", "steps", "copy " + corners(source) + " to=" + show(turned) + " rotate=" + 90 * q);
            int sx = q % 2 == 0 ? source.sizeX() : source.sizeZ();
            int sz = q % 2 == 0 ? source.sizeZ() : source.sizeX();
            BlockPos back = new BlockPos(source.min().x() + 120, source.min().y(), source.min().z());
            h.ok("build_batch", "steps", "copy " + show(turned) + " " + show(turned.offset(sx - 1, source.sizeY() - 1, sz - 1))
                    + " to=" + show(back) + " rotate=" + 90 * (4 - q));
            for (BlockPos p : src.keySet()) {
                BlockPos at = p.offset(120, 0, 0);
                assertThat(h.agent.get(at.x(), at.y(), at.z())).as("seed %d q=%d at %s", seed, q, p).isEqualTo(src.get(p));
            }
        }
    }

    @Test
    void aSliceWithStatesPastesBackAsTheSameBlocks() {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region source = box(rnd, 9);
            scatter(rnd, source);
            Map<BlockPos, String> src = snapshot(source);
            String slice = h.ok("world_slice", "region", corners(source), "states", true, "maxChars", 24_000);
            BlockPos to = source.min().offset(0, 0, 60);
            h.ok("build_batch", "steps", "structure " + show(to) + "\n" + slice.substring(slice.indexOf("legend:")) + "\nend");
            for (BlockPos p : src.keySet()) {
                String s = src.get(p);
                // Every kind of air is shown as "." and pastes as plain air.
                String expected = s.matches("minecraft:(cave_|void_)?air") ? "minecraft:air" : s;
                assertThat(h.agent.get(p.x(), p.y(), p.z() + 60)).as("seed %d at %s", seed, p).isEqualTo(expected);
            }
        }
    }

    @Test
    void aRegionDiffedWithItsOwnSnapshotIsUnchangedAndChangesAreCountedExactly() throws Exception {
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region region = box(rnd, 8);
            scatter(rnd, region);
            String schem = "snap/s" + seed + ".schem";
            h.ok("structure_save", "region", corners(region), "file", schem);
            String text = "snap/s" + seed + ".txt";
            String slice = h.ok("world_slice", "region", corners(region), "states", true, "maxChars", 24_000);
            Files.createDirectories(workspace.resolve("snap"));
            Files.writeString(workspace.resolve(text), slice);
            for (String snap : List.of(schem, text)) {
                assertThat(h.ok("world_summary", "region", corners(region), "diff", snap)).as("seed %d", seed)
                        .isEqualTo("no change since " + snap);
            }
            // Change k distinct cells to a block the region does not hold, at least one on a corner of the box.
            List<BlockPos> cells = new ArrayList<>(snapshot(region).keySet());
            java.util.Collections.shuffle(cells, rnd);
            int k = 1 + rnd.nextInt(Math.min(5, cells.size()));
            List<BlockPos> changed = new ArrayList<>(cells.subList(0, k));
            changed.set(0, rnd.nextBoolean() ? region.min() : region.max());
            changed = changed.stream().distinct().toList();
            StringBuilder steps = new StringBuilder();
            changed.forEach(p -> steps.append("set ").append(show(p)).append(" emerald_block\n"));
            h.ok("build_batch", "steps", steps.toString());
            Region spanned = span(changed);
            for (String snap : List.of(schem, text)) {
                assertThat(h.ok("world_summary", "region", corners(region), "diff", snap)).as("seed %d %s", seed, snap)
                        .startsWith(changed.size() + (changed.size() == 1 ? " block" : " blocks") + " changed since " + snap
                                + ", in world " + corners(spanned) + "\n");
            }
        }
    }

    @Test
    void summaryCountsAddUpToTheVolume() {
        Pattern kind = Pattern.compile("(?:blocks:|,) (\\S+) (\\d+)");
        Pattern layer = Pattern.compile(" (-?\\d+)(?:\\.\\.(-?\\d+))?:(\\d+)");
        for (long seed = 0; seed < SEEDS; seed++) {
            Random rnd = new Random(seed);
            Region region = box(rnd, 10);
            scatter(rnd, region);
            Map<BlockPos, String> blocks = snapshot(region);
            long air = blocks.values().stream().filter(s -> s.matches("minecraft:(cave_|void_)?air")).count();
            String out = h.ok("world_summary", "region", corners(region));
            long volume = region.volume();
            assertThat(out).as("seed %d", seed).startsWith("region world " + corners(region) + " size " + region.sizeX() + "x"
                    + region.sizeY() + "x" + region.sizeZ() + " = " + volume + " blocks, " + Math.round(100.0 * air / volume)
                    + "% air\n");
            if (air == volume) {
                assertThat(out).contains("empty: only air");
                continue;
            }
            String[] lines = out.split("\n");
            long kinds = 0;
            Matcher m = kind.matcher(lines[1]);
            while (m.find()) {
                kinds += Long.parseLong(m.group(2));
            }
            long layers = 0;
            int layerCount = 0;
            Matcher l = layer.matcher(lines[3].substring(lines[3].indexOf("): ") + 1));
            while (l.find()) {
                int from = Integer.parseInt(l.group(1));
                int to = l.group(2) == null ? from : Integer.parseInt(l.group(2));
                layers += (long) (to - from + 1) * Long.parseLong(l.group(3));
                layerCount += to - from + 1;
            }
            assertThat(kinds).as("seed %d kinds: %s", seed, out).isEqualTo(volume - air);
            assertThat(layers).as("seed %d layers: %s", seed, out).isEqualTo(volume - air);
            assertThat(layerCount).as("seed %d", seed).isEqualTo(region.sizeY());
            Region solid = span(blocks.entrySet().stream().filter(e -> !e.getValue().matches("minecraft:(cave_|void_)?air"))
                    .map(Map.Entry::getKey).toList());
            assertThat(lines[2]).as("seed %d", seed).isEqualTo("non-air bounds: " + show(solid.min()) + " " + show(solid.max()));
        }
    }

    @Test
    void summaryAndPathStitchReadsAcrossThePartBorder() {
        // 129 x 64 x 128: one column over the per-call limit, so the agent is read in two parts split at x=127|128.
        synchronized (h.agent) {
            h.agent.blocks.put(new BlockPos(127, 64, 5), "minecraft:gold_block");
            h.agent.blocks.put(new BlockPos(128, 64, 5), "minecraft:gold_block");
            h.agent.blocks.put(new BlockPos(128, 127, 127), "minecraft:diamond_block");
            for (int x = 0; x <= 128; x++) {
                h.agent.blocks.put(new BlockPos(x, 64, 0), "minecraft:oak_planks");
            }
        }
        h.agent.reads.clear();
        String summary = h.ok("world_summary", "region", "0,64,0 128,127,127");
        assertThat(h.agent.reads).hasSize(2);
        assertThat(summary).contains("oak_planks 129, gold_block 2 at 127,64,5 128,64,5, diamond_block 1 at 128,127,127\n")
                .contains("non-air bounds: 0,64,0 128,127,127\nnon-air per layer (y:count): 64:131 65..126:0 127:1");
        // A walk along the planks crosses the border between the two reads.
        assertThat(h.ok("world_summary", "region", "0,63,0 128,126,127", "path", "0,65,0 128,65,0"))
                .startsWith("reachable: 128 moves");
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** A box of 1 to {@code side} blocks a side, sometimes flat or a single block, around y=64 where stone meets air. */
    private static Region box(Random rnd, int side) {
        int x = rnd.nextInt(200) - 100;
        int y = 60 + rnd.nextInt(6);
        int z = rnd.nextInt(200) - 100;
        return Region.of("world", x, y, z, x + rnd.nextInt(side), y + rnd.nextInt(Math.min(side, 6)), z + rnd.nextInt(side));
    }

    private static Region grow(Region r, int by) {
        return Region.of(r.world(), r.min().x() - by, r.min().y() - by, r.min().z() - by, r.max().x() + by,
                r.max().y() + by, r.max().z() + by);
    }

    private static Region span(List<BlockPos> cells) {
        Region out = new Region("world", cells.get(0), cells.get(0));
        for (BlockPos p : cells) {
            out = Region.of("world", Math.min(out.min().x(), p.x()), Math.min(out.min().y(), p.y()),
                    Math.min(out.min().z(), p.z()), Math.max(out.max().x(), p.x()), Math.max(out.max().y(), p.y()),
                    Math.max(out.max().z(), p.z()));
        }
        return out;
    }

    /** Random blocks in about two thirds of the region's cells; the rest keep the fake world's stone or air. */
    private void scatter(Random rnd, Region r) {
        synchronized (h.agent) {
            for (int y = r.min().y(); y <= r.max().y(); y++) {
                for (int z = r.min().z(); z <= r.max().z(); z++) {
                    for (int x = r.min().x(); x <= r.max().x(); x++) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (rnd.nextInt(3) == 0) {
                            h.agent.blocks.remove(p);
                        } else {
                            h.agent.blocks.put(p, KINDS[rnd.nextInt(KINDS.length)]);
                        }
                    }
                }
            }
        }
    }

    private Map<BlockPos, String> snapshot(Region r) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    out.put(new BlockPos(x, y, z), h.agent.get(x, y, z));
                }
            }
        }
        return new HashMap<>(out);
    }

    private static String editId(String result) {
        assertThat(result).startsWith("edit e");
        return result.substring(5, result.indexOf(':'));
    }

    private static String show(BlockPos p) {
        return p.x() + "," + p.y() + "," + p.z();
    }

    private static String corners(Region r) {
        return show(r.min()) + " " + show(r.max());
    }
}
