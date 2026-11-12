package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {
    @TempDir
    Path dir;

    @Test
    void installDirIsTheFolderAboveLibWhenRunFromADistribution() throws Exception {
        assertThat(Main.installDir(source(dir.resolve("msc/lib/msc-core-0.1.0.jar").toUri()))).isEqualTo(dir.resolve("msc"));
    }

    @Test
    void noInstallDirWhenRunFromClassesOrAnotherLayout() throws Exception {
        assertThat(Main.installDir(source(dir.resolve("build/classes/java/main/").toUri()))).isNull();
        assertThat(Main.installDir(source(dir.resolve("libs/msc-core.jar").toUri()))).isNull();
        assertThat(Main.installDir(null)).isNull();
    }

    @Test
    void theMachineEulaFileIsInTheUsersHomeAndNeverInATestConfig() {
        Path expected = Path.of(System.getProperty("user.home"), ".msc", "eula.json");
        assertThat(CoreConfig.fromEnvironment(Map.of()).eulaFile()).isEqualTo(expected);
        assertThat(CoreConfig.fromEnvironment(Map.of()).withWorkspace(dir).eulaFile()).isEqualTo(expected);
        assertThat(CoreConfig.forWorkspace(dir).eulaFile()).isNull();
    }

    private static CodeSource source(URI uri) throws Exception {
        return new CodeSource(uri.toURL(), (Certificate[]) null);
    }
}
