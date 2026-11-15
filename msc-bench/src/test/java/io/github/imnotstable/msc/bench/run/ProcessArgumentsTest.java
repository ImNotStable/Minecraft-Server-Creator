package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** #595: Windows command lines split into whole arguments as the C runtime splits them. */
class ProcessArgumentsTest {

    record Line(String line, List<String> arguments) {
        @Override
        public String toString() {
            return "[" + line + "]";
        }
    }

    static Stream<Line> lines() {
        return Stream.of(
                new Line("java -Dmsc.bench.run=r Main", List.of("java", "-Dmsc.bench.run=r", "Main")),
                new Line("\"C:\\Program Files\\java.exe\"  \t\"-Dmsc.bench.attempt=C:\\a b\\t\\toolset-1\" x",
                        List.of("C:\\Program Files\\java.exe", "-Dmsc.bench.attempt=C:\\a b\\t\\toolset-1", "x")),
                // Node and Java quote an argument ending in a backslash by doubling it before the closing quote.
                new Line("java \"-Dmsc.bench.attempt=C:\\a b\\\\\" y", List.of("java", "-Dmsc.bench.attempt=C:\\a b\\",
                        "y")),
                new Line("java C:\\a\\b \\\\server\\share", List.of("java", "C:\\a\\b", "\\\\server\\share")),
                new Line("java a\\\"b \"c\\\\\\\"d\"", List.of("java", "a\"b", "c\\\"d")),
                new Line("java \"a\"\"b\" -Dx=\"\"", List.of("java", "a\"b", "-Dx=")),
                new Line("java -Dmsc.bench.run=r\" \"-Dmsc.bench.attempt=x", List.of("java",
                        "-Dmsc.bench.run=r -Dmsc.bench.attempt=x")),
                new Line("  ", List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lines")
    void splitsAsTheCRuntimeDoes(Line line) {
        assertThat(ProcessArguments.splitWindows(line.line())).containsExactlyElementsOf(line.arguments());
    }

    @Test
    void readsTheProcessQueryOneObjectOrAList() {
        assertThat(ProcessArguments.parseWindows("\uFEFF{\"ProcessId\":7,\"CommandLine\":\"java -Da=b Main\"}\r\n"))
                .isEqualTo(Map.of(7L, List.of("-Da=b", "Main")));
        assertThat(ProcessArguments.parseWindows("[{\"ProcessId\":7,\"CommandLine\":\"java x\"},"
                + "{\"ProcessId\":4,\"CommandLine\":null},{\"ProcessId\":0},{\"CommandLine\":\"y\"},3]"))
                .isEqualTo(Map.of(7L, List.of("x")));
        assertThat(ProcessArguments.parseWindows("")).isEmpty();
        assertThat(ProcessArguments.parseWindows("Get-CimInstance : Access denied")).isEmpty();
    }
}
