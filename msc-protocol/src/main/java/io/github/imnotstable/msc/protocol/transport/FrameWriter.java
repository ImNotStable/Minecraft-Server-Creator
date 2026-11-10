package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Writes one frame per line. Safe to call from several threads. */
public final class FrameWriter {
    private final OutputStream out;
    private final int maxBytes;

    public FrameWriter(OutputStream out, int maxBytes) {
        this.out = out;
        this.maxBytes = maxBytes;
    }

    public synchronized void write(String frame) throws IOException {
        if (frame.indexOf('\n') >= 0) {
            throw new ProtocolException("frame contains a newline");
        }
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new ProtocolException("frame larger than " + maxBytes + " bytes");
        }
        out.write(bytes);
        out.write('\n');
        out.flush();
    }
}
