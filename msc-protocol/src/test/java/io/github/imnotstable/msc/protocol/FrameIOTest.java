package io.github.imnotstable.msc.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.protocol.transport.FrameReader;
import io.github.imnotstable.msc.protocol.transport.FrameWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

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
    void truncatedFrameIsAnError() {
        FrameReader reader = new FrameReader(new ByteArrayInputStream("{\"a\"".getBytes(StandardCharsets.UTF_8)), 1024);
        assertThatThrownBy(reader::read).isInstanceOf(ProtocolException.class);
    }
}
