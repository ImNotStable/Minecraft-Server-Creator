package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * A clean start-up log of every platform (in {@code startup-logs/}), read the way {@link ServerManager} reads a
 * server's output, plain and coloured, with and without a user's stack trace and warning, delivered in chunks of
 * several fixed sizes: the ready line is seen and errors and warnings are counted exactly (#294).
 */
class StartupLogFixturesTest {
    /** A platform's clean log, the known-harmless entries it hides, and a user's error and warning as it prints them. */
    private record Fixture(String platform, int hidden, List<String> userError, String userWarning) {
    }

    private static final List<String> PLUGIN_ERROR = List.of(
            "[12:00:05 ERROR]: Could not pass event PlayerJoinEvent to MyPlugin v1.0",
            "java.lang.NullPointerException: Cannot invoke \"org.bukkit.World.getName()\" because \"world\" is null",
            "\tat com.example.myplugin.Arena.load(Arena.java:42) ~[MyPlugin.jar:?]",
            "\tat org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:288) ~[paper-api.jar:?]",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583) ~[?:?]");
    private static final String PLUGIN_WARNING = "[12:00:05 WARN]: [MyPlugin] config.yml missing key 'arena', using default";
    private static final List<String> MOD_ERROR = List.of(
            "[12:00:05] [Server thread/ERROR]: Failed to load arena",
            "java.lang.IllegalStateException: boom",
            "\tat com.example.mymod.Arena.load(Arena.java:12) ~[mymod.jar:?]",
            "\tat net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1) ~[?:?]");
    private static final String MOD_WARNING = "[12:00:05] [Server thread/WARN]: Mod mymod uses a deprecated entrypoint";
    private static final List<String> LOADER_ERROR = List.of(
            "[12:00:05] [Server thread/ERROR] [mymod/]: Arena failed",
            "java.lang.IllegalStateException: boom",
            "\tat TRANSFORMER/mymod@1.0/com.example.mymod.Arena.load(Arena.java:12) ~[mymod-1.0.jar%23190!/:?]",
            "\tat net.neoforged.fml.javafmlmod.FMLModContainer.constructMod(FMLModContainer.java:110) ~[?:?]");
    private static final String LOADER_WARNING = "[12:00:05] [Server thread/WARN] [mymod/]: deprecated thing";
    /** Minestom's default exception handler prints a listener's exception bare. */
    private static final List<String> LISTENER_ERROR = List.of(
            "java.lang.IllegalStateException: join failed",
            "\tat com.example.lobby.Lobby.onJoin(Lobby.java:30)",
            "\tat net.minestom.server.event.EventNodeImpl$Handle.call(EventNodeImpl.java:1)");
    private static final String SIMPLE_WARNING = "[main] WARN com.example.lobby.LobbyConfig - lobby.json missing, using defaults";

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("paper", 13, PLUGIN_ERROR, PLUGIN_WARNING),
            new Fixture("purpur", 13, PLUGIN_ERROR, PLUGIN_WARNING),
            new Fixture("fabric", 6, MOD_ERROR, MOD_WARNING),
            new Fixture("quilt", 7, MOD_ERROR, MOD_WARNING),
            new Fixture("neoforge", 8, LOADER_ERROR, LOADER_WARNING),
            new Fixture("forge", 9, LOADER_ERROR, LOADER_WARNING),
            new Fixture("minestom", 0, LISTENER_ERROR, SIMPLE_WARNING));

    private static final int[] CHUNKS = {1, 3, 7, 64, 8192};

    @TestFactory
    Stream<DynamicTest> everyPlatformsStartUpIsClassified() {
        return FIXTURES.stream().map(f -> DynamicTest.dynamicTest(f.platform(), () -> check(f)));
    }

    private static void check(Fixture f) throws IOException {
        List<String> clean = fixture(f.platform());
        List<String> failures = new ArrayList<>();
        for (boolean injected : new boolean[] {false, true}) {
            List<String> lines = injected ? inject(clean, f) : clean;
            for (boolean ansi : new boolean[] {false, true}) {
                String text = String.join("\n", ansi ? lines.stream().map(StartupLogFixturesTest::colour).toList() : lines) + "\n";
                for (int chunk : CHUNKS) {
                    LogBuffer log = new LogBuffer(20_000);
                    boolean[] ready = {false};
                    ServerManager.pump(new Chunked(text.getBytes(StandardCharsets.UTF_8), chunk), log, () -> ready[0] = true);
                    LogCondenser.Condensed c = LogCondenser.analyze(log.all(), 6000);
                    String got = "ready=" + ready[0] + " errors=" + c.errors() + " warnings=" + c.warnings()
                            + " hidden=" + c.hidden();
                    String want = "ready=true errors=" + (injected ? 3 : 0) + " warnings=" + (injected ? 1 : 0)
                            + " hidden=" + f.hidden();
                    if (!got.equals(want)) {
                        failures.add((injected ? "injected" : "clean") + (ansi ? "+ansi" : "") + " chunk=" + chunk
                                + ": " + got);
                    }
                }
            }
        }
        assertThat(failures).isEmpty();
    }

    /** The user's error and warning before the ready line, and the error twice more after it. */
    private static List<String> inject(List<String> clean, Fixture f) {
        List<String> out = new ArrayList<>(clean.subList(0, clean.size() - 1));
        out.addAll(f.userError());
        out.add(f.userWarning());
        out.add(clean.getLast());
        out.addAll(f.userError());
        out.addAll(f.userError());
        return out;
    }

    /** Colours a line by its level, the way the servers' consoles do, and resets at the end. */
    private static String colour(String line) {
        String start = line.contains("ERROR") ? "\u001b[31m" : line.contains("WARN") ? "\u001b[33;1m" : "\u001b[0m";
        return start + line + "\u001b[m";
    }

    private static List<String> fixture(String platform) throws IOException {
        try (InputStream in = Objects.requireNonNull(
                StartupLogFixturesTest.class.getResourceAsStream("/startup-logs/" + platform + ".txt"), platform)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
    }

    /** A process's output arriving at most {@code chunk} bytes per read. */
    private static final class Chunked extends InputStream {
        private final byte[] bytes;
        private final int chunk;
        private int pos;

        Chunked(byte[] bytes, int chunk) {
            this.bytes = bytes;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return pos < bytes.length ? bytes[pos++] & 0xff : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= bytes.length) {
                return -1;
            }
            int n = Math.min(Math.min(len, chunk), bytes.length - pos);
            System.arraycopy(bytes, pos, b, off, n);
            pos += n;
            return n;
        }
    }
}
