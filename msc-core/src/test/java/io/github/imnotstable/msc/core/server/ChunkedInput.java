package io.github.imnotstable.msc.core.server;

import java.io.InputStream;

/** A process's output arriving at most {@code chunk} bytes per read. */
final class ChunkedInput extends InputStream {
    /** Read sizes the log tests run every input at, to split lines, escapes and characters at every place. */
    static final int[] CHUNKS = {1, 3, 7, 64, 8192};

    private final byte[] bytes;
    private final int chunk;
    private int pos;

    ChunkedInput(byte[] bytes, int chunk) {
        this.bytes = bytes;
        this.chunk = chunk;
    }

    @Override
    public int read() {
        return pos < bytes.length ? bytes[pos++] & 0xff : -1;
    }

    @Override
    public int read(byte[] b, int off, int len) {
        if (pos >= bytes.length) {
            return -1;
        }
        int n = Math.min(Math.min(len, chunk), bytes.length - pos);
        System.arraycopy(bytes, pos, b, off, n);
        pos += n;
        return n;
    }
}
