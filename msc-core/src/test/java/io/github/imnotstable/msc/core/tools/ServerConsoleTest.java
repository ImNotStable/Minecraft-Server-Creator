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

    @Test
    void minestomLogPrefixesAreStripped() {
        assertThat(ServerConsole.consoleLines(List.of("[main] INFO com.example.Lobby - Wave 3"), List.of()))
                .containsExactly("Wave 3");
    }
}
