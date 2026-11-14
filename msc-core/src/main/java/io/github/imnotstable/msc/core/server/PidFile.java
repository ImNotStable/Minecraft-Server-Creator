package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * A file holding a process's pid and start time, so a later core can find a process an earlier one left running.
 * The start time must match, so a reused pid is never taken for it. Two more words may name the core that started it,
 * by the same pid and start time, so a server whose core still runs is told apart from one whose core died (ADR 0037);
 * a file without them (written by cores before that, or for a pack host) has no known owner. A last word,
 * {@value #RESISTING}, records that the process survived a whole stop (#408), so the next start does not wait it all
 * out again.
 */
public final class PidFile {
    static final String RESISTING = "resisting";

    private PidFile() {
    }

    /**
     * A process told apart from a later one that reuses its pid: its pid and start time in epoch millis, 0 where the
     * system does not tell it (then it is never found alive, so it is never mistaken for another process).
     */
    public record Identity(long pid, long startedAt) {
        public static Identity of(ProcessHandle process) {
            return new Identity(process.pid(), process.info().startInstant().map(Instant::toEpochMilli).orElse(0L));
        }

        /** This JVM: the core. */
        public static Identity current() {
            return of(ProcessHandle.current());
        }

        /** The process while it runs, never another one that reused its pid. */
        public Optional<ProcessHandle> live() {
            return ProcessHandle.of(pid).filter(h -> startedAt != 0 && h.isAlive()
                    && h.info().startInstant().map(Instant::toEpochMilli).orElse(-1L) == startedAt);
        }
    }

    /** What a file says: the process, the core that started it (null: not known), whether it resisted a stop. */
    private record Entry(Identity process, Identity owner, boolean resisting) {
        /** The file's words, or null when they are not a pid file's. */
        static Entry parse(String[] words) {
            boolean resisting = words.length > 0 && words[words.length - 1].equals(RESISTING);
            String[] ids = resisting ? Arrays.copyOf(words, words.length - 1) : words;
            if (ids.length != 2 && ids.length != 4) {
                return null;
            }
            try {
                long[] n = Arrays.stream(ids).mapToLong(Long::parseLong).toArray();
                return new Entry(new Identity(n[0], n[1]), n.length == 4 ? new Identity(n[2], n[3]) : null, resisting);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        String text() {
            return process.pid() + " " + process.startedAt() + (owner == null ? "" : " " + owner.pid() + " "
                    + owner.startedAt()) + (resisting ? " " + RESISTING : "") + "\n";
        }
    }

    /** True when the file records that its process survived a whole stop ({@link #markResisting}). */
    public static boolean resisting(Path file) {
        Entry entry = entry(file);
        return entry != null && entry.resisting();
    }

    /** Records in the file that its process survived a whole stop. The rest stays as it was. */
    public static void markResisting(Path file) {
        Entry entry = entry(file);
        if (entry == null || entry.resisting()) {
            return;
        }
        try {
            writeWhole(file, new Entry(entry.process(), entry.owner(), true).text());
        } catch (IOException e) {
            // Best effort: the next start then tries the whole stop again.
        }
    }

    /** The core that started the file's process, when the file names it (ADR 0037). */
    public static Optional<Identity> owner(Path file) {
        Entry entry = entry(file);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.owner());
    }

    /** True when the file names the process {@code pid}, alive or not. */
    static boolean names(Path file, long pid) {
        Entry entry = entry(file);
        return entry != null && entry.process().pid() == pid;
    }

    private static Entry entry(Path file) {
        try {
            return Entry.parse(Files.readString(file).strip().split(" "));
        } catch (IOException e) {
            return null;
        }
    }

    /** Best effort: only leftover detection depends on it. No owner is recorded (a pack host's file). */
    public static void write(Path file, ProcessHandle process) {
        write(file, process, null);
    }

    /** Best effort: only leftover detection depends on it. {@code owner} is the core that started it, or null. */
    public static void write(Path file, ProcessHandle process, Identity owner) {
        try {
            writeWhole(file, new Entry(Identity.of(process), owner, false).text());
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
        String[] words;
        try {
            words = Files.readString(file).strip().split(" ");
            if (delete) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            return Optional.empty();
        }
        Entry entry = Entry.parse(words);
        return entry == null ? Optional.empty() : entry.process().live();
    }
}
