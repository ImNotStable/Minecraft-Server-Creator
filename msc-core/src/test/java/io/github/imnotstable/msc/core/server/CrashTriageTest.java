package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Crash and start-up failure logs of every platform (in {@code crash-logs/}) triaged into a few lines. */
class CrashTriageTest {
    @TempDir
    Path dir;

    @Test
    void paperPluginThatFailsToEnableBlamesItsOwnFrame() {
        assertThat(triage("paper-enable")).isEqualTo("""
                plugin MyPlugin failed to enable [log 12:00:02]
                  culprit: com.example.myplugin.Arena.load
                  cause: java.lang.IllegalStateException: Arena 'spawn' has no world
                  at com.example.myplugin.Arena.load(Arena.java:42)
                  at com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:17)
                  fix: fix the code at Arena.java:42 (com.example.myplugin.Arena.load)""");
    }

    @Test
    void knownCausesAreOneLineAndAFix() {
        assertThat(triage("paper-dependency")).isEqualTo("""
                missing dependency Vault of plugin MyPlugin [log 12:00:01]
                  fix: install it into plugins/ or mods/, or make the dependency optional""");
        assertThat(triage("paper-port")).startsWith("server port 25570 already in use [log 12:00:01]\n  fix: another");
        assertThat(triage("purpur-java")).startsWith("wrong Java: com.example.myplugin.MyPlugin needs Java 25, the "
                + "server runs Java 21 [log 12:00:01]\n  fix: set MSC_JAVA to a Java 25+ install, or build the project "
                + "for Java 21");
        assertThat(triage("paper-eula")).startsWith("EULA not accepted [log 12:00:01]\n  fix: ask the user");
        assertThat(triage("paper-watchdog")).startsWith("server hung: the watchdog");
    }

    @Test
    void repeatsAreCountedNotRepeated() {
        assertThat(triage("paper-oom")).isEqualTo("""
                out of memory (x2) [log 12:05:41]
                  culprit: com.example.myplugin.ChunkCache.put
                  cause: java.lang.OutOfMemoryError: Java heap space
                  at com.example.myplugin.ChunkCache.put(ChunkCache.java:30)
                  fix: the dev server has -Xmx2G (Minestom 1G): find what keeps growing (caches, loaded chunks, \
                entities, a leak) or load less at once""");
        // Minestom's lines carry no time: its repeats count the same (#561).
        assertThat(triage("minestom-oom")).startsWith("out of memory (x2) [log]\n");
    }

    @Test
    void fabricAndQuiltLoaderFailures() {
        assertThat(triage("fabric-dependency")).isEqualTo("""
                missing dependency fabric-api of mod mymod [log 12:00:00]
                  fix: install it into plugins/ or mods/, or make the dependency optional
                missing dependency geckolib of mod mymod [log 12:00:00]
                  fix: install it into plugins/ or mods/, or make the dependency optional""");
        assertThat(triage("fabric-entrypoint")).isEqualTo("""
                mod mymod failed in its main entrypoint [log 12:00:02]
                  culprit: com.example.mymod.config.Config.load
                  cause: java.lang.ClassNotFoundException: com.google.gson2.Gson
                  at com.example.mymod.config.Config.load(Config.java:31)
                  at com.example.mymod.MyMod.onInitialize(MyMod.java:20)
                  fix: a class is missing at run time: shade the library into the jar, or install the plugin or mod \
                that provides it""");
        assertThat(triage("quilt-mixin")).startsWith("mixin of mod mymod failed to apply [log 12:00:03]\n"
                        + "  cause: org.spongepowered.asm.mixin.injection.throwables.InvalidInjectionException")
                .endsWith("  fix: the mixin does not fit this Minecraft version: check its target class, method and "
                        + "descriptor");
    }

    @Test
    void forgeAndNeoForgeLoaderFailures() throws IOException {
        assertThat(triage("forge-dependency")).isEqualTo("""
                missing dependency jei of mod mymod [log 12:00:02]
                  fix: install it into plugins/ or mods/, or make the dependency optional
                mod mymod needs minecraft [1.21,1.22), found 26.3 [log 12:00:02]
                  fix: use a version in range, or change the range in the mod's mods.toml""");
        copy("neoforge-construct-crash", "crash-reports/crash-2026-11-14_12.00.04-fml.txt");
        assertThat(triage("neoforge-construct")).isEqualTo("""
                mod mymod failed to construct [log 12:00:04, crash-reports/crash-2026-11-14_12.00.04-fml.txt]
                  culprit: com.example.mymod.ModItems.<clinit>
                  cause: java.lang.IllegalStateException: Registry is already frozen
                  at com.example.mymod.ModItems.<clinit>(ModItems.java:14)
                  at com.example.mymod.MyMod.<init>(MyMod.java:22)
                  fix: fix the code at ModItems.java:14 (com.example.mymod.ModItems.<clinit>)""");
    }

    @Test
    void aCrashReportAndTheLogSayingTheSameAreOneFinding() throws IOException {
        copy("fabric-tick-crash", "crash-reports/crash-2026-11-14_12.02.10-server.txt");
        assertThat(triage("fabric-tick")).isEqualTo("""
                crash report: Ticking entity [crash-reports/crash-2026-11-14_12.02.10-server.txt, log 12:02:10]
                  culprit: com.example.mymod.entity.Guard.tick
                  cause: java.lang.NullPointerException: Cannot invoke "com.example.mymod.Arena.owner()" because \
                "arena" is null
                  at com.example.mymod.entity.Guard.tick(Guard.java:57)
                  fix: fix the code at Guard.java:57 (com.example.mymod.entity.Guard.tick)""");
    }

