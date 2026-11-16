package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The writes a run makes to its own files, at the one place a full disk makes them fail: each attempt's folder, its
 * {@code mcp.json}, {@code prompt.md} and agent output files, {@code results.jsonl} and {@code report.md}. A run stops
 * when one of the attempt's files or {@code results.jsonl} cannot be written ({@link RunFolderUnwritable}). Tests put
 * in one that fails part way, as a full disk does, without filling a disk.
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

    /** Creates the folder {@code dir}, whose parent exists; fails when {@code dir} exists. */
    default void createDirectory(Path dir) throws IOException {
        Files.createDirectory(dir);
    }

    /** Writes {@code text} in UTF-8 as the whole of {@code file}, creating it, through {@link #write}. */
    default void writeString(Path file, String text) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            write(file, channel, ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
