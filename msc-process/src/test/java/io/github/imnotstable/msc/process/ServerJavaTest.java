package io.github.imnotstable.msc.process;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A server's Java gets the UTF-8 console flags; any other program is left alone (#466). */
class ServerJavaTest {
    @ParameterizedTest
    @ValueSource(strings = {"java", "/opt/jdk/bin/java", "C:\\Program Files\\Java\\bin\\java.exe", "JAVAW.EXE"})
    void aJavaLauncherGetsTheFlagsRightAfterIt(String java) {
        assertThat(ServerJava.withEncoding(List.of(java, "-Xmx2G", "-jar", "paper.jar", "--nogui")))
                .startsWith(java).containsSubsequence(ServerJava.ENCODING_FLAGS)
                .endsWith("-Xmx2G", "-jar", "paper.jar", "--nogui")
                .hasSize(5 + ServerJava.ENCODING_FLAGS.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"./start.sh", "cmd.exe", "javascript", "/usr/bin/env", "myjava"})
    void anyOtherProgramIsPassedUnchanged(String program) {
        List<String> command = List.of(program, "java", "-jar", "server.jar");
        assertThat(ServerJava.withEncoding(command)).isEqualTo(command);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dstdout.encoding=Cp1252", "-Dfile.encoding=COMPAT"})
    void aFlagTheCommandSetsItselfIsKept(String own) {
        List<String> out = ServerJava.withEncoding(List.of("java", own, "-jar", "server.jar"));
        String property = own.substring(0, own.indexOf('=') + 1);
        assertThat(out.stream().filter(a -> a.startsWith(property))).containsExactly(own);
        assertThat(out).hasSize(4 + ServerJava.ENCODING_FLAGS.size() - 1);
        assertThat(ServerJava.withEncoding(List.of())).isEmpty();
    }
}
