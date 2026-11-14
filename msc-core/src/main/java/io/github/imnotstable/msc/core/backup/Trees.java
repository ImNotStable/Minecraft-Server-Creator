package io.github.imnotstable.msc.core.backup;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Folder copies, moves and deletes for world backups. On Windows a file another process holds open (the server's
 * {@code session.lock}, a virus scanner, Explorer) refuses a move or delete for a moment, so those are retried; the
 * running server's {@code session.lock} is never copied, since the server locks it and makes its own on start.
 */
final class Trees {
    static final String SESSION_LOCK = "session.lock";
    private static final int TRIES = 20;
    private static final long WAIT_MILLIS = 250;

    private Trees() {
    }

    /** Copy the tree at {@code from} to {@code to} (which must not exist), without session.lock; returns its bytes. */
    static long copy(Path from, Path to) throws IOException {
        AtomicLong bytes = new AtomicLong();
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!file.getFileName().toString().equals(SESSION_LOCK)) {
                    try {
                        Files.copy(file, to.resolve(from.relativize(file).toString()));
                        bytes.addAndGet(attrs.size());
                    } catch (NoSuchFileException gone) {
                        // A temporary file the server removed while the copy ran: not part of the world.
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes.get();
    }

    /** Bytes of the files under {@code root}. */
    static long size(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    /** Delete {@code root} and everything under it, if it exists. */
    static void delete(Path root) throws IOException, InterruptedException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.sorted(Comparator.reverseOrder()).toList()) {
                retry(() -> Files.deleteIfExists(p));
            }
        }
    }

    /** Rename {@code from} to {@code to} in one step (same folder or volume), retrying while Windows holds it. */
    static void move(Path from, Path to) throws IOException, InterruptedException {
        retry(() -> Files.move(from, to, StandardCopyOption.ATOMIC_MOVE));
    }

    private interface Io {
        void run() throws IOException;
    }

    private static void retry(Io io) throws IOException, InterruptedException {
        for (int i = 1; ; i++) {
            try {
                io.run();
                return;
            } catch (AccessDeniedException e) {
                if (i == TRIES) {
                    throw e;
                }
            } catch (FileSystemException e) {
                // Windows: "The process cannot access the file because it is being used by another process".
                if (i == TRIES || e instanceof NoSuchFileException || e.getReason() == null
                        || !e.getReason().contains("used by another process")) {
                    throw e;
                }
            }
            Thread.sleep(WAIT_MILLIS);
        }
    }
}
