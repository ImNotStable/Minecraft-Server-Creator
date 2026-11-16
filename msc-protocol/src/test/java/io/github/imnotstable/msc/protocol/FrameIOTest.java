package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.transport.FrameReader;
import io.github.imnotstable.msc.protocol.transport.FrameWriter;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FrameIOTest {
    @Test
    void writesAndReadsUtf8Lines() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameWriter writer = new FrameWriter(out, 1024);
        writer.write("{\"a\":\"héllo ✓\"}");
        writer.write("{}");
        FrameReader reader = new FrameReader(new ByteArrayInputStream(out.toByteArray()), 1024);
        assertThat(reader.read()).isEqualTo("{\"a\":\"héllo ✓\"}");
        assertThat(reader.read()).isEqualTo("{}");
        assertThat(reader.read()).isNull();
    }

    @Test
    void refusesOversizeAndNewlines() {
        FrameWriter writer = new FrameWriter(new ByteArrayOutputStream(), 4);
        assertThatThrownBy(() -> writer.write("12345")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> writer.write("a\nb")).isInstanceOf(ProtocolException.class);
        FrameReader reader = new FrameReader(new ByteArrayInputStream("12345\n".getBytes(StandardCharsets.UTF_8)), 4);
        assertThatThrownBy(reader::read).isInstanceOf(ProtocolException.class);
    }

    @Test
    void framesSpanReadChunksWhole() throws IOException {
        // Larger than the reader's 64 KB chunk, with a two-byte character across a chunk boundary.
        String big = "x".repeat(65_535) + "é" + "y".repeat(100_000);
        String bytes = "a\n" + big + "\n\nlast\n";
        FrameReader reader = new FrameReader(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8)), 200_000);
        assertThat(reader.read()).isEqualTo("a");
        assertThat(reader.read()).isEqualTo(big);
        assertThat(reader.read()).isEmpty();
        assertThat(reader.read()).isEqualTo("last");
        assertThat(reader.read()).isNull();
    }

    @Test
    void sizeLimitHoldsAcrossChunks() throws IOException {
        byte[] exact = ("z".repeat(100_000) + "\n").getBytes(StandardCharsets.UTF_8);
        assertThat(new FrameReader(new ByteArrayInputStream(exact), 100_000).read()).hasSize(100_000);
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(exact), 99_999).read())
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void readsInBulkAcrossChunkBoundaries() throws IOException {
        String big = "x".repeat(200_000);
        byte[] bytes = ("a\n" + big + "\nb\n").getBytes(StandardCharsets.UTF_8);
        InputStream bulkOnly = new BufferedInputStream(new ByteArrayInputStream(bytes)) {
            @Override
            public int read() {
                throw new AssertionError("per-byte read");
            }
        };
        FrameReader reader = new FrameReader(bulkOnly, 200_000);
        assertThat(reader.read()).isEqualTo("a");
        assertThat(reader.read()).isEqualTo(big);
        assertThat(reader.read()).isEqualTo("b");
        assertThat(reader.read()).isNull();
        FrameReader small = new FrameReader(new ByteArrayInputStream(bytes), 199_999);
        assertThat(small.read()).isEqualTo("a");
        assertThatThrownBy(small::read).isInstanceOf(ProtocolException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 7, 64})
    void readsFramesThatArriveInPieces(int piece) throws IOException {
        // #442: a socket hands over what has arrived, which may end inside a frame, a character or right at a newline.
        String bytes = "{\"a\":\"héllo ✓\"}\n\n" + "z".repeat(200) + "€\nlast\n";
        InputStream trickle = new FilterInputStream(new ByteArrayInputStream(bytes.getBytes(StandardCharsets.UTF_8))) {
            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return super.read(b, off, Math.min(len, piece));
            }
        };
        FrameReader reader = new FrameReader(trickle, 1024);
        assertThat(reader.read()).isEqualTo("{\"a\":\"héllo ✓\"}");
        assertThat(reader.read()).isEmpty();
        assertThat(reader.read()).isEqualTo("z".repeat(200) + "€");
        assertThat(reader.read()).isEqualTo("last");
        assertThat(reader.read()).isNull();
    }

    @Test
    void truncatedFrameIsAnError() {
        FrameReader reader = new FrameReader(new ByteArrayInputStream("{\"a\"".getBytes(StandardCharsets.UTF_8)), 1024);
        assertThatThrownBy(reader::read).isInstanceOf(ProtocolException.class);
    }
}
