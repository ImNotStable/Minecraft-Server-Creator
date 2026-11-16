package io.github.imnotstable.msc.core.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JsonRpcLinesTest {

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 4096})
    void readsLinesThatArriveInPieces(int piece) throws IOException {
        // #442: stdin hands over what has arrived, which may end inside a message, a character or at a newline.
        String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"say\":\"héllo €✓\"}}";
        String note = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        String input = ping + "\r\n\nnot json\n[" + ping + "," + note + "]\n" + note;
        InputStream trickle = new FilterInputStream(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, piece));
            }
        };
        ByteArrayOutputStream notes = new ByteArrayOutputStream();
        try (JsonRpcLines lines = new JsonRpcLines(trickle, new PrintStream(notes, true, StandardCharsets.UTF_8))) {
            assertThat(new String(lines.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo(ping + "\n" + ping + "\n" + note + "\n" + note + "\n");
        }
        assertThat(notes.toString(StandardCharsets.UTF_8)).contains("not json").hasLineCount(1);
    }
}
