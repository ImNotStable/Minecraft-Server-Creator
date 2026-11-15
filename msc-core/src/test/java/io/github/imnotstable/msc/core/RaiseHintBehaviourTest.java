package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.OutputStream;
import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * #614, by running the product: every tool that takes maxChars, called at the largest one (24000) with a result too
 * large for it, is cut, and its cut line never says to raise maxChars, which a retry could not do. The table must
 * name every registered tool with a maxChars argument, so a new one is called here too.
 */
class RaiseHintBehaviourTest {
    @TempDir
    Path dir;

    private interface Setup {
        void run(CoreHarness h) throws Exception;
    }

    /** A call of {@code tool} at maxChars=24000 on a world, pack or API index that {@code setup} made too large. */
    private record Row(String tool, Setup setup, Object... args) {
    }

    private static final String[] KINDS = {"stone", "dirt", "oak_planks", "sand", "gravel"};

    private final List<Row> agentRows = List.of(
            new Row("world_slice", h -> {
                // Random blocks: no two rows or layers alike, so the grids do not fold into "same as" lines.
                Random random = new Random(614);
                for (int y = 64; y < 96; y++) {
                    for (int z = 0; z < 32; z++) {
                        for (int x = 0; x < 32; x++) {
                            h.agent.blocks.put(new BlockPos(x, y, z),
                                    "minecraft:" + KINDS[random.nextInt(KINDS.length)]);
                        }
                    }
                }
            }, "region", "0,64,0 31,95,31"),
            // One block above the flat ground: 256 rows of 256 height digits.
            new Row("world_heightmap", h -> h.agent.blocks.put(new BlockPos(0, 70, 0), "minecraft:stone"),
                    "area", "0,0 255,255"),
            new Row("texture_view", h -> h.ok("texture", "name", "block/big", "palette", palette(), "rows", bigRows()),
                    "name", "block/big"),
            new Row("api_search", h -> { }, "query", "widget", "limit", 100),
            new Row("api_class", h -> { }, "name", "Big", "limit", 300));

    private static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static List<String> palette() {
        List<String> palette = new ArrayList<>();
        for (int i = 0; i < SYMBOLS.length(); i++) {
            palette.add(SYMBOLS.charAt(i) + "=#" + String.format("%02x%02x%02x", i * 4, 255 - i * 4, i));
        }
        return palette;
    }

    /** 160 rows of 160 pixels in 62 colours. */
    private static List<String> bigRows() {
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < 160; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 160; x++) {
                row.append(SYMBOLS.charAt((x * 7 + y * 3) % SYMBOLS.length()));
            }
            rows.add(row.toString());
        }
        return rows;
    }

    /** 120 classes with 250-character names, and a class Big with 300 methods of 120-character names. */
    private Path apiJar() throws Exception {
        Path jar = dir.resolve("api.jar");
        ClassFile cf = ClassFile.of();
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream zip = new JarOutputStream(out)) {
            for (int i = 0; i < 120; i++) {
                String name = "com.example.Widget" + i + "q".repeat(250);
                zip.putNextEntry(new JarEntry(name.replace('.', '/') + ".class"));
                zip.write(cf.build(ClassDesc.of(name), cb -> cb.withFlags(ClassFile.ACC_PUBLIC)));
                zip.closeEntry();
            }
            zip.putNextEntry(new JarEntry("com/example/Big.class"));
            zip.write(cf.build(ClassDesc.of("com.example.Big"), cb -> {
                cb.withFlags(ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT);
                for (int i = 0; i < 300; i++) {
                    cb.withMethod("member" + i + "m".repeat(120), MethodTypeDesc.of(ConstantDescs.CD_void),
                            ClassFile.ACC_PUBLIC | ClassFile.ACC_ABSTRACT, mb -> { });
                }
            }));
            zip.closeEntry();
        }
        return jar;
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    @Test
    void noCutAtTheLargestMaxCharsSaysToRaiseIt() throws Exception {
        Path api = apiJar();
        Map<String, String> results = new LinkedHashMap<>();
        TreeSet<String> takesMaxChars = new TreeSet<>();
        for (Row row : agentRows) {
            Path ws = Files.createDirectories(dir.resolve(row.tool()));
            try (CoreHarness h = new CoreHarness(ws, c -> c.withApiJars(List.of(api))).withAgent()) {
                h.core.registry().tools().stream().filter(Budget::takesMaxChars).map(Tool::name)
                        .forEach(takesMaxChars::add);
                row.setup().run(h);
                List<Object> args = new ArrayList<>(List.of(row.args()));
                args.addAll(List.of("maxChars", Budget.MAX_CHARS));
                results.put(row.tool(), h.ok(row.tool(), args.toArray()));
            }
        }
        // dev_cycle and server_logs on a fake server with 300 errors and a build with 200 failed tests.
        Path ws = Files.createDirectories(dir.resolve("server"));
        Path project = Files.createDirectories(ws.resolve("demo"));
        Files.writeString(project.resolve("version.txt"), "1.0");
        Files.writeString(project.resolve("many-failing-tests.flag"), "");
        Files.writeString(Files.createDirectories(ws.resolve("run")).resolve("many-errors.flag"), "");
        try (CoreHarness h = new CoreHarness(ws, c -> c.withServerCommand(java(FakeServerMain.class))
                .withBuildCommand(java(FakeBuildMain.class)))) {
            int max = Budget.MAX_CHARS;
            results.put("dev_cycle", h.ok("dev_cycle", "project", "demo", "maxChars", max));
            results.put("server_logs errors", h.ok("server_logs", "maxChars", max));
            results.put("server_logs tail", h.ok("server_logs", "mode", "tail", "lines", 100_000, "maxChars", max));
            results.put("server_logs grep", h.ok("server_logs", "mode", "grep", "pattern", ".", "lines", 100_000,
                    "maxChars", max));
            h.ok("server_stop");
        }

        assertThat(results.keySet().stream().map(k -> k.split(" ")[0]).distinct().sorted())
                .as("a row for every tool with maxChars").containsExactlyElementsOf(takesMaxChars);
        results.forEach((call, text) -> assertThat(text).as(call).hasSizeLessThanOrEqualTo(Budget.MAX_CHARS)
                .containsPattern("\\[truncated: |chars cut]|outputs cut")
                .doesNotContainIgnoringCase("raise maxChars"));
    }
}
