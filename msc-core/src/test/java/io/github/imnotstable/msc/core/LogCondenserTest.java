package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.LogCondenser;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogCondenserTest {
    @Test
    void keepsErrorsTrimsFrameworkFramesAndCollapsesRepeats() {
        List<String> log = List.of(
                "[12:00:00 INFO]: Starting minecraft server version 26.3",
                "[12:00:01 INFO]: Loading properties",
                "[12:00:02 WARN]: Legacy plugin Foo v1 does not specify an api-version.",
                "[12:00:03 ERROR]: Error occurred while enabling MyPlugin v1.0 (Is it up to date?)",
                "java.lang.NullPointerException: Cannot invoke \"org.bukkit.World.getName()\" because \"world\" is null",
                "\tat com.example.myplugin.Arena.load(Arena.java:42) ~[MyPlugin.jar:?]",
                "\tat com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:17) ~[MyPlugin.jar:?]",
                "\tat org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:288) ~[paper-api.jar:?]",
                "\tat io.papermc.paper.plugin.manager.PaperPluginInstanceManager.enablePlugin(PaperPluginInstanceManager.java:202) ~[paper.jar:?]",
                "\tat net.minecraft.server.MinecraftServer.run(MinecraftServer.java:1) ~[paper.jar:?]",
                "\tat java.base/java.lang.Thread.run(Thread.java:1583) ~[?:?]",
                "[12:00:04 INFO]: Done (3.2s)! For help, type \"help\"",
                "[12:00:05 WARN]: Can't keep up! Is the server overloaded?",
                "[12:00:06 WARN]: Can't keep up! Is the server overloaded?",
                "[12:00:07] [Server thread/WARN]: Can't keep up! Is the server overloaded?");
        assertThat(LogCondenser.condense(log, 6000)).isEqualTo("""
                15 lines, 1 errors, 4 warnings
                12:00:02 WARN Legacy plugin Foo v1 does not specify an api-version.
                12:00:03 ERROR Error occurred while enabling MyPlugin v1.0 (Is it up to date?)
                java.lang.NullPointerException: Cannot invoke "org.bukkit.World.getName()" because "world" is null
                \tat com.example.myplugin.Arena.load(Arena.java:42) ~[MyPlugin.jar:?]
                \tat com.example.myplugin.MyPlugin.onEnable(MyPlugin.java:17) ~[MyPlugin.jar:?]
                \t... 4 framework frames
                12:00:05 WARN Can't keep up! Is the server overloaded? (x3)""");
    }

    @Test
    void serverLibraryFramesAreFramework() {
        List<String> log = List.of(
                "[02:11:45 ERROR]: Could not read the registry",
                "com.sun.jna.platform.win32.Win32Exception: The parameter is incorrect.",
                "\tat com.sun.jna.platform.win32.Advapi32Util.registryGetStringArray(Advapi32Util.java:921) ~[jna-platform-5.17.0.jar:5.17.0 (b0)]",
                "\tat oshi.driver.windows.registry.HkeyPerformanceDataUtil.<clinit>(HkeyPerformanceDataUtil.java:51) ~[oshi-core-6.9.0.jar:6.9.0]",
                "\tat oshi.util.Memoizer$1.get(Memoizer.java:65) ~[oshi-core-6.9.0.jar:6.9.0]",
                "\tat net.minecraft.SystemReport.putProcessDetails(SystemReport.java:191) ~[paper-26.3.jar:26.3-248-0727243]");
        assertThat(LogCondenser.condense(log, 6000)).endsWith("""
                \tat com.sun.jna.platform.win32.Advapi32Util.registryGetStringArray(Advapi32Util.java:921) ~[jna-platform-5.17.0.jar:5.17.0 (b0)]
                \t... 3 framework frames""");
    }

    @Test
    void modLoaderFramesAreFramework() {
        List<String> log = List.of(
                "[12:00:03] [main/ERROR] [ne.ne.fm.ModLoader/LOADING]: Failed to create mod instance",
                "java.lang.IllegalStateException: boom",
                "\tat TRANSFORMER/mymod@1.0/com.example.mymod.MyMod.<init>(MyMod.java:12) ~[mymod-1.0.jar%23190!/:?]",
                "\tat net.neoforged.fml.javafmlmod.FMLModContainer.constructMod(FMLModContainer.java:110) ~[?:?]",
                "\tat MC-BOOTSTRAP/cpw.mods.modlauncher@11.0.4/cpw.mods.modlauncher.Launcher.run(Launcher.java:108) ~[?:?]",
                "\tat net.minecraftforge.eventbus.EventBus.post(EventBus.java:302) ~[?:?]",
                "\tat org.spongepowered.asm.mixin.transformer.MixinProcessor.applyMixins(MixinProcessor.java:363) ~[?:?]",
                "\tat net.fabricmc.loader.impl.launch.knot.Knot.launch(Knot.java:74) ~[?:?]",
                "\tat org.quiltmc.loader.impl.launch.knot.Knot.init(Knot.java:3) ~[?:?]",
                "\tat org.purpurmc.purpur.PurpurConfig.init(PurpurConfig.java:1) ~[?:?]");
        assertThat(LogCondenser.condense(log, 6000)).endsWith("""
                \tat TRANSFORMER/mymod@1.0/com.example.mymod.MyMod.<init>(MyMod.java:12) ~[mymod-1.0.jar%23190!/:?]
                \t... 7 framework frames""");
    }

    @Test
    void devServerBannersAndTheWindowsOshiErrorAreDropped() {
        List<String> log = List.of(
                "[01:06:43 WARN]: ****************************",
                "[01:06:43 WARN]: YOU ARE RUNNING THIS SERVER AS AN ADMINISTRATIVE OR ROOT USER. THIS IS NOT ADVISED.",
                "[01:06:43 WARN]: YOU ARE OPENING YOURSELF UP TO POTENTIAL RISKS WHEN DOING THIS.",
                "[01:06:43 WARN]: FOR MORE INFORMATION, SEE https://madelinemiller.dev/blog/root-minecraft-server/",
                "[01:06:43 WARN]: ****************************",
                "[01:06:44 WARN]: **** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!",
                "[01:06:44 WARN]: The server will make no attempt to authenticate usernames. Beware.",
                "[01:06:44 WARN]: While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose.",
                "[01:06:44 WARN]: To change this, set \"online-mode\" to \"true\" in the server.properties file.",
                // Paper logs this on every start on some Windows machines (system report via the bundled oshi library).
                "[02:11:45 ERROR]: [oshi.driver.windows.registry.HkeyPerformanceDataUtil] Unable to locate English counter names",
                "com.sun.jna.platform.win32.Win32Exception: The parameter is incorrect.",
                "\tat oshi.util.Memoizer$1.get(Memoizer.java:65) ~[oshi-core-6.9.0.jar:6.9.0]",
                "[02:11:45 WARN]: [oshi.util.platform.windows.PerfCounterQuery] Unable to locate English counter names in registry Perflib 009. Assuming English counters.",
                "[02:11:46 WARN]: Failed to get system info for Process Loads",
                "java.lang.NullPointerException: Cannot invoke \"java.util.List.stream()\" because \"loads\" is null",
                "\tat net.minecraft.SystemReport.lambda$putProcessDetails$0(SystemReport.java:190) ~[paper-26.3.jar:26.3-249]",
                "[02:11:46 WARN]: Failed to get system info for Virtual Size",
                "[02:11:46 WARN]: Failed to get system info for Resident Size",
                "[01:06:45 INFO]: Done (3.2s)! For help, type \"help\"");
        assertThat(LogCondenser.analyze(log, 6000).clean()).isTrue();
    }

    @Test
    void forgeStyleLinesDropTheLoggerAndTheSameNoiseIsDropped() {
        List<String> log = List.of(
                "[14:03:37] [main/WARN] [os.dr.wi.re.HkeyPerformanceDataUtil/]: Unable to locate English counter names",
                "[14:03:37] [Server thread/WARN] [oshi.util.platform.windows.PerfCounterQuery/]: Unable to locate counters",
                "[14:03:38] [Server thread/WARN] [minecraft/SystemReport]: Failed to get system info for Process Loads",
                "[14:03:39] [Server thread/WARN] [ne.mi.co.ForgeMod/CORE]: Something worth reading",
                "[14:03:40] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.1s)! For help, type \"help\"");
        assertThat(LogCondenser.condense(log, 6000))
                .isEqualTo("5 lines, 0 errors, 1 warnings, 3 known-harmless hidden\n14:03:39 WARN Something worth reading");
    }

    @Test
    void windowsLoaderStartupNoiseIsDroppedAndCounted() {
        List<String> log = List.of(
                // NeoForge and Forge on Windows: netty probes the macOS and Linux native transports.
                "[14:03:30] [main/ERROR] [io.ne.ch.kq.KQueue/]: KQueue support is not available",
                "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.kqueue.Native",
                "\tat io.netty.channel.kqueue.KQueue.<clinit>(KQueue.java:39) ~[netty-transport-classes-kqueue.jar:?]",
                "Caused by: java.lang.ExceptionInInitializerError: Exception java.lang.IllegalStateException: Only supported on OSX/BSD [in thread \"main\"]",
                "\tat io.netty.channel.kqueue.Native.<clinit>(Native.java:62) ~[netty-transport-classes-kqueue.jar:?]",
                "[14:03:30] [main/DEBUG] [io.ne.ch.ep.Epoll/]: Epoll support is not available",
                "java.lang.NoClassDefFoundError: Could not initialize class io.netty.channel.epoll.Native",
                "\tat io.netty.channel.epoll.Epoll.<clinit>(Epoll.java:40) ~[netty-transport-classes-epoll.jar:?]",
                "Caused by: java.lang.IllegalStateException: Only supported on Linux",
                // Forge's first start writes its config files.
                "[14:03:31] [main/WARN] [ne.mi.fm.co.ConfigFileTypeHandler/CONFIG]: Configuration file C:\\srv\\config\\fml.toml is not correct. Correcting",
                "[14:03:31] [main/WARN] [ne.mi.fm.co.ConfigSpec/CORE]: Incorrect key earlyWindowControl was corrected from null to its default, true. ",
                // Fabric and Quilt print oshi's message without its logger.
                "[14:03:34] [Server thread/ERROR]: Unable to locate English counter names in registry Perflib 009",
                "com.sun.jna.platform.win32.Win32Exception: The parameter is incorrect.",
                "\tat com.sun.jna.platform.win32.Advapi32Util.registryGetValue(Advapi32Util.java:1104) ~[jna-platform.jar:?]",
                "[14:03:34] [Server thread/WARN]: Unable to locate English counter names in registry Perflib 009. Assuming English counters.",
                "[14:03:35] [Server thread/WARN] [ne.mi.co.ConfigSpec/CORE]: Incorrect key arena.size was corrected from 500 to its default, 64.",
                "[14:03:40] [Server thread/INFO] [minecraft/DedicatedServer]: Done (3.1s)! For help, type \"help\"");
        LogCondenser.Condensed c = LogCondenser.analyze(log, 6000);
        assertThat(c.text()).isEqualTo("17 lines, 0 errors, 1 warnings, 6 known-harmless hidden\n"
                + "14:03:35 WARN Incorrect key arena.size was corrected from 500 to its default, 64.");
        assertThat(c.hidden()).isEqualTo(6);
    }

    /** The forge-mod template and the agent ship a pack.mcmeta, so a mod jar without one is the user's (#254). */
    @Test
    void aForgeModWithoutPackMetadataIsKept() {
        List<String> log = List.of(
                "[14:03:33] [Worker-Main-2/WARN] [minecraft/Pack]: Missing metadata in pack mod:mymod",
                "[14:03:33] [Worker-Main-2/WARN] [ne.mi.re.ResourcePackLoader/CORE]: Could not find resource pack for mod file mymod.jar",
                "[14:03:34] [Server thread/WARN] [minecraft/MinecraftServer]: Missing data pack mod:mymod");
        assertThat(LogCondenser.analyze(log, 6000).warnings()).isEqualTo(3);
    }

    @Test
    void quiltsMissingIntermediaryOn26IsDroppedWithItsTimings() {
        List<String> log = List.of(
                "[10:01:02] [main/WARN]: Not remapping mods because intermediary is missing!",
                "transform-cache took 2460ms",
                "zip sub copy took 9ms",
                "[10:01:03] [main/WARN]: Not remapping mods because mappings are empty");
        assertThat(LogCondenser.condense(log, 6000)).isEqualTo("4 lines, 0 errors, 1 warnings, 1 known-harmless hidden\n"
                + "10:01:03 WARN Not remapping mods because mappings are empty");
    }

    @Test
    void quiltsTimingLinesAreNotShownAsAnotherWarningsContext() {
        List<String> log = List.of(
                "[10:01:02] [main/WARN]: Mod mymod uses a deprecated entrypoint",
                "mod adding took 12ms",
                "tmp jar copy took 3ms",
                "transform-cache took 2460ms",
                "zip sub copy took 9ms");
        assertThat(LogCondenser.condense(log, 6000))
                .isEqualTo("5 lines, 0 errors, 1 warnings\n10:01:02 WARN Mod mymod uses a deprecated entrypoint");
    }

    @Test
    void quiltsInMemoryAssetsUrlIsDroppedButAnotherSchemaIsKept() {
        List<String> log = List.of(
                "[10:01:04] [main/WARN]: Assets URL 'quilt.zfs://minecraft-26.3.jar/assets/.mcassetsroot' uses unexpected schema",
                "[10:01:04] [main/WARN]: Assets URL 'http://example.com/assets/.mcassetsroot' uses unexpected schema");
        assertThat(LogCondenser.condense(log, 6000)).isEqualTo("2 lines, 0 errors, 1 warnings, 1 known-harmless hidden\n"
                + "10:01:04 WARN Assets URL 'http://example.com/assets/.mcassetsroot' uses unexpected schema");
    }

    @Test
    void aNettyErrorThatIsNotTheNativeProbeIsKept() {
        List<String> log = List.of(
                "[14:03:30] [Server thread/ERROR] [io.ne.ch.DefaultChannelPipeline/]: An exceptionCaught() event was fired",
                "java.lang.IllegalStateException: boom",
                "\tat io.netty.channel.epoll.EpollEventLoop.run(EpollEventLoop.java:1) ~[netty.jar:?]");
        assertThat(LogCondenser.analyze(log, 6000).errors()).isEqualTo(1);
    }

    @Test
    void forgesTransformerAuditIsCutFromTraces() {
        List<String> log = List.of(
                "[14:03:39] [Server thread/ERROR] [minecraft/MinecraftServer]: Encountered an unexpected exception",
                "java.lang.IllegalStateException: boom",
                "\tat com.example.mymod.Arena.start(Arena.java:12) ~[mymod.jar%23171!/:?] {re:classloading}",
                "Transformer Audit:",
                "  com.example.mymod.Arena",
                "    REASON: classloading",
                "  net.minecraft.server.MinecraftServer",
                "    REASON: classloading",
                "    PLUGIN: accesstransformer:BEFORE");
        assertThat(LogCondenser.condense(log, 6000)).doesNotContain("Transformer Audit", "REASON")
                .contains("IllegalStateException: boom", "Arena.start(Arena.java:12)");
    }

    @Test
    void buildOutputKeepsFailedTestsAndDropsJdkWarnings() {
        String output = """
                Starting a Gradle Daemon, 2 incompatible Daemons could not be reused, use --status for details
                > Task :compileJava
                > Task :test
                WARNING: A terminally deprecated method in sun.misc.Unsafe has been called
                WARNING: sun.misc.Unsafe::objectFieldOffset has been called by net.bytebuddy.Foo
                WARNING: Please consider reporting this to the maintainers of class net.bytebuddy.Foo

                LobbyPluginTest > greetsPlayersWhenTheyJoin() FAILED
                    java.util.NoSuchElementException
                        at java.base/java.util.ArrayList.getFirst(ArrayList.java:440)
                        at com.example.lobby.LobbyPlugin.onEnable(LobbyPlugin.java:19)
                        at org.bukkit.plugin.java.JavaPlugin.setEnabled(JavaPlugin.java:280)

                4 tests completed, 1 failed

                > Task :test FAILED
                """;
        assertThat(LogCondenser.condenseBuild(output, 6000)).isEqualTo("""
                LobbyPluginTest > greetsPlayersWhenTheyJoin() FAILED
                    java.util.NoSuchElementException
                        at com.example.lobby.LobbyPlugin.onEnable(LobbyPlugin.java:19)
                4 tests completed, 1 failed
                > Task :test FAILED""");
    }

    @Test
    void cleanLogIsOneLine() {
        LogCondenser.Condensed c = LogCondenser.analyze(List.of("[12:00:00 INFO]: Done (1.0s)!"), 6000);
        assertThat(c.clean()).isTrue();
        assertThat(c.text()).isEqualTo("1 lines, 0 errors, 0 warnings");
    }

    @Test
    void buildOutputKeepsCompilerErrorsAndDropsGradleAdvice() {
        String output = """
                > Task :compileJava FAILED
                /work/src/main/java/com/example/Foo.java:12: error: cannot find symbol
                        player.sendMesage("hi");
                              ^
                  symbol:   method sendMesage(String)
                  location: variable player of type Player
                1 error

                FAILURE: Build failed with an exception.

                * What went wrong:
                Execution failed for task ':compileJava'.
                > Compilation failed; see the compiler output below.
                  /work/src/main/java/com/example/Foo.java:12: error: cannot find symbol
                          player.sendMesage("hi");
                                ^
                    symbol:   method sendMesage(String)
                    location: variable player of type Player
                  1 error

                * Try:
                > Run with --info option to get more log output.
                > Get more help at https://help.gradle.org.

                BUILD FAILED in 2s
                1 actionable task: 1 executed
                """;
        assertThat(LogCondenser.condenseBuild(output, 6000)).isEqualTo("""
                > Task :compileJava FAILED
                /work/src/main/java/com/example/Foo.java:12: error: cannot find symbol
                        player.sendMesage("hi");
                              ^
                  symbol:   method sendMesage(String)
                  location: variable player of type Player
                1 error
                * What went wrong:
                Execution failed for task ':compileJava'.
                BUILD FAILED in 2s""");
    }
}
