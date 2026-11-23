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

    /** Real logs from omen (ADR 0089): four broken Paper plugins, as the start report and mode=triage see them. */
    @Test
    void realPaperPluginFailuresAreEachNamed() {
        assertThat(triage("paper-plugins")).isEqualTo("""
                missing dependency Vault of plugin Needy [log 08:21:05]
                  fix: install it into plugins/ or mods/, or make the dependency optional
                plugin jar Future.jar failed to load [log 08:21:05]
                  cause: org.bukkit.plugin.InvalidPluginException: Unsupported API version 99.0
                  at org.bukkit.craftbukkit.util.CraftMagicNumbers.checkSupported(CraftMagicNumbers.java:378)
                  fix: api-version 99.0 in plugin.yml is newer than this server: set the server's Minecraft version \
                there, or set the server up with that version
                plugin Linky failed to enable [log 08:21:05]
                  culprit: com.example.linky.Linky.onEnable
                  cause: java.lang.NoSuchMethodError: 'java.lang.String com.example.lib.Lib.greet(java.lang.String)'
                  at com.example.linky.Linky.onEnable(Linky.java:4)
                  fix: compiled against another version than the server runs: match the API or dependency version to \
                the server
                plugin Boom failed to enable [log 08:21:05]
                  culprit: com.example.boom.Boom.onEnable
                  cause: java.lang.NullPointerException: Cannot invoke "String.trim()" because "mode" is null
                  at com.example.boom.Boom.onEnable(Boom.java:9)
                  fix: fix the code at Boom.java:9 (com.example.boom.Boom.onEnable)""");
    }

    /**
     * ADR 0089: with the project's source beside the server folder and the plugins in it, a project frame is its file
     * and line with the code, another plugin's class is named by its jar, and a missing method by the plugin that lacks it.
     */
    @Test
    void framesLinkToTheProjectAndClassesToTheirJars() throws IOException {
        Path run = Files.createDirectories(dir.resolve("ws/run/plugins")).getParent();
        Path src = Files.createDirectories(dir.resolve("ws/boom/src/main/java/com/example/boom"));
        Files.writeString(src.resolve("Boom.java"), "package com.example.boom;\n\n".repeat(4)
                + "        start(mode.trim());\n");
        jar(run.resolve("plugins/Lib.jar"), "name: Lib\nversion: 2.0\nmain: com.example.lib.Lib\n", "com/example/lib/Lib.class");
        jar(run.resolve("plugins/Linky.jar"), "name: Linky\nversion: 1.0\nmain: com.example.linky.Linky\n",
                "com/example/linky/Linky.class");
        String out = CrashTriage.triage(lines("crash-logs/paper-plugins.txt"), run, null, Platform.PAPER).render(null);
        assertThat(out).contains("""
                  culprit: com.example.linky.Linky.onEnable in plugin Linky 1.0 (plugins/Linky.jar)
                  cause: java.lang.NoSuchMethodError: 'java.lang.String com.example.lib.Lib.greet(java.lang.String)'
                  at com.example.linky.Linky.onEnable(Linky.java:4)
                  fix: the installed plugin Lib 2.0 (plugins/Lib.jar) has no such member of com.example.lib.Lib: the \
                caller was built against another version of it; install that version or rebuild against this one""")
                .contains("\n  culprit: com.example.boom.Boom.onEnable\n  cause: java.lang.NullPointerException: Cannot "
                        + "invoke \"String.trim()\" because \"mode\" is null\n  at com.example.boom.Boom.onEnable -> "
                        + "boom/src/main/java/com/example/boom/Boom.java:9: start(mode.trim());\n  fix: fix the code at "
                        + "boom/src/main/java/com/example/boom/Boom.java:9");
        CrashSources sources = new CrashSources(run, Platform.PAPER);
        assertThat(sources.linkage("java.lang.NoClassDefFoundError: com/example/gone/Thing", "x")).isEqualTo(
                "no installed plugin or mod has com.example.gone.Thing: install the one that provides it, or shade the "
                        + "library into the jar");
        assertThat(sources.linkage("java.lang.NoClassDefFoundError: com/example/lib/Lib", "x")).startsWith(
                "plugin Lib 2.0 (plugins/Lib.jar) has com.example.lib.Lib but the caller's class loader cannot see it");
        assertThat(sources.linkage("java.lang.NoSuchMethodError: 'void org.bukkit.entity.Player.fly()'", "x"))
                .startsWith("built against another Paper API than the server runs (org.bukkit.entity.Player lacks it)");
    }

    /** Real Velocity and Waterfall logs from omen (ADR 0089): a missing dependency and a plugin that throws. */
    @Test
    void proxyPluginFailuresAreNamed() {
        assertThat(triage("velocity-plugins")).isEqualTo("""
                missing dependency luckperms of plugin needv [log 08:21:08]
                  fix: install it into plugins/ or mods/, or make the dependency optional
                plugin boomv failed in its ProxyInitializeEvent listener [log 08:21:09]
                  culprit: com.example.boomv.BoomV.onInit
                  cause: java.lang.NullPointerException: Cannot invoke "Object.toString()" because "o" is null
                  at com.example.boomv.BoomV.onInit(BoomV.java:8)
                  fix: fix the code at BoomV.java:8 (com.example.boomv.BoomV.onInit)""");
        assertThat(triage("waterfall-plugins")).isEqualTo("""
                missing dependency LuckPerms of plugin NeedW [log 08:21:16]
                  fix: install it into plugins/ or mods/, or make the dependency optional
                plugin BoomW failed to load [log 08:21:16]
                  culprit: com.example.boomw.BoomW.onEnable
                  cause: java.lang.IllegalStateException: no config
                  at com.example.boomw.BoomW.onEnable(BoomW.java:4)
                  fix: fix the code at BoomW.java:4 (com.example.boomw.BoomW.onEnable)""");
    }

    private static void jar(Path file, String pluginYml, String cls) throws IOException {
        try (java.util.zip.ZipOutputStream out = new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            out.putNextEntry(new java.util.zip.ZipEntry("plugin.yml"));
            out.write(pluginYml.getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new java.util.zip.ZipEntry(cls));
            out.write(new byte[] {(byte) 0xCA, (byte) 0xFE});
        }
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
        // Different failures are never a repeat (#567): two fatal exceptions in server code stay two findings...
        assertThat(triage("paper-two-fatal")).doesNotContain("(x2)")
                .contains("fatal exception in server code [log 12:00:01]\n  culprit: server or loader code\n"
                        + "  cause: java.lang.IllegalStateException: world folder locked")
                .contains("fatal exception in server code [log 12:00:02]\n  culprit: server or loader code\n"
                        + "  cause: java.lang.NullPointerException: Cannot invoke \"Level.save()\"");
        // ...and two plugins failing with the same exception in different seconds are both named.
        assertThat(triage("paper-two-plugins")).doesNotContain("(x2)")
                .contains("plugin Shops failed to enable [log 12:00:02]\n  culprit: com.example.shops.Shops.onEnable")
                .contains("plugin Jobs failed to enable [log 12:00:03]\n  culprit: com.example.jobs.Jobs.onEnable");
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

    /**
     * #755: when one of MSC's own pack hosts holds the server's port, the fix names it, not a leftover. It is asked
     * with the port from the log, else the one server.properties sets, else 25565 (Minestom has no file).
     */
    @Test
    void aPortHeldByAnMscPackHostNamesThatHost() throws IOException {
        List<Integer> asked = new java.util.ArrayList<>();
        java.util.function.IntFunction<String> holder = port -> {
            asked.add(port);
            return "this session's resource pack host held port " + port;
        };
        assertThat(CrashTriage.triage(lines("crash-logs/paper-port.txt"), dir, null, Platform.PAPER, holder)
                .render(null)).startsWith("server port 25570 already in use [log 12:00:01]\n  fix: this session's "
                + "resource pack host held port 25570");
        assertThat(CrashTriage.triage(lines("crash-logs/minestom-uncaught.txt"), dir, null, Platform.MINESTOM, holder)
                .render(null)).endsWith("fix: this session's resource pack host held port 25565");
        Files.writeString(dir.resolve("server.properties"), "server-port=25990\n");
        CrashTriage.triage(lines("crash-logs/minestom-uncaught.txt"), dir, null, Platform.MINESTOM, holder);
        assertThat(asked).containsExactly(25570, 25565, 25990);
        // No MSC host there: the fix is as before.
        assertThat(CrashTriage.triage(lines("crash-logs/paper-port.txt"), dir, null, Platform.PAPER, port -> null)
                .render(null)).contains("fix: another process holds the port");
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
                "[12:00:05 ERROR]: Error occurred while enabling C v1",
                "[12:00:06 ERROR]: Error occurred while enabling D v1",
                "[12:00:07 ERROR]: Error occurred while enabling E v1");
        assertThat(CrashTriage.triage(lines, dir, null, Platform.PAPER).render(null))
                .endsWith("\n+2 more: server_logs mode=errors").contains("plugin C failed").doesNotContain("plugin D");
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
