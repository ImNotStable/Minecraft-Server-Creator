package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;

/**
 * The writes a run makes into its folder ({@code results.jsonl}, {@code report.md}), at the one place a full disk
 * makes them fail. Tests put in one that fails part way, as a full disk does, without filling a disk.
 */
public interface Disk {

    /** The real file system. */
    Disk REAL = new Disk() {
    };

    /** Writes all of {@code bytes} at the channel's position; {@code file} is the file the write is for. */
    default void write(Path file, FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    /** Cuts the channel's file back to {@code size} bytes; {@code file} is the file the write is for. */
    default void truncate(Path file, FileChannel channel, long size) throws IOException {
        channel.truncate(size);
    }
}
