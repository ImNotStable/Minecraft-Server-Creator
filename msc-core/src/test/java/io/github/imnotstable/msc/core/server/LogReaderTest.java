package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/** The line half of {@link LogReader}'s contract, each input read at every chunk size. */
class LogReaderTest {
    private record Case(String name, byte[] input, List<String> lines) {
        Case(String name, String input, List<String> lines) {
            this(name, input.getBytes(StandardCharsets.UTF_8), lines);
        }
    }

    private static final int MAX = LogReader.MAX_LINE_CHARS;

    private static final List<Case> CASES = List.of(
            new Case("LF, CRLF and bare CR", "a\r\nb\rc\nd\n", List.of("a", "b", "c", "d")),
            new Case("blank lines are kept", "a\n\n\r\nb\n", List.of("a", "", "", "b")),
            new Case("an unterminated last line", "x\ny", List.of("x", "y")),
            new Case("a CR at the end", "a\r", List.of("a")),
            new Case("colours", "\u001b[31;1mred\u001b[0m and \u001b[mplain\n", List.of("red and plain")),
            new Case("a window title ended by BEL", "\u001b]0;Minecraft\u0007x\n", List.of("x")),
            new Case("a window title ended by ESC \\", "\u001b]0;t\u001b\\y\n", List.of("y")),
            new Case("a two-byte escape", "\u001b7a\u001b8b\n", List.of("ab")),
            new Case("an escape cut by the line end", "a\u001b[3\nb\n", List.of("a", "b")),
            new Case("control characters but tab", "a\u0000b\u0007c\td\u007f\n", List.of("abc\td")),
            new Case("multi-byte characters", "é€😀\n", List.of("é€😀")),
            new Case("malformed UTF-8", new byte[] {'a', (byte) 0xff, 'b', '\n'}, List.of("a�b")),
            new Case("a long line", "x".repeat(MAX + 10) + "\nnext\n",
                    List.of("x".repeat(MAX) + " [10 more chars cut]", "next")),
            new Case("a long line cut inside a surrogate pair", "x".repeat(MAX - 1) + "😀z\n",
                    List.of("x".repeat(MAX - 1) + " [3 more chars cut]")),
            new Case("a long unterminated line", "y".repeat(MAX + 1), List.of("y".repeat(MAX) + " [1 more chars cut]")));

    @TestFactory
    Stream<DynamicTest> linesFollowTheContract() {
        return CASES.stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            List<String> failures = new ArrayList<>();
            for (int chunk : ChunkedInput.CHUNKS) {
                List<String> got = new ArrayList<>();
                LogReader.read(new ChunkedInput(c.input(), chunk), got::add);
                if (!got.equals(c.lines())) {
                    failures.add("chunk=" + chunk + ": " + got.stream().map(l -> l.length() > 40
                            ? l.substring(0, 20) + "..(" + l.length() + ")" : l).toList());
                }
            }
            assertThat(failures).isEmpty();
        }));
    }

    @Test
    void aLineWithoutEndHoldsOnlyTheKeptPart() throws IOException {
        long size = 64L << 20;
        InputStream endless = new InputStream() {
            private long left = size;

            @Override
            public int read() {
                return left-- > 0 ? 'x' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + n, (byte) 'x');
                left -= n;
                return n;
            }
        };
        List<String> got = new ArrayList<>();
        LogReader.read(endless, got::add);
        assertThat(got).singleElement().asString().hasSize(MAX + (" [" + (size - MAX) + " more chars cut]").length());
    }

    @Test
    void theBufferDropsOldLinesOverItsCharacterBudget() {
        LogBuffer log = new LogBuffer(100, 10);
        log.add("aaaa");
        log.add("bbbb");
        log.add("cccc");
        assertThat(log.all()).containsExactly("bbbb", "cccc");
        log.add("x".repeat(50));
        assertThat(log.all()).containsExactly("x".repeat(50));
        assertThat(log.mark()).isEqualTo(4);
    }
}
