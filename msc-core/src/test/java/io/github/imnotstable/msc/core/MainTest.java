package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.MachineEula;
import java.io.File;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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
        MachineEula expected = MachineEula.forHome(Path.of(System.getProperty("user.home")));
        assertThat(expected).hasToString(Path.of(System.getProperty("user.home"), ".msc", "eula.json").toString());
        assertThat(CoreConfig.fromEnvironment(Map.of()).machineEula()).isEqualTo(expected);
        assertThat(CoreConfig.fromEnvironment(Map.of()).withWorkspace(dir).machineEula()).isEqualTo(expected);
        assertThat(CoreConfig.forWorkspace(dir).machineEula()).isEqualTo(MachineEula.none());
    }

    /**
     * A non-ASCII MSC_WORKSPACE under LANG=C: the JVM's file name encoding is US-ASCII, so the path cannot exist in
     * it. Main says so in one line, with the fix, instead of a stack trace. The bytes come from printf, so the test
     * JVM's own locale does not matter; Windows passes the environment as UTF-16 and never hits this.
     */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows file names are UTF-16 whatever the locale")
    void aWorkspacePathTheLocaleCannotRepresentIsOneLineWithTheFix() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        ProcessBuilder builder = new ProcessBuilder("/bin/sh", "-c",
                "MSC_WORKSPACE=\"$1/w$(printf '\\303\\251')\" exec \"$2\" -cp \"$3\" " + Main.class.getName(), "sh",
                dir.toString(), java, System.getProperty("java.class.path"))
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        builder.environment().putAll(Map.of("LC_ALL", "C", "LANG", "C"));
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = builder.start();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();

        assertThat(p.exitValue()).isEqualTo(2);
        assertThat(err.lines()).singleElement().asString()
                .startsWith("MSC cannot use the path " + dir)
                .contains("(US-ASCII) cannot represent", "UTF-8 locale", "LANG=C.UTF-8");
    }

    @Test
    void anotherBadPathIsNotBlamedOnTheLocale() {
        assertThat(Main.unrepresentable(new InvalidPathException("a<b", "Illegal char"),
                StandardCharsets.US_ASCII)).isNull();
        assertThat(Main.unrepresentable(new InvalidPathException("w\u00e9", "Unmappable"),
                StandardCharsets.UTF_8)).isNull();
    }

    private static CodeSource source(URI uri) throws Exception {
        return new CodeSource(uri.toURL(), (Certificate[]) null);
    }
}
