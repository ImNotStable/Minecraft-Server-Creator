package io.github.imnotstable.msc.core.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServerConsoleTest {
    @Test
    void aFailedCommandKeepsTheExceptionHeaderAndItsProjectFrames() {
        List<String> log = new ArrayList<>();
        log.add("[msc-req-server.command] ERROR net.minestom.server.command.CommandParserImpl - An exception was encountered");
        log.add("java.lang.NullPointerException: Unloaded chunk at 292,-60,-6");
        log.add("\tat net.minestom.server.instance.Instance.getBlock(Instance.java:621)");
        log.add("\tat dev.game.Trials.rootsLeft(Trials.java:282)");
        for (int i = 0; i < 40; i++) {
            log.add("\tat java.base/java.util.stream.Pipeline.step" + i + "(Pipeline.java:" + i + ")");
        }

        List<String> lines = ServerConsole.consoleLines(log, List.of());

        assertThat(lines).contains("java.lang.NullPointerException: Unloaded chunk at 292,-60,-6",
                "at dev.game.Trials.rootsLeft(Trials.java:282)", "... 40 framework frames");
        assertThat(lines).hasSizeLessThan(8);
    }

    @Test
    void linesTheCommandReturnedAreNotRepeated() {
        assertThat(ServerConsole.consoleLines(List.of("[12:00:00 INFO]: hello", "[12:00:00 INFO]: [Server] Wave 3"),
                List.of("hello"))).containsExactly("[Server] Wave 3");
    }

    /** #611: a cut keeps the start of the command's reply and the end of the console lines, the newest. */
    @Test
    void aCutCommandOutputKeepsTheNewestConsoleLine() {
        List<String> console = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            console.add("[Server] wave " + i + " " + "w".repeat(250));
        }
        ServerConsole.Output out = new ServerConsole.Output("reply line", "console during the command:\n",
                String.join("\n", console));
        for (int n : new int[] {200, 1000, 6000}) {
            assertThat(out.fit(n)).as("n " + n).hasSizeLessThanOrEqualTo(n)
                    .startsWith("reply line\nconsole during the command:\n[truncated: ")
                    .endsWith(console.getLast().substring(200));
        }
        assertThat(out.fit(6000)).endsWith(console.getLast());
        assertThat(out.fit(100_000)).isEqualTo(out.whole());
        // The console path: only log lines.
        ServerConsole.Output log = new ServerConsole.Output("", "", String.join("\n", console));
        assertThat(log.fit(6000)).hasSizeLessThanOrEqualTo(6000).startsWith("[truncated: ").endsWith(console.getLast());
        assertThat(new ServerConsole.Output("ran say hi", "", "").fit(6000)).isEqualTo("ran say hi");
    }

    @Test
    void minestomLogPrefixesAreStripped() {
        assertThat(ServerConsole.consoleLines(List.of("[main] INFO com.example.Lobby - Wave 3"), List.of()))
                .containsExactly("Wave 3");
    }
}
