package io.github.imnotstable.msc.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.plugin.MscLauncher.Install;
import io.github.imnotstable.msc.plugin.MscLauncher.LaunchException;
import io.github.imnotstable.msc.plugin.MscLauncher.Plan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MscLauncherTest {
    @TempDir
    Path home;

    @Test
    void newestIsTheLastInstalled() throws IOException {
        install(home.resolve("msc-core-0.3.0-SNAPSHOT-20261112-080000"), "0.3.0-SNAPSHOT", 2_000L);
        install(home.resolve("msc-core-0.2.0"), "0.2.0", 1_000L);
        Files.createDirectories(home.resolve("unrelated/lib"));

        Install newest = MscLauncher.newest(home, 5_000L).orElseThrow();

        assertThat(newest.version()).isEqualTo("0.3.0-SNAPSHOT");
        assertThat(newest.installed()).isEqualTo(2_000L);
    }

    @Test
    void anUnzippedReleaseCountsFromWhenTheLauncherFirstSeesIt() throws IOException {
        install(home.resolve("msc-core-0.3.0-SNAPSHOT-a"), "0.3.0-SNAPSHOT", 2_000L);
        Path release = home.resolve("msc-core-0.4.0");
        Files.createDirectories(release.resolve("lib"));
        Files.createFile(release.resolve("lib/msc-core-0.4.0.jar"));

        assertThat(MscLauncher.newest(home, 9_000L).orElseThrow().dir()).isEqualTo(release);
        // Recorded, so a later install still wins over it.
        install(home.resolve("msc-core-0.3.0-SNAPSHOT-b"), "0.3.0-SNAPSHOT", 10_000L);
        assertThat(MscLauncher.newest(home, 20_000L).orElseThrow().dir().getFileName().toString())
                .isEqualTo("msc-core-0.3.0-SNAPSHOT-b");
        assertThat(props(release).getProperty("installed")).isEqualTo("9000");
    }

    @Test
    void theOldFlatInstallStillCounts() throws IOException {
        install(home, "0.1.0-SNAPSHOT", 3_000L);
        install(home.resolve("msc-core-0.1.0-SNAPSHOT-x"), "0.1.0-SNAPSHOT", 1_000L);

        assertThat(MscLauncher.newest(home, 5_000L).orElseThrow().dir()).isEqualTo(home);
    }

    @Test
    void noInstallSaysHowToMakeOne() {
        assertThatThrownBy(() -> MscLauncher.plan(home, Map.of(), home, 25, 0L))
                .isInstanceOf(LaunchException.class)
                .hasMessageContaining("installMsc")
                .hasMessageContaining("/mcp");
    }

    @Test
    void homeComesFromTheEnvironment() {
        assertThat(MscLauncher.home(Map.of(), "/u")).isEqualTo(Path.of("/u", ".msc", "core"));
        assertThat(MscLauncher.home(Map.of(MscLauncher.ENV_HOME, "/x"), "/u")).isEqualTo(Path.of("/x"));
    }

    @Test
    void javaPrefersTheOverrideThenTheInstallsJavaThenThisJvm() throws Exception {
        Path jdk25 = jdk("jdk25", "25.0.1");
        Path jdk21 = jdk("jdk21", "21.0.4");
        Path dir = home.resolve("msc-core-1");
        install(dir, "1", 1L);
        Install install = MscLauncher.newest(home, 0L).orElseThrow();

        assertThat(MscLauncher.java(install, Map.of(MscLauncher.ENV_JAVA, "/my/java"), jdk21, 21)).isEqualTo("/my/java");
        // No recorded java: run in this JVM when it is new enough, else JAVA_HOME.
        assertThat(MscLauncher.java(install, Map.of(), jdk25, 25)).isNull();
        assertThat(MscLauncher.java(install, Map.of("JAVA_HOME", jdk25.toString()), jdk21, 21))
                .isEqualTo(MscLauncher.executable(jdk25).toString());

        // The install's own java: a child when this JVM is another one, this JVM when it is the same.
        Properties p = props(dir);
        p.setProperty("java", MscLauncher.executable(jdk25).toString());
        store(dir, p);
        assertThat(MscLauncher.java(install, Map.of(), jdk21, 21)).isEqualTo(MscLauncher.executable(jdk25).toString());
        assertThat(MscLauncher.java(install, Map.of(), jdk25, 25)).isNull();
    }

    @Test
    void tooOldJavaIsOneClearLine() throws Exception {
        Path jdk21 = jdk("jdk21", "21.0.4");
        install(home.resolve("msc-core-1"), "1", 1L);
        Install install = MscLauncher.newest(home, 0L).orElseThrow();

        assertThatThrownBy(() -> MscLauncher.java(install, Map.of("JAVA_HOME", jdk21.toString()), jdk21, 21))
                .hasMessageContaining("Java 25")
                .hasMessageContaining(MscLauncher.ENV_JAVA);
    }

    @Test
    void featureReadsTheReleaseFile() throws IOException {
        assertThat(MscLauncher.feature(jdk("a", "25.0.1"))).isEqualTo(25);
        assertThat(MscLauncher.feature(jdk("b", "1.8.0_402"))).isEqualTo(8);
        assertThat(MscLauncher.feature(home.resolve("missing"))).isZero();
    }

    @Test
    void childCommandUsesTheLibFolder() {
        Install install = new Install(Path.of("/i"), "1", 1L);
        List<String> cmd = MscLauncher.command(new Plan(install, "/j/java"), new String[0]);

        assertThat(cmd).containsExactly("/j/java", "-cp", Path.of("/i", "lib") + java.io.File.separator + "*",
                MscLauncher.MAIN_CLASS);
    }

    private Path jdk(String name, String version) throws IOException {
        Path dir = Files.createDirectories(home.resolve("jdks").resolve(name));
        Files.createDirectories(dir.resolve("bin"));
        Files.createFile(MscLauncher.executable(dir));
        Files.writeString(dir.resolve("release"), "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"" + version + "\"\n");
        return dir;
    }

    private static void install(Path dir, String version, long installed) throws IOException {
        Files.createDirectories(dir.resolve("lib"));
        Files.createFile(dir.resolve("lib/msc-core-" + version + ".jar"));
        Properties p = new Properties();
        p.setProperty("installed", Long.toString(installed));
        store(dir, p);
    }

    private static Properties props(Path dir) throws IOException {
        Properties p = new Properties();
        try (var in = Files.newInputStream(dir.resolve(MscLauncher.INSTALL_PROPERTIES))) {
            p.load(in);
        }
        return p;
    }

    private static void store(Path dir, Properties p) throws IOException {
        try (var out = Files.newOutputStream(dir.resolve(MscLauncher.INSTALL_PROPERTIES))) {
            p.store(out, null);
        }
    }
}
