package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Names and creates a run's own folder. Runs started side by side share the runs folder (the default
 * {@code ~/.msc/bench-runs} is one for all of them), and two started in the same second got the same name: both wrote
 * one {@code results.jsonl} and one {@code report.md} (#621). So a folder is only ever created, never reused: the name
 * is the start second, {@code yyyyMMdd-HHmmss}, and when anything by that name already exists, the next of
 * {@code -2}, {@code -3}... that does not. The creation itself claims the name, so two processes can never both get it.
 */
public final class RunFolder {

    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private RunFolder() {
    }

    /** Creates and returns a new run folder in {@code runsDir} (created if missing) for a run started {@code now}. */
    public static Path claim(Path runsDir, LocalDateTime now) throws IOException {
        Files.createDirectories(runsDir);
        String base = now.format(NAME);
        for (int n = 1; ; n++) {
            Path dir = runsDir.resolve(n == 1 ? base : base + "-" + n);
            try {
                return Files.createDirectory(dir);
            } catch (FileAlreadyExistsException taken) {
                // another run's folder, or anything else by that name: try the next
            }
        }
    }
}
