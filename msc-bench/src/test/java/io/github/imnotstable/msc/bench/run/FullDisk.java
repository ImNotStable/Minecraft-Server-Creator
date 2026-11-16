package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Set;

/**
 * A disk that fills up: writes to the files named in {@code full} after the first {@code room} of them take half
 * their bytes and then fail as a full disk does, and so does creating a folder named there; with {@code stuck},
 * cutting a file back fails too.
 */
public final class FullDisk implements Disk {

    private final Set<String> full;
    private final int room;
    private final boolean stuck;
    private int writes;

    public FullDisk(Set<String> full, int room, boolean stuck) {
        this.full = full;
        this.room = room;
        this.stuck = stuck;
    }

    @Override
    public void write(Path file, FileChannel channel, ByteBuffer bytes) throws IOException {
        if (!full.contains(file.getFileName().toString()) || writes++ < room) {
            Disk.REAL.write(file, channel, bytes);
            return;
        }
        Disk.REAL.write(file, channel, bytes.slice(bytes.position(), bytes.remaining() / 2));
        throw new IOException("No space left on device");
    }

    @Override
    public void createDirectory(Path dir) throws IOException {
        if (full.contains(dir.getFileName().toString()) && writes++ >= room) {
            throw new IOException("No space left on device");
        }
        Disk.REAL.createDirectory(dir);
    }

    @Override
    public void truncate(Path file, FileChannel channel, long size) throws IOException {
        if (stuck) {
            throw new IOException("Input/output error");
        }
        Disk.REAL.truncate(file, channel, size);
    }
}
