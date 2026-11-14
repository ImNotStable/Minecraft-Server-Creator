package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeBuildMain;
import io.github.imnotstable.msc.core.tools.StepsAccess;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * #417: the core killed between any two file operations of a dev_cycle (the MCP client closing mid-call), not just a
 * step failing. At the moment of the kill nothing in the scan folder may be a temp, aside or half-written file: only
 * whole jars. A fresh dev_cycle after it then leaves exactly one copy of the project's jar there, the record naming
 * it, every other record kept, and nothing an interrupted call left, anywhere in the workspace.
 *
 * <p>A kill is simulated with the steps' kill-point seam: at the n-th point the disk is copied as it is (what a killed
 * process leaves; the cleanup the dying thread would still run is not part of it), and the call stops there.
 */
class DevCycleCrashTest {
    @TempDir
    Path root;

    /** What the kill hook throws: an Error, so no step catches it as a failure. */
    private static final class Killed extends Error {
        private static final long serialVersionUID = 1L;

        Killed() {
            super("killed", null, false, false);
        }
    }

    private static List<String> java(Class<?> main) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), main.getName());
    }

    private static final String OTHER = "/elsewhere/Other=%s/other.jar";

    /** A project whose jar now deploys as arena.jar, and a workspace where it was deployed as oldname.jar. */
    private Path workspace(String name, String addons, String descriptor) throws IOException {
        Path project = root.resolve("demo");
        if (!Files.exists(project)) {
            Files.writeString(Files.createDirectories(project.resolve("src/main/resources")).resolve(descriptor), "{}");
            Files.writeString(project.resolve("version.txt"), "2.0");
            Files.writeString(project.resolve("name.txt"), "arena");
        }
        Path ws = Files.createDirectories(root.resolve(name));
        Path folder = Files.createDirectories(ws.resolve("run").resolve(addons));
        Files.writeString(folder.resolve("oldname.jar"), "1.0");
        Files.writeString(folder.resolve("other.jar"), "other");
        // What cores before #417 could leave: an aside name Quilt loads.
        Files.writeString(folder.resolve("older.jar.msc-removing"), "0.9");
        Files.createDirectories(ws.resolve(".msc"));
        Files.writeString(ws.resolve(".msc/deployed.properties"), String.format(OTHER, addons) + "\n"
                + project.toAbsolutePath().normalize() + "=" + addons + "/oldname.jar\n");
        return ws;
    }

    private CoreHarness open(Path ws) throws Exception {
        return new CoreHarness(ws, c -> c.withBuildCommand(java(FakeBuildMain.class)));
    }

    private String devCycle(CoreHarness h) {
        var r = h.call("dev_cycle", "project", root.resolve("demo").toString(), "restart", false);
        assertThat(r.ok()).as(r.text()).isTrue();
        return r.text();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"quilt.mod.json mods", "plugin.yml plugins"})
    void aKillBetweenAnyTwoFileOperationsLeavesNothingLoadableAndTheNextCallCleansUp(String setup) throws Exception {
        String descriptor = setup.split(" ")[0];
        String addons = setup.split(" ")[1];
        // A clean run lists the kill points, and is the end state every run must reach.
        Path clean = workspace("clean", addons, descriptor);
        List<String> points = new ArrayList<>();
        try (CoreHarness h = open(clean)) {
            StepsAccess.killPoints(points::add);
            try {
                devCycle(h);
            } finally {
                StepsAccess.killPoints(null);
            }
        }
        assertThat(points).as("the sweep, the deploy, the removal and the record each have points").hasSizeGreaterThan(8);
        endState(clean, addons);

        for (int n = 0; n < points.size(); n++) {
            Path ws = workspace("run-" + n, addons, descriptor);
            Path snapshot = root.resolve("killed-" + n);
            int at = n;
            int[] reached = {0};
            try (CoreHarness h = open(ws)) {
                StepsAccess.killPoints(where -> {
                    if (reached[0]++ == at) {
                        copyTree(ws, snapshot);
                        throw new Killed();
                    }
                });
                try {
                    devCycle(h);
                } catch (Killed expected) {
                    // The core is gone; what it left is in the snapshot.
                } finally {
                    StepsAccess.killPoints(null);
                }
            }
            String where = "killed at " + points.get(n);
            assertThat(snapshot).as(where).isDirectory();
            for (String entry : entries(snapshot.resolve("run").resolve(addons))) {
                assertThat(entry).as(where + ": only whole jars (and the leftover of an older core) sit in " + addons)
                        .isIn("oldname.jar", "other.jar", "arena.jar", "older.jar.msc-removing");
            }
            Path arena = snapshot.resolve("run").resolve(addons).resolve("arena.jar");
            if (Files.exists(arena)) {
                assertThat(arena).as(where).hasContent("2.0");
            }
            try (CoreHarness h = open(snapshot)) {
                devCycle(h);
            }
            endState(snapshot, addons);
        }
    }

    /** One copy of the project's jar, the other project's jar, both records, and nothing left over anywhere. */
    private void endState(Path ws, String addons) throws IOException {
        assertThat(entries(ws.resolve("run").resolve(addons))).as(ws.getFileName().toString())
                .containsExactlyInAnyOrder("arena.jar", "other.jar");
        assertThat(ws.resolve("run").resolve(addons).resolve("arena.jar")).hasContent("2.0");
        Properties record = new Properties();
        try (InputStream in = Files.newInputStream(ws.resolve(".msc/deployed.properties"))) {
            record.load(in);
        }
        Map<String, String> records = new java.util.HashMap<>();
        record.stringPropertyNames().forEach(k -> records.put(k, record.getProperty(k)));
        assertThat(records).containsExactlyInAnyOrderEntriesOf(Map.of(
                root.resolve("demo").toAbsolutePath().normalize().toString(), addons + "/arena.jar",
                "/elsewhere/Other", addons + "/other.jar"));
        try (Stream<Path> tree = Files.walk(ws)) {
            assertThat(tree.map(p -> ws.relativize(p).toString().replace('\\', '/')))
                    .as(ws.getFileName() + ": nothing an interrupted call left")
                    .noneMatch(p -> p.endsWith(".part") || p.contains("msc-removing"))
                    .noneMatch(p -> p.startsWith("run/.msc/staging/"));
        }
    }

    private static List<String> entries(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static void copyTree(Path from, Path to) {
        try (Stream<Path> tree = Files.walk(from)) {
            for (Path p : tree.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
