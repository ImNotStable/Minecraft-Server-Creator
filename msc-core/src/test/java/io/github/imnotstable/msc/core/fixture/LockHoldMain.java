package io.github.imnotstable.msc.core.fixture;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Another core in the middle of its start or stop: holds a file lock on its first argument (creating the file and its
 * folder), prints {@code locked}, and keeps it until its input closes or it is killed.
 */
public final class LockHoldMain {
    private LockHoldMain() {
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Files.createDirectories(file.getParent());
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.lock();
            System.out.println("locked");
            System.out.flush();
            while (System.in.read() != -1) {
                // Held until the input closes.
            }
        }
    }

    /** Starts it on {@code file} and returns once the lock is held. */
    public static Process hold(Path file) throws Exception {
        Process p = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), LockHoldMain.class.getName(), file.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String line = p.inputReader().readLine();
        if (!"locked".equals(line)) {
            p.destroyForcibly();
            throw new IllegalStateException("the lock holder did not lock " + file + ": " + line);
        }
        return p;
    }
}
