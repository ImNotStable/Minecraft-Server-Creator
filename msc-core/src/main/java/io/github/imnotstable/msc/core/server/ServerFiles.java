package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts a downloaded or installed file into the server folder. The one place that creates the target folder, so no
 * setup path depends on an earlier step having made {@code run/} or its addon folder (#367).
 */
public final class ServerFiles {
    private ServerFiles() {
    }

    /**
     * Copies {@code source} to {@code dir/name}, creating {@code dir} first and replacing an older file, whole or not
     * at all: a server jar cut off halfway would not start (#417). For a folder a server scans for code, the tools
     * copy through a staging folder instead, so not even the temp file sits there.
     */
    public static Path place(Path source, Path dir, String name) throws IOException {
        return AtomicFile.copy(source, Files.createDirectories(dir).resolve(name));
    }
}
