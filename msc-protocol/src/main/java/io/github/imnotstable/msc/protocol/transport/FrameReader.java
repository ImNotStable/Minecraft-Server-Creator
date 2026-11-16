package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads newline-terminated UTF-8 frames, refusing any frame over the size limit. Reads the stream in
 * 64 KiB chunks and scans each chunk for the newline, so callers may pass a raw socket stream. Bytes
 * after a frame's newline stay buffered for the next frame.
 */
public final class FrameReader {
    private static final int CHUNK_BYTES = 64 * 1024;

    private final InputStream in;
    private final int maxBytes;
    private final byte[] chunk = new byte[CHUNK_BYTES];
    private int pos;
    private int limit;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(1024);

    public FrameReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** Next frame without its newline, or {@code null} at end of stream. */
    public String read() throws IOException {
        return read(maxBytes);
    }

    /** Like {@link #read()}, refusing a frame over {@code maxBytes} (the handshake's smaller limit). */
    public String read(int maxBytes) throws IOException {
        buffer.reset();
        while (true) {
            if (pos == limit) {
                int n = in.read(chunk, 0, chunk.length);
                if (n == -1) {
                    if (buffer.size() == 0) {
                        return null;
                    }
                    throw new ProtocolException("stream ended in the middle of a frame");
                }
                pos = 0;
                limit = n;
            }
            int newline = indexOfNewline();
            int end = newline >= 0 ? newline : limit;
            int length = end - pos;
            if (buffer.size() + length > maxBytes) {
                throw new ProtocolException("frame larger than " + maxBytes + " bytes");
            }
            if (newline >= 0 && buffer.size() == 0) {
                String frame = new String(chunk, pos, length, StandardCharsets.UTF_8);
                pos = newline + 1;
                return frame;
            }
            buffer.write(chunk, pos, length);
            if (newline >= 0) {
                pos = newline + 1;
                return buffer.toString(StandardCharsets.UTF_8);
            }
            pos = limit;
        }
    }

    private int indexOfNewline() {
        for (int i = pos; i < limit; i++) {
            if (chunk[i] == '\n') {
                return i;
            }
        }
        return -1;
    }
}
