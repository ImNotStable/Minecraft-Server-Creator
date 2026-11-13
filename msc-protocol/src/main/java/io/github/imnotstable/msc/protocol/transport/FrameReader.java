package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Reads newline-terminated UTF-8 frames, refusing any frame over the size limit. Reads the stream in 64 KB chunks
 * and scans them for the newline, so callers may pass a raw socket stream.
 */
public final class FrameReader {
    private final InputStream in;
    private final int maxBytes;
    private final byte[] chunk = new byte[64 * 1024];
    private int pos;
    private int limit;
    /** The frame so far, when it spans chunks. */
    private byte[] frame = new byte[1024];
    private int length;

    public FrameReader(InputStream in, int maxBytes) {
        this.in = in;
        this.maxBytes = maxBytes;
    }

    /** Next frame without its newline, or {@code null} at end of stream. */
    public String read() throws IOException {
        length = 0;
        while (true) {
            if (pos == limit) {
                int n = in.read(chunk, 0, chunk.length);
                if (n == -1) {
                    if (length == 0) {
                        return null;
                    }
                    throw new ProtocolException("stream ended in the middle of a frame");
                }
                pos = 0;
                limit = n;
            }
            int start = pos;
            int newline = indexOfNewline(start);
            int end = newline < 0 ? limit : newline;
            pos = newline < 0 ? limit : newline + 1;
            if (newline >= 0 && length == 0) {
                checkSize(end - start);
                return new String(chunk, start, end - start, StandardCharsets.UTF_8);
            }
            append(start, end - start);
            if (newline >= 0) {
                return new String(frame, 0, length, StandardCharsets.UTF_8);
            }
        }
    }

    private int indexOfNewline(int from) {
        for (int i = from; i < limit; i++) {
            if (chunk[i] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private void append(int from, int n) throws ProtocolException {
        checkSize(length + n);
        if (length + n > frame.length) {
            frame = Arrays.copyOf(frame, Math.max(length + n, Math.min(frame.length * 2, maxBytes)));
        }
        System.arraycopy(chunk, from, frame, length, n);
        length += n;
    }

    private void checkSize(long size) throws ProtocolException {
        if (size > maxBytes) {
            throw new ProtocolException("frame larger than " + maxBytes + " bytes");
        }
    }
}
