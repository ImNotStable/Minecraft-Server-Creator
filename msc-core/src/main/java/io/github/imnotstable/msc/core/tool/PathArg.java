package io.github.imnotstable.msc.core.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;

/**
 * The one way a tool turns a path argument into a {@link Path}: resolved against the workspace, with a one-line
 * refusal naming the argument for anything the file system cannot hold (NUL and other control characters, a name the
 * platform refuses, a name longer than {@link #MAX_NAME_BYTES}) and for a path outside the workspace when the rule
 * keeps it inside. Every tool reads its path arguments through {@link Args#path}, so no tool can forget a check.
 *
 * @param base   the workspace, which relative paths are resolved against
 * @param inside true when the path must lie inside {@code base} (after {@code ..} is applied); false for an argument
 *               that names a folder anywhere, such as msc_workspace's path
 */
public record PathArg(Path base, boolean inside) {
    /** Longest file or folder name, in UTF-8 bytes, that common file systems (ext4, NTFS, APFS) all hold. */
    public static final int MAX_NAME_BYTES = 255;

    public PathArg {
        base = base.toAbsolutePath().normalize();
    }

    /** Paths that must stay inside the workspace: every path argument but msc_workspace's. */
    public static PathArg inside(Path workspace) {
        return new PathArg(workspace, true);
    }

    /** A folder anywhere; relative paths are relative to the workspace. */
    public static PathArg anywhere(Path workspace) {
        return new PathArg(workspace, false);
    }

    /** {@code value} of argument {@code name} as an absolute, normalized path, or a refusal naming the argument. */
    public Path resolve(String name, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                throw new ToolException(String.format("argument %s has a control character (U+%04X); give a plain path",
                        name, (int) c));
            }
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException e) {
            throw new ToolException("argument " + name + " is not a path this file system can hold: " + e.getReason());
        }
        checkNames(path, "argument " + name);
        Path resolved = base.resolve(path).normalize();
        if (inside && !resolved.startsWith(base)) {
            throw new ToolException("argument " + name + " must be inside the workspace " + base + ", not " + resolved
                    + ". Give a path in it, or switch with msc_workspace.");
        }
        return resolved;
    }

    /**
     * Refuses a path with a file or folder name longer than {@link #MAX_NAME_BYTES}, which no write could create; the
     * check runs before anything is written, so a refused name leaves no empty folders. Returns {@code path}.
     *
     * @param what how the message names it, such as "argument file" or "texture"
     */
    public static Path checkNames(Path path, String what) {
        for (Path part : path) {
            String text = part.toString();
            int bytes = text.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_NAME_BYTES) {
                throw new ToolException(what + " has a name of " + bytes + " bytes (" + text.substring(0, 24)
                        + "...); file systems hold at most " + MAX_NAME_BYTES + ". Use a shorter name.");
            }
        }
        return path;
    }

    /** Creates {@code dir} and the folders above it, or refuses in one line naming the folder and the reason. */
    public static Path createFolders(Path dir, String what) {
        try {
            return Files.createDirectories(dir);
        } catch (IOException e) {
            // The folder is named once: a file-system message without the path it repeats.
            String why = e instanceof FileSystemException f && f.getClass() == FileSystemException.class
                    && f.getReason() != null ? f.getReason() : reason(e);
            throw new ToolException(what + " " + dir + " could not be created: " + why);
        }
    }

    /** One readable line for why a file operation failed. */
    public static String reason(IOException e) {
        return switch (e) {
            case DirectoryNotEmptyException d -> "a folder that is not empty is in the way: " + d.getFile();
            case NoSuchFileException n -> "no such file or folder: " + n.getFile();
            case NotDirectoryException n -> "not a folder: " + n.getFile();
            case FileAlreadyExistsException f -> "already exists: " + f.getFile();
            case AccessDeniedException a -> "access denied: " + a.getFile();
            case FileSystemException f -> f.getMessage();
            default -> e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        };
    }
}
