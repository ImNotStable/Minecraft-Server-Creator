package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * A file only its owner can read, for text holding a secret: the attempt's {@code mcp.json} carries the core's token
 * (ADR 0043), and on a shared machine the run folder may be readable by other users.
 */
final class PrivateFile {
    private PrivateFile() {
    }

    /** Writes {@code text}, owner read and write only where the file system has POSIX permissions. */
    static void write(Path file, String text) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.deleteIfExists(file);
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
