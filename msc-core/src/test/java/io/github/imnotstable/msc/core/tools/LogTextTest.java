package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.server.LogBuffer;
import io.github.imnotstable.msc.core.server.LogReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The lifecycle tools show log lines as the server's log buffer holds them: the reader already removed escapes. */
class LogTextTest {
    private static List<String> pumped(String output) throws Exception {
        LogBuffer log = new LogBuffer(100, Long.MAX_VALUE);
        LogReader.pump(new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), log, () -> {
        });
        return log.all();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\u001b[32m[12:00:00 INFO]: Done (1.2s)!\u001b[0m\n",
        "\u001b[1;33;40m[12:00:00 INFO]: \u001b[?25lDone (1.2s)!\u001b[m\n",
        "\u001b]0;title\u0007[12:00:00 INFO]: Done (1.2s)!\n",
        "\u001b]8;;https://x\u001b\\[12:00:00 INFO]: Done (1.2s)!\u001b]8;;\u001b\\\n",
    })
    void lineFromTheServerLogReachesTheToolsWithoutEscapes(String output) throws Exception {
        List<String> lines = pumped(output);

        assertThat(lines).containsExactly("[12:00:00 INFO]: Done (1.2s)!");
        assertThat(LogText.tail(lines, 5)).isEqualTo("[12:00:00 INFO]: Done (1.2s)!");
        assertThat(ServerConsole.consoleLines(lines, List.of())).containsExactly("Done (1.2s)!");
    }

    @Test
    void tailKeepsTheLastLinesInOrder() {
        assertThat(LogText.tail(List.of("a", "b", "c"), 2)).isEqualTo("b\nc");
        assertThat(LogText.tail(List.of("a"), 5)).isEqualTo("a");
    }
}