    @Test
    void minestomPrintsItsExceptionsBare() {
        assertThat(triage("minestom-uncaught")).isEqualTo("""
                server port already in use [log]
                  culprit: com.example.lobby.Main.main
                  cause: java.net.BindException: Address already in use
                  at com.example.lobby.Main.main(Main.java:21)
                  fix: another process holds the port: server_stop stops a leftover server, else change the port in \
                the main class's server.start(...)""");
        assertThat(triage("minestom-listener")).isEqualTo("""
                last logged exception (not fatal) in plugin or mod code [log]
                  culprit: com.example.lobby.Lobby.spread
                  cause: java.lang.StackOverflowError
                  at com.example.lobby.Lobby.spread(Lobby.java:77)
                  at com.example.lobby.Lobby.spread(Lobby.java:78)
                  fix: endless recursion around Lobby.java:77 (com.example.lobby.Lobby.spread)""");
    }

    @Test
    void aJvmCrashNamesItsFrame() throws IOException {
        Path file = copy("hs_err", "hs_err_pid41234.log");
        assertThat(CrashTriage.triage(List.of("[12:00:00 INFO]: Done (1.0s)!"), dir, null, Platform.PAPER).render(null))
                .startsWith("JVM crash (SIGSEGV) [hs_err_pid41234.log (")
                .contains("\n  culprit: [libnoise.so+0x1c3d]  fbm_sample+0x2d\n  fix: a native library crashed");
        // Older than the start being triaged: not this crash.
        Files.setLastModifiedTime(file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z")));
        assertThat(CrashTriage.triage(List.of(), dir, Instant.parse("2026-02-01T00:00:00Z"), Platform.PAPER).findings())
                .isEmpty();
    }

    @Test
    void withoutLinesItReadsLatestLog() throws IOException {
        copy("paper-eula", "logs/latest.log");
        assertThat(CrashTriage.triage(List.of(), dir, null, Platform.PAPER).render(null))
                .startsWith("EULA not accepted [logs/latest.log 12:00:01]");
        // A clean one: the all-clear counts the lines it read there, not the empty buffer's (#559).
        Files.writeString(dir.resolve("logs/latest.log"), "[12:00:00 INFO]: Starting minecraft server version 26.3\n"
                + "[12:00:03 INFO]: Done (3.1s)! For help, type \"help\"\n");
        assertThat(CrashTriage.triage(List.of(), dir, null, Platform.PAPER).render("server not running"))
                .isEqualTo("no crash or startup failure found in 2 lines of logs/latest.log and no new crash report; "
                        + "server not running");
    }

    @Test
    void aPortThePluginItselfBindsIsItsOwnProblemNotTheServerPort() {
        // #560: the game port is fine; the plugin's web server's port is taken.
        assertThat(triage("paper-plugin-socket")).isEqualTo("""
                last logged exception (not fatal) in plugin or mod code [log 12:00:05]
                  culprit: com.example.web.WebServer.start
                  cause: java.net.BindException: Address already in use
                  at com.example.web.WebServer.start(WebServer.java:20)
                  at com.example.web.WebPlugin.onEnable(WebPlugin.java:12)
                  fix: the port this code binds is taken: pick another port, or stop what holds it""");
        assertThat(triage("paper-port")).endsWith("else change server-port in server.properties");
    }

    @Test
    void cleanStartupLogsHaveNothingToTriage() {
        for (String platform : List.of("paper", "purpur", "fabric", "quilt", "neoforge", "forge", "minestom")) {
            List<String> lines = lines("startup-logs/" + platform + ".txt");
            assertThat(CrashTriage.triage(lines, dir, null, Platform.of(platform)).render("server running"))
                    .as(platform).isEqualTo("no crash or startup failure found in " + lines.size()
                            + " log lines and no new crash report; server running");
        }
    }

    @Test
    void manyFindingsAreCappedAndCounted() {
        List<String> lines = List.of(
                "[12:00:01 INFO]: You need to agree to the EULA in order to run the server.",
                "[12:00:02 WARN]: **** FAILED TO BIND TO PORT!",
                "[12:00:03 ERROR]: Error occurred while enabling A v1",
                "[12:00:04 ERROR]: Error occurred while enabling B v1",
                "[12:00:05 ERROR]: Error occurred while enabling C v1");
        assertThat(CrashTriage.triage(lines, dir, null, Platform.PAPER).render(null))
                .endsWith("\n+2 more: server_logs mode=errors").contains("plugin A failed").doesNotContain("plugin B");
    }

    private String triage(String fixture) {
        List<String> lines = lines("crash-logs/" + fixture + ".txt");
        Platform platform = Platform.of(fixture.substring(0, fixture.indexOf('-')));
        return CrashTriage.triage(lines, dir, null, platform).render(null);
    }

    private Path copy(String fixture, String to) throws IOException {
        Path target = dir.resolve(to);
        Files.createDirectories(target.getParent());
        Files.write(target, String.join("\n", lines("crash-logs/" + fixture + ".txt")).getBytes(StandardCharsets.UTF_8));
        return target;
    }

    private static List<String> lines(String resource) {
        try (InputStream in = Objects.requireNonNull(CrashTriageTest.class.getResourceAsStream("/" + resource))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
