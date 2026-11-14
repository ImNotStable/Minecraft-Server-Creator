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
    /** The same with a checked exception thrown as is: the bare class name ends in the suffix itself (#322). */
    private static final List<String> BARE_LISTENER_ERROR = List.of(
            "java.lang.Exception: join failed",
            "\tat com.example.lobby.Lobby.onJoin(Lobby.java:30)");
    /**
     * {@code e.printStackTrace()} in a plugin on Paper and Purpur: System.err is logged at WARN, one line per line,
     * tagged with the plugin (#336). Paper nags once about the plugin's use of System.err.
     */
    private static final List<String> PLUGIN_PRINTED = printed("[12:00:05 WARN]: [MyPlugin] [STDERR] ", List.of(
            "java.lang.IllegalStateException: Arena 'spawn' has no world",
            "\tat MyPlugin-1.0.jar//com.example.myplugin.Arena.load(Arena.java:42)",
            "\tat MyPlugin-1.0.jar//com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:17)",
            "\tat org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:288)",
            "\tat io.papermc.paper.plugin.manager.PaperPluginInstanceManager.enablePlugin(PaperPluginInstanceManager.java:202)",
            "\tat io.papermc.paper.plugin.manager.PaperPluginManagerImpl.enablePlugin(PaperPluginManagerImpl.java:109)",
            "\tat org.bukkit.plugin.SimplePluginManager.enablePlugin(SimplePluginManager.java:520)",
            "\tat org.bukkit.craftbukkit.CraftServer.enablePlugin(CraftServer.java:654)",
            "\tat org.bukkit.craftbukkit.CraftServer.enablePlugins(CraftServer.java:565)",
            "\tat net.minecraft.server.MinecraftServer.loadWorld0(MinecraftServer.java:676)",
            "\tat net.minecraft.server.MinecraftServer.loadLevel(MinecraftServer.java:434)",
            "\tat net.minecraft.server.dedicated.DedicatedServer.initServer(DedicatedServer.java:308)",
            "\tat net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1101)",
            "\tat net.minecraft.server.MinecraftServer.lambda$spin$2(MinecraftServer.java:310)",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583)",
            "Caused by: java.io.FileNotFoundException: plugins/MyPlugin/arenas/spawn.yml (No such file or directory)",
            "\tat java.base/java.io.FileInputStream.open0(Native Method)",
            "\tat java.base/java.io.FileInputStream.open(FileInputStream.java:213)",
            "\tat java.base/java.io.FileInputStream.<init>(FileInputStream.java:152)",
            "\tat MyPlugin-1.0.jar//com.example.myplugin.ArenaFile.read(ArenaFile.java:20)",
            "\tat MyPlugin-1.0.jar//com.example.myplugin.Arena.load(Arena.java:40)",
            "\t... 13 more"));
    private static final String NAG_WARNING = "[12:00:05 WARN]: Nag author(s): '[Someone]' of 'MyPlugin v1.0' about"
            + " their usage of System.out/err.print. Please use your plugin's logger instead (JavaPlugin#getLogger).";
    /** The same printed by server code or a library: Paper logs it at WARN without a tag. */
    private static final List<String> SERVER_PRINTED = printed("[12:00:05 WARN]: ",
            PLUGIN_PRINTED.stream().map(l -> l.substring(l.indexOf("[STDERR] ") + 9)).toList());
    /** Vanilla's logged System.err, which Fabric and Quilt keep: INFO, tagged {@code [STDERR]:}. */
    private static final List<String> MOD_PRINTED = printed("[12:00:05] [Server thread/INFO]: [STDERR]: ", List.of(
            "java.lang.IllegalStateException: boom",
            "\tat com.example.mymod.Arena.load(Arena.java:12)",
            "\tat net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1)",
            "\tat java.base/java.lang.Thread.run(Thread.java:1583)"));
    /** Forge and NeoForge log System.err under the logger STDERR, with the printing call in front. */
    private static final List<String> LOADER_PRINTED = List.of(
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printStackTrace:662]: java.lang.IllegalStateException: boom",
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printStackTrace:662]: \tat TRANSFORMER/mymod@1.0/com.example.mymod.Arena.load(Arena.java:12)",
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printStackTrace:662]: \tat TRANSFORMER/minecraft@26.3/net.minecraft.server.MinecraftServer.runServer(MinecraftServer.java:1)",
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printEnclosedStackTrace:688]: Caused by: java.io.IOException: disk",
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printEnclosedStackTrace:697]: \tat TRANSFORMER/mymod@1.0/com.example.mymod.Arena.read(Arena.java:30)",
            "[12:00:05] [Server thread/INFO] [STDERR/]: [java.lang.Throwable:printEnclosedStackTrace:711]: \t... 2 more");
    private static final String SIMPLE_WARNING = "[main] WARN com.example.lobby.LobbyConfig - lobby.json missing, using defaults";

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("paper", 13, PLUGIN_ERROR, PLUGIN_WARNING),
            new Fixture("purpur", 13, PLUGIN_ERROR, PLUGIN_WARNING),
            new Fixture("fabric", 6, MOD_ERROR, MOD_WARNING),
            new Fixture("quilt", 7, MOD_ERROR, MOD_WARNING),
            new Fixture("neoforge", 8, LOADER_ERROR, LOADER_WARNING),
            new Fixture("forge", 9, LOADER_ERROR, LOADER_WARNING),
            new Fixture("paper", 13, PLUGIN_PRINTED, NAG_WARNING),
            new Fixture("purpur", 13, PLUGIN_PRINTED, NAG_WARNING),
            new Fixture("paper", 13, SERVER_PRINTED, PLUGIN_WARNING),
            new Fixture("purpur", 13, SERVER_PRINTED, PLUGIN_WARNING),
            new Fixture("fabric", 6, MOD_PRINTED, MOD_WARNING),
            new Fixture("quilt", 7, MOD_PRINTED, MOD_WARNING),
            new Fixture("neoforge", 8, LOADER_PRINTED, LOADER_WARNING),
            new Fixture("forge", 9, LOADER_PRINTED, LOADER_WARNING),
            new Fixture("minestom", 0, LISTENER_ERROR, SIMPLE_WARNING),
            new Fixture("minestom", 0, BARE_LISTENER_ERROR, SIMPLE_WARNING));

    /** Each line of a printed trace with the prefix a platform's logged System.err puts in front. */
    private static List<String> printed(String prefix, List<String> lines) {
        return lines.stream().map(l -> prefix + l).toList();
    }

    @TestFactory
    Stream<DynamicTest> everyPlatformsStartUpIsClassified() {
        return FIXTURES.stream().map(f -> DynamicTest.dynamicTest(f.platform() + " #" + FIXTURES.indexOf(f), () -> check(f)));
    }

    private static void check(Fixture f) throws IOException {
        List<String> clean = fixture(f.platform());
        List<String> failures = new ArrayList<>();
        for (boolean injected : new boolean[] {false, true}) {
            List<String> lines = injected ? inject(clean, f) : clean;
            for (boolean ansi : new boolean[] {false, true}) {
                String text = String.join("\n", ansi ? lines.stream().map(StartupLogFixturesTest::colour).toList() : lines) + "\n";
                for (int chunk : ChunkedInput.CHUNKS) {
                    LogBuffer log = new LogBuffer(20_000, Long.MAX_VALUE);
                    boolean[] ready = {false};
                    LogReader.pump(new ChunkedInput(text.getBytes(StandardCharsets.UTF_8), chunk), log, () -> ready[0] = true);
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

    /**
     * An adversarial log, what the reader must make of it, and a text the condensed report must contain. No report
     * may hold a CR or an escape character.
     */
    private record Case(String name, String text, boolean ready, int errors, int warnings, String contains) {
    }

    private static final String MINESTOM_READY = "[main] INFO net.minestom.server.MinecraftServer - Done (0.5s)!\n";
    private static final String PAPER_READY = "[12:00:09 INFO]: Done (3.2s)! For help, type \"help\"\n";
    private static final String FRAME = "\tat com.example.lobby.Lobby.onJoin(Lobby.java:30)\n";

    private static List<Case> adversarial() {
        List<Case> cases = new ArrayList<>();
        for (String type : List.of("java.lang.Exception", "java.lang.Error", "java.lang.Throwable", "com.example.Exception")) {
            cases.add(new Case("two bare " + type + " after the ready line (#322)",
                    MINESTOM_READY + type + ": first\n" + FRAME + type + ": second\n" + FRAME, true, 2, 0, type + ": second"));
        }
        cases.add(new Case("CRLF line ends", "[12:00:00 INFO]: Starting\r\n[12:00:01 WARN]: low memory\r\n"
                + PAPER_READY.replace("\n", "\r\n"), true, 0, 1, "WARN low memory"));
        cases.add(new Case("bare CR progress redraws", "[12:00:01 INFO]: Preparing 10%\r[12:00:01 INFO]: Preparing 50%\r\n"
                + PAPER_READY, true, 0, 0, "0 errors"));
        cases.add(new Case("colours, a window title and a cursor reset", "\u001b]0;Minecraft server\u0007\u001b[2K\r"
                + "\u001b[31;1m[12:00:05 ERROR]: Arena failed\u001b[m\njava.lang.IllegalStateException: boom\n" + FRAME
                + "\u001b[0m" + PAPER_READY, true, 1, 0, "ERROR Arena failed\njava.lang.IllegalStateException: boom"));
        cases.add(new Case("a stdout line in the middle of a stderr trace", "java.lang.IllegalStateException: boom\n" + FRAME
                + "[12:00:03 INFO]: Preparing spawn area: 50%\n" + FRAME + "Caused by: java.io.IOException: disk\n" + FRAME
                + "\t... 3 more\n" + PAPER_READY, true, 1, 0, "Caused by: java.io.IOException: disk"));
        cases.add(new Case("a warning with its own trace stays one warning", "[12:00:04 WARN]: Could not load skin\n"
                + "java.io.IOException: timeout\n" + FRAME + PAPER_READY, true, 0, 1, "java.io.IOException: timeout"));
        cases.add(new Case("an error's trace, then a bare one", "[12:00:05 ERROR]: Arena failed\n"
                + "java.lang.IllegalStateException: boom\n" + FRAME + "java.lang.Exception: later\n" + FRAME + PAPER_READY,
                true, 2, 0, "java.lang.Exception: later"));
        cases.add(new Case("a trace cut off after its header at exit", PAPER_READY
                + "java.lang.IllegalStateException: shutting down", true, 1, 0, "shutting down"));
        cases.add(new Case("a trace cut off in a frame at exit", "[12:00:01 INFO]: Loading\n"
                + "java.lang.NoClassDefFoundError: com/example/Gone\n\tat com.example.Mod.<init>(Mod.ja", false, 1, 0,
                "NoClassDefFoundError"));
        cases.add(new Case("a line of a megabyte", "[12:00:04 WARN]: " + "x".repeat(1 << 20) + "\n" + PAPER_READY,
                true, 0, 1, "more chars cut"));
        cases.add(new Case("a trace of 5000 frames", "java.lang.StackOverflowError\n"
                + "\tat com.example.Loop.again(Loop.java:1)\n".repeat(5000) + PAPER_READY, true, 1, 0,
                (5000 - LogReader.MAX_ENTRY_LINES) + " more lines cut"));
        String printed = String.join("\n", PLUGIN_PRINTED) + "\n";
        cases.add(new Case("a plugin's printed trace is one error with its framework frames folded (#336)",
                NAG_WARNING + "\n" + printed + PAPER_READY, true, 1, 1, "\t... 12 framework frames\nCaused by: "));
        cases.add(new Case("an untagged printed trace is one error too (#336)", String.join("\n", SERVER_PRINTED) + "\n"
                + PAPER_READY, true, 1, 0, "WARN java.lang.IllegalStateException: Arena 'spawn' has no world\n\tat"));
        cases.add(new Case("two plugins printing at once stay apart", "[12:00:05 WARN]: [A] [STDERR] java.lang.Exception: a\n"
                + "[12:00:05 WARN]: [B] [STDERR] java.lang.Exception: b\n[12:00:05 WARN]: [A] [STDERR] \tat a.A.run(A.java:1)\n"
                + "[12:00:05 WARN]: [B] [STDERR] \tat b.B.run(B.java:1)\n" + PAPER_READY, true, 2, 0, "[B] java.lang.Exception: b"));
        cases.add(new Case("a plugin's ordinary System.err line stays a warning",
                "[12:00:05 WARN]: [MyPlugin] [STDERR] could not reach the stats server\n" + PAPER_READY, true, 0, 1,
                "could not reach the stats server"));
        cases.add(new Case("an untagged header without frames is not an error",
                "[12:00:05 WARN]: java.io.IOException: timeout\n" + PAPER_READY, true, 0, 1, "timeout"));
        return cases;
    }

    @TestFactory
    Stream<DynamicTest> adversarialLogsAreClassified() {
        return adversarial().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> check(c)));
    }

    private static void check(Case c) throws IOException {
        List<String> failures = new ArrayList<>();
        for (int chunk : ChunkedInput.CHUNKS) {
            LogBuffer log = new LogBuffer(20_000, 4_000_000);
            boolean[] ready = {false};
            LogReader.pump(new ChunkedInput(c.text().getBytes(StandardCharsets.UTF_8), chunk), log, () -> ready[0] = true);
            LogCondenser.Condensed got = LogCondenser.analyze(log.all(), 1_000_000);
            String summary = "ready=" + ready[0] + " errors=" + got.errors() + " warnings=" + got.warnings()
                    + " contains=" + got.text().contains(c.contains()) + " controls=" + got.text().matches("(?s).*[\\r\\u001b].*");
            String want = "ready=" + c.ready() + " errors=" + c.errors() + " warnings=" + c.warnings() + " contains=true controls=false";
            if (!summary.equals(want)) {
                failures.add("chunk=" + chunk + ": " + summary);
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
}
