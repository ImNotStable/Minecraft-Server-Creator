package io.github.imnotstable.msc.core.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Puts a downloaded or installed file into the server folder. The one place that creates the target folder, so no
 * setup path depends on an earlier step having made {@code run/} or its addon folder (#367).
 */
public final class ServerFiles {
    private ServerFiles() {
    }

    /** Copies {@code source} to {@code dir/name}, creating {@code dir} first and replacing an older file. */
    public static Path place(Path source, Path dir, String name) throws IOException {
        Path target = Files.createDirectories(dir).resolve(name);
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
