package io.github.imnotstable.msc.protocol.transport;

import io.github.imnotstable.msc.protocol.ProtocolException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Writes one frame per line. Safe to call from several threads: frames never interleave. Uses a
 * {@link ReentrantLock} rather than {@code synchronized} so a virtual thread blocked on a full
 * socket buffer does not pin its carrier. The stream is buffered and flushed once per frame.
 */
public final class FrameWriter {
    private final OutputStream out;
    private final ReentrantLock lock = new ReentrantLock();
    private final int maxBytes;

    public FrameWriter(OutputStream out, int maxBytes) {
        this.out = new BufferedOutputStream(out, 64 * 1024);
        this.maxBytes = maxBytes;
    }

    /**
     * Write one frame. A frame with a newline or over the limit throws {@link ProtocolException} (for the limit
     * {@link FrameTooLargeException}) before a byte is written; any other {@link IOException} means the stream broke.
     */
    public void write(String frame) throws IOException {
        if (frame.indexOf('\n') >= 0) {
            throw new ProtocolException("frame contains a newline");
        }
        byte[] bytes = frame.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new FrameTooLargeException("frame", bytes.length, maxBytes);
        }
        lock.lock();
        try {
            out.write(bytes);
            out.write('\n');
            out.flush();
        } finally {
            lock.unlock();
        }
    }
}
