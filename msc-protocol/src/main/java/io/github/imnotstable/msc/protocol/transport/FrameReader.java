package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-terminated UTF-8 frames, refusing any frame over the size limit. The stream is
 * buffered here, so callers may pass a raw socket stream.
 */
public final class FrameReader {
    private final InputStream in;
    private final int maxBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);

    public FrameReader(InputStream in, int maxBytes) {
        this.in = in instanceof BufferedInputStream ? in : new BufferedInputStream(in, 64 * 1024);
        this.maxBytes = maxBytes;
    }

    /** Next frame without its newline, or {@code null} at end of stream. */
    public String read() throws IOException {
        buffer.reset();
        while (true) {
            int b = in.read();
            if (b == -1) {
                if (buffer.size() == 0) {
                    return null;
                }
                throw new ProtocolException("stream ended in the middle of a frame");
            }
            if (b == '\n') {
                return buffer.toString(StandardCharsets.UTF_8);
            }
            if (buffer.size() >= maxBytes) {
                throw new ProtocolException("frame larger than " + maxBytes + " bytes");
            }
            buffer.write(b);
        }
    }
}
