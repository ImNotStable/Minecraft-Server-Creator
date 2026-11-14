package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.security.SecureRandom;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The one way this repository writes a file whole or not at all (ADR 0034): into a temp file beside it, moved into
 * place once complete. msc-core and msc-bench both use it, so it lives in msc-process, which both depend on. The result has the attributes a plain write would have given it: a file that was there keeps its permissions (POSIX
 * mode and group, or its ACL), and a new file gets the default ones ({@code 0666} minus the umask), never the
 * {@code 0600} of {@link Files#createTempFile} (#399).
 */
public final class AtomicFile {
    /** Writes the content to the path it is given, a fresh empty file the writer may also replace. */
    @FunctionalInterface
    public interface Writer {
        void write(Path to) throws IOException;
    }

    private static final Random RANDOM = new SecureRandom();
    /** As many links as Linux follows before ELOOP. */
    private static final int MAX_LINKS = 40;

    private AtomicFile() {
    }

    /**
     * Writes {@code target} as writing it in place would: a symbolic link there is followed and the file it names is
     * replaced, the link stays. Returns {@code target}.
     */
    public static Path write(Path target, Writer writer) throws IOException {
        put(resolveLinks(target.toAbsolutePath()), writer, null);
        return target;
    }

    /**
     * Writes {@code target} as {@link Files#copy} with {@code REPLACE_EXISTING} would: whatever is there, a symbolic
     * link included, is replaced by the new file (a file that was there keeps its permissions). Returns {@code target}.
     */
    public static Path replace(Path target, Writer writer) throws IOException {
        put(target.toAbsolutePath(), writer, null);
        return target;
    }

    /**
     * Like {@link #replace}, but the temp file is made in {@code tempDir}, not beside {@code target}: for a folder a
     * server scans, where not even a temp file may sit (#417). It is then renamed into place, which needs both on one
     * volume. Where they are not ({@link AtomicMoveNotSupportedException}), the write is made again through a temp file
     * beside the target, never by a move that copies across volumes and could leave half a file at the target.
     */
    public static Path replace(Path target, Writer writer, Path tempDir) throws IOException {
        put(target.toAbsolutePath(), writer, tempDir.toAbsolutePath());
        return target;
    }

    /** True for the name of a temp file this class makes: {@code .<target name>.<random>.part}. */
    public static boolean isTempName(String name) {
        return TEMP_NAME.matcher(name).matches();
    }

    private static final Pattern TEMP_NAME = Pattern.compile("\\..+\\.[0-9a-z]{1,13}\\.part");

    /** Copies {@code source} to {@code target} as {@link #replace} writes. */
    public static Path copy(Path source, Path target) throws IOException {
        return replace(target, to -> Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING));
    }

    /**
     * True when {@code a} and {@code b} are one directory entry's file as the file system sees it, so deleting
     * {@code a} deletes {@code b}: {@code Lobby.jar} and {@code lobby.jar} on a case-insensitive volume (macOS by
     * default, Windows), two spellings of one Unicode name, or a path through a linked folder. Never decided by
     * comparing the paths. A path that does not exist is no file, so false. When the file system cannot tell, true:
     * a caller that deletes "the other file" then keeps one rather than lose the one it just wrote (#398).
     */
    public static boolean sameFile(Path a, Path b) {
        try {
            BasicFileAttributes x = Files.readAttributes(a, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes y = Files.readAttributes(b, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (x.fileKey() != null && y.fileKey() != null) {
                // The entry itself, not what a link names: deleting a link to b leaves b.
                return x.fileKey().equals(y.fileKey());
            }
            if (x.isSymbolicLink() != y.isSymbolicLink()) {
                return false;
            }
            return Files.isSameFile(a, b);
        } catch (NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Writes {@code file} through a temp file in {@code tempDir}, or beside {@code file} when that is null. */
    private static void put(Path file, Writer writer, Path tempDir) throws IOException {
        Path dir = Files.createDirectories(file.getParent());
        boolean beside = tempDir == null || tempDir.equals(dir);
        Path temp = createTemp(beside ? dir : Files.createDirectories(tempDir), file.getFileName().toString());
        try {
            writer.write(temp);
            // After the writer: one that recreates the file (Files.copy, ImageIO) would drop what was set before.
            keepAttributes(file, temp);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                if (!beside) {
                    // Another volume: a plain move would copy into the target itself. Write beside it instead.
                    Files.deleteIfExists(temp);
                    put(file, writer, null);
                    return;
                }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** A new empty file beside the target, created as a plain write creates one: default permissions, no 0600. */
    private static Path createTemp(Path dir, String name) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Path temp = dir.resolve("." + name + "." + Long.toUnsignedString(RANDOM.nextLong(), 36) + ".part");
            try {
                return Files.createFile(temp);
            } catch (FileAlreadyExistsException e) {
                if (attempt >= 100) {
                    throw e;
                }
            }
        }
    }

    /** The permissions of the regular file being replaced, if there is one, go to the file replacing it. */
    private static void keepAttributes(Path existing, Path temp) throws IOException {
        if (!Files.isRegularFile(existing, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(temp, PosixFileAttributeView.class);
        if (posix != null) {
            PosixFileAttributes was = Files.readAttributes(existing, PosixFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            try {
                // Best effort: only the file's owner in that group may set it; the mode below never depends on it.
                posix.setGroup(was.group());
            } catch (IOException e) {
                // The temp keeps the creator's group, as a new file would.
            }
            posix.setPermissions(was.permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(temp, AclFileAttributeView.class);
        AclFileAttributeView wasAcl = Files.getFileAttributeView(existing, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl != null && wasAcl != null) {
            try {
                acl.setAcl(wasAcl.getAcl());
            } catch (IOException e) {
                // The temp keeps the ACL it inherited from its folder, as a new file would.
            }
        }
    }

    /** The file {@code path} names after following symbolic links, so a write replaces it and keeps the link. */
    private static Path resolveLinks(Path path) throws IOException {
        Path file = path;
        for (int i = 0; i < MAX_LINKS; i++) {
            if (!Files.isSymbolicLink(file)) {
                return file;
            }
            file = file.resolveSibling(Files.readSymbolicLink(file));
        }
        throw new FileSystemException(path.toString(), null, "too many levels of symbolic links");
    }
}
