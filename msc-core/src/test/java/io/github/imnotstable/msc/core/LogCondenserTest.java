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
        // Paper logs this on every start on some Windows machines (system report via the bundled oshi library).
        List<String> log = List.of(
                "[02:11:45 ERROR]: [oshi.driver.windows.registry.HkeyPerformanceDataUtil] Unable to locate English counter names",
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
