package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.PathArg;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A file a tool writes: checked before the call changes anything ({@link #check}), then written whole or not at all
 * ({@link #write}), so a failed write never leaves half a file where the old one was.
 */
final class OutputFile {
    /** Writes the content to the path it is given. */
    @FunctionalInterface
    interface Writer {
        void write(Path to) throws IOException;
    }

    private OutputFile() {
    }

    /**
     * Refuses {@code target} when it cannot be written: it is a folder, a part of its path is a file, the nearest
     * existing folder or the file itself is read-only, a name in it is too long ({@link PathArg#checkNames}). Returns {@code target}.
     *
     * @param what how the message names it, such as "file" or "server.properties"
     */
    static Path check(Path target, String what) {
        Path file = PathArg.checkNames(target.toAbsolutePath().normalize(), what);
        if (Files.isDirectory(file)) {
            throw new ToolException(what + " " + file + " is a folder; give a file path");
        }
        Path existing = file.getParent();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw new ToolException(what + " " + file + " has no existing folder above it");
        }
        if (!Files.isDirectory(existing)) {
            throw new ToolException(what + " " + file + " cannot be written: " + existing + " is a file, not a folder");
        }
        if (!Files.isWritable(existing) || Files.exists(file) && !Files.isWritable(file)) {
            throw new ToolException(what + " " + file + " is not writable");
        }
        return target;
    }

    /** Refuses {@code dir} when it cannot be a folder to write in: a file is there or on its path, or it is read-only. */
    static Path checkFolder(Path dir, String what) {
        Path folder = PathArg.checkNames(dir.toAbsolutePath().normalize(), what);
        if (Files.exists(folder) && !Files.isDirectory(folder)) {
            throw new ToolException(what + " " + folder + " is a file, not a folder");
        }
        Path existing = folder;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null || !Files.isDirectory(existing)) {
            throw new ToolException(what + " " + folder + " cannot be created: " + existing + " is a file, not a folder");
        }
        if (!Files.isWritable(existing)) {
            throw new ToolException(what + " " + folder + " is not writable");
        }
        return dir;
    }

    /**
     * Writes {@code target} through a temp file beside it, moved into place once complete, as writing it in place
     * would: a symbolic link is followed, a file that was there keeps its permissions, a new one gets the default ones.
     */
    static Path write(Path target, Writer writer) throws IOException {
        return AtomicFile.write(target, writer::write);
    }

    /**
     * Like {@link #write}, but as {@link Files#copy} with {@code REPLACE_EXISTING} or {@code ImageIO.write} would: a
     * symbolic link at {@code target} is replaced by the new file.
     */
    static Path replace(Path target, Writer writer) throws IOException {
        return AtomicFile.replace(target, writer::write);
    }

    /** Copies {@code source} to {@code target}, whole or not at all, as {@link #replace} writes. */
    static Path copy(Path source, Path target) throws IOException {
        return AtomicFile.copy(source, target);
    }
}
