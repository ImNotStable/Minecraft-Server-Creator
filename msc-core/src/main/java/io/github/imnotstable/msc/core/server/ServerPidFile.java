package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * {@code .msc/server.pid} and the lock every change to it is made under (ADR 0037 point 3): a core that reads the
 * file, decides and writes it (start: check, launch, record; stop: check, delete; a leftover stop: claim, delete)
 * does so in one short critical section that excludes every other core on the workspace, in this process or another.
 * The changes can only be made through {@link Held}, which only {@link #locked} hands out.
 *
 * <p>The lock is a {@link FileLock} on a separate file, {@code .msc/server.lock}, held only for that section, never
 * for a server's life. On Windows a locked region cannot be read or the file replaced, so locking the pid file itself
 * would keep {@code msc_status} from reading it and the atomic replace from writing it; the lock file holds no data
 * and is never read. A {@code FileLock} belongs to the whole JVM (a second one on the same file throws
 * {@link java.nio.channels.OverlappingFileLockException}, and closing any channel on the file may release it), so two
 * managers in one JVM first take an in-process lock keyed by the file's real path, and only its holder opens the file.
 * The OS releases the lock when a core dies; the file staying behind is harmless.
 */
final class ServerPidFile {
    /** How long a core waits for another one's critical section before it refuses. */
    static final Duration WAIT = Duration.ofSeconds(10);
    private static final long POLL_MILLIS = 20;
    private static final ConcurrentHashMap<Path, ReentrantLock> IN_JVM = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private volatile Duration wait = WAIT;

    ServerPidFile(Path dataDir) {
        this.file = dataDir.resolve("server.pid");
        this.lockFile = dataDir.resolve("server.lock");
    }

    /** For tests: how long a call waits for another core's critical section before it refuses ({@link #WAIT}). */
    void waitLimit(Duration wait) {
        this.wait = wait;
    }

    Path path() {
        return file;
    }

    Path lockFile() {
        return lockFile;
    }

    /** What a critical section runs. */
    @FunctionalInterface
    interface Body<T, X extends Exception> {
        T run(Held held) throws X, InterruptedException;
    }

    /** The changes to the pid file, valid only inside the critical section that handed it out. */
    final class Held {
        private boolean open = true;

        private Held() {
        }

        private Path file() {
            if (!open) {
                throw new IllegalStateException("the server pid file's lock is no longer held");
            }
            return file;
        }

        /** Records {@code process}, started by {@code owner}, replacing what the file said. */
        void write(ProcessHandle process, PidFile.Identity owner) {
            PidFile.write(file(), process, owner);
        }

        /** Deletes the file, whatever it names (a stale one). */
        void delete() {
            PidFile.take(file());
        }

        /** Deletes the file if it still names {@code pid}: a file another core wrote meanwhile stays. */
        void deleteIfNames(long pid) {
            if (PidFile.names(file(), pid)) {
                PidFile.take(file);
            }
        }

        /** Records that {@code pid} survived a whole stop, if the file still names it. */
        void markResisting(long pid) {
            if (PidFile.names(file(), pid)) {
                PidFile.markResisting(file);
            }
        }
    }

    /**
     * Runs {@code body} holding the lock, so no other core reads-decides-writes the file meanwhile. A refusal that
     * changes nothing when another core holds it for longer than the wait. Not reentrant.
     */
    <T, X extends Exception> T locked(Body<T, X> body) throws X, InterruptedException {
        try {
            Files.createDirectories(lockFile.getParent());
        } catch (IOException e) {
            throw unlockable(e);
        }
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock local = local();
        if (local.isHeldByCurrentThread()) {
            throw new IllegalStateException("the server pid file's lock is not reentrant");
        }
        if (!local.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
            throw busy();
        }
        FileChannel channel = null;
        try {
            try {
                channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                acquire(channel, deadline);
            } catch (IOException e) {
                throw unlockable(e);
            }
            Held held = new Held();
            try {
                return body.run(held);
            } finally {
                held.open = false;
            }
        } finally {
            closeQuietly(channel); // releases the file lock
            local.unlock();
        }
    }

    /**
     * Waits until no other core is inside its critical section, without taking part: a check before any side effect
     * then reads what that core's start or stop left, never what it is in the middle of. Creates nothing; a refusal
     * when the wait runs out.
     */
    void awaitIdle() throws InterruptedException {
        long deadline = System.nanoTime() + wait.toNanos();
        ReentrantLock local;
        try {
            local = local();
        } catch (ToolException e) {
            return; // no .msc folder: no core is in its section, which creates it first
        }
        if (local.isHeldByCurrentThread()) {
            return;
        }
        if (!local.tryLock(wait.toNanos(), TimeUnit.NANOSECONDS)) {
            throw busy();
        }
        FileChannel channel = null;
        try {
            // Checked only now: a manager of this JVM holding the in-process lock may not have created the file yet
            // (its first section on this workspace), and was waited for above (#497).
            if (!Files.exists(lockFile)) {
                return;
            }
            channel = FileChannel.open(lockFile, StandardOpenOption.WRITE);
            acquire(channel, deadline); // only waited for: closing the channel releases it
        } catch (NoSuchFileException e) {
            // Deleted by hand meanwhile: no core holds it.
        } catch (IOException e) {
            throw unlockable(e);
        } finally {
            closeQuietly(channel);
            local.unlock();
        }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The lock goes with the channel or, at the latest, with this process.
        }
    }

    /** The in-process lock of this lock file, keyed by its real path so two spellings of one folder share it. */
    private ReentrantLock local() {
        Path key;
        try {
            key = lockFile.getParent().toRealPath().resolve(lockFile.getFileName());
        } catch (IOException e) {
            throw unlockable(e);
        }
        return IN_JVM.computeIfAbsent(key, k -> new ReentrantLock());
    }

    /** Takes the file lock on {@code channel}; it lasts until the channel closes. */
    private void acquire(FileChannel channel, long deadline) throws IOException, InterruptedException {
        while (true) {
            FileLock lock = channel.tryLock();
            if (lock != null) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw busy();
            }
            Thread.sleep(POLL_MILLIS);
        }
    }

    private ToolException busy() {
        return new ToolException("another MSC session on this workspace is starting or stopping the server right "
                + "now (it has held " + lockFile + " for over " + wait.toSeconds() + " s): try again in a moment. "
                + "Nothing was changed.");
    }

    private ToolException unlockable(IOException e) {
        return new ToolException("could not lock " + lockFile + ": " + e.getMessage() + ". Nothing was changed.");
    }
}
