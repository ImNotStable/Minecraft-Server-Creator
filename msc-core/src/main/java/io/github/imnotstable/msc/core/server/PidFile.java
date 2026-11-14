package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

/**
 * A file holding a process's pid and start time, so a later core can find a process an earlier one left running.
 * The start time must match, so a reused pid is never taken for it. A third word, {@value #RESISTING}, records that
 * the process survived a whole stop (#408), so the next start does not wait it all out again.
 */
public final class PidFile {
    static final String RESISTING = "resisting";

    private PidFile() {
    }

    /** True when the file records that its process survived a whole stop ({@link #markResisting}). */
    public static boolean resisting(Path file) {
        String[] parts = words(file);
        return parts != null && parts.length == 3 && parts[2].equals(RESISTING);
    }

    /** Records in the file that its process survived a whole stop. The pid and start time stay as they were. */
    public static void markResisting(Path file) {
        String[] parts = words(file);
        if (parts == null || parts.length != 2) {
            return;
        }
        try {
            writeWhole(file, parts[0] + " " + parts[1] + " " + RESISTING + "\n");
        } catch (IOException e) {
            // Best effort: the next start then tries the whole stop again.
        }
    }

    private static String[] words(Path file) {
        try {
            return Files.readString(file).strip().split(" ");
        } catch (IOException e) {
            return null;
        }
    }

    /** Best effort: only leftover detection depends on it. */
    public static void write(Path file, ProcessHandle process) {
        try {
            long started = process.info().startInstant().map(Instant::toEpochMilli).orElse(0L);
            writeWhole(file, process.pid() + " " + started + "\n");
        } catch (IOException e) {
            // Without it a leftover is not found; nothing else breaks.
        }
    }

    /** Whole or not at all ({@link AtomicFile}): a core reading it meanwhile never sees half a pid. */
    private static void writeWhole(Path file, String text) throws IOException {
        AtomicFile.write(file, to -> Files.writeString(to, text));
    }

    /** The live process the file names, or empty. The file is deleted either way. */
    public static Optional<ProcessHandle> take(Path file) {
        return read(file, true);
    }

    /** The live process the file names, or empty. The file stays. */
    public static Optional<ProcessHandle> peek(Path file) {
        return read(file, false);
    }

    private static Optional<ProcessHandle> read(Path file, boolean delete) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String[] parts;
        try {
            parts = Files.readString(file).strip().split(" ");
            if (delete) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        if (parts.length != 2 && !(parts.length == 3 && parts[2].equals(RESISTING))) {
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
