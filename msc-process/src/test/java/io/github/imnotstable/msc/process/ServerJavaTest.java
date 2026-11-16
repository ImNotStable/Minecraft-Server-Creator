package io.github.imnotstable.msc.process;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** A server's Java gets the UTF-8 console flags; any other program gets them in JAVA_OPTS (#466, #478). */
class ServerJavaTest {
    private static final String TEXT = "\u00e9 \u00a7 \u4f60 \uD83D\uDE00";
    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @ParameterizedTest
    @ValueSource(strings = {"java", "/opt/jdk/bin/java", "C:\\Program Files\\Java\\bin\\java.exe", "JAVAW.EXE"})
    void aJavaLauncherGetsTheFlagsRightAfterIt(String java) {
        assertThat(ServerJava.processBuilder(List.of(java, "-Xmx2G", "-jar", "paper.jar", "--nogui")).command())
                .startsWith(java).containsSubsequence(ServerJava.ENCODING_FLAGS)
                .endsWith("-Xmx2G", "-jar", "paper.jar", "--nogui")
                .hasSize(5 + ServerJava.ENCODING_FLAGS.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"./start.sh", "cmd.exe", "javascript", "/usr/bin/env", "myjava"})
    void anyOtherProgramIsPassedUnchangedWithTheFlagsInJavaOpts(String program) {
        List<String> command = List.of(program, "java", "-jar", "server.jar");
        ProcessBuilder builder = ServerJava.processBuilder(command);
        assertThat(builder.command()).isEqualTo(command);
        assertThat(builder.environment().get(ServerJava.JAVA_OPTS).split(" ")).contains(
                ServerJava.ENCODING_FLAGS.toArray(String[]::new));
    }

    @ParameterizedTest
    @ValueSource(strings = {"-Dstdout.encoding=Cp1252", "-Dfile.encoding=COMPAT"})
    void aFlagTheCommandSetsItselfIsKept(String own) {
        List<String> out = ServerJava.processBuilder(List.of("java", own, "-jar", "server.jar")).command();
        String property = own.substring(0, own.indexOf('=') + 1);
        assertThat(out.stream().filter(a -> a.startsWith(property))).containsExactly(own);
        assertThat(out).hasSize(4 + ServerJava.ENCODING_FLAGS.size() - 1);
        assertThat(ServerJava.withEncoding(List.of())).isEmpty();
        String opts = ServerJava.withFlags(" -Xmx1G " + own);
        assertThat(opts).startsWith("-Xmx1G " + own + " ").containsOnlyOnce(property);
        assertThat(opts.split(" ")).hasSize(2 + ServerJava.ENCODING_FLAGS.size() - 1);
    }

    @Test
    void aJavaStartedDirectlyEchoesNonAsciiTextUnderACLocale() throws Exception {
        assertThat(echo(List.of(JAVA, "-cp", System.getProperty("java.class.path"), Echo.class.getName())))
                .contains(TEXT, "got: say " + TEXT);
    }

    /** gradlew and every Gradle-made start script pass JAVA_OPTS to their Java, as this script does. */
    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "a POSIX shell script; cmd scripts read JAVA_OPTS alike")
    void aJavaStartedByAScriptEchoesNonAsciiTextUnderACLocale() throws Exception {
        assertThat(echo(List.of("/bin/sh", "-c", "exec \"$0\" $JAVA_OPTS -cp \"$1\" \"$2\"", JAVA,
                System.getProperty("java.class.path"), Echo.class.getName())))
                .contains(TEXT, "got: say " + TEXT);
    }

    /** What the program printed after one console line, both read and written as UTF-8, under LC_ALL=C. */
    private static String echo(List<String> command) throws Exception {
        ProcessBuilder builder = ServerJava.processBuilder(command).redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C");
        builder.environment().put("LANG", "C");
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        Process p = builder.start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(("say " + TEXT + "\n").getBytes(UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        return out;
    }

    /** Prints non-ASCII text on System.out and echoes a console line read in the stdin encoding, as JLine does. */
    public static final class Echo {
        public static void main(String[] args) throws Exception {
            System.out.println(TEXT);
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in,
                    Charset.forName(System.getProperty("stdin.encoding"))));
            System.out.println("got: " + in.readLine());
        }
    }
}
