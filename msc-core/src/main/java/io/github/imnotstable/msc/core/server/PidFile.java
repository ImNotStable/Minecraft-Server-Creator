package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * A file holding a process's pid and start time, so a later core can find a process an earlier one left running.
 * The start time must match, so a reused pid is never taken for it.
 */
public final class PidFile {
    private PidFile() {
    }

    /** Best effort: only leftover detection depends on it. */
    public static void write(Path file, ProcessHandle process) {
        try {
            long started = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            Files.createDirectories(file.getParent());
            Files.writeString(file, process.pid() + " " + started + "\n");
        } catch (IOException e) {
            // Without it a leftover is not found; nothing else breaks.
        }
    }

    /** The live process the file names, or empty. The file is deleted either way. */
    public static Optional<ProcessHandle> take(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String[] parts;
        try {
            parts = Files.readString(file).strip().split(" ");
            Files.deleteIfExists(file);
        } catch (IOException e) {
            return Optional.empty();
        }
        if (parts.length != 2) {
            return Optional.empty();
        }
        long pid;
        long startedAt;
        try {
            pid = Long.parseLong(parts[0]);
            startedAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return ProcessHandle.of(pid).filter(h -> h.isAlive() && startedAt != 0
                && h.info().startInstant().map(Instant::toEpochMilli).orElse(-1L) == startedAt);
    }
}
