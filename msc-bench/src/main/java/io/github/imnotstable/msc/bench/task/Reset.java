package io.github.imnotstable.msc.bench.task;

import io.github.imnotstable.msc.bench.Quoted;
import io.github.imnotstable.msc.bench.UserJson;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

/**
 * What the bench puts back after the agent finishes and before the checks run: task.json's
 * {@code resetBeforeChecks}. A check must judge the agent's work, not what the agent's own testing left behind (#495):
 * a {@code benchmotd set} tried while testing stays in the plugin's saved config, and the plugin would then never
 * show its shipped default to the checks.
 *
 * @param delete workspace paths, relative and with {@code /} between folders, deleted (a folder with everything in
 *               it) when present
 */
public record Reset(List<String> delete) {

    /** The keys of {@code resetBeforeChecks}. */
    public static final UserJson.Keys KEYS = UserJson.Keys.of("delete");

    /** No reset: the checks see the workspace as the agent left it. */
    public static final Reset NONE = new Reset(List.of());

    public Reset {
        delete = List.copyOf(delete);
    }

    public boolean isEmpty() {
        return delete.isEmpty();
    }

    /** Reads task.json's {@code resetBeforeChecks} object, or {@link #NONE} when it is absent. */
    static Reset parse(UserJson json) {
        if (json == null) {
            return NONE;
        }
        List<String> paths = json.exactStrings("delete");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < paths.size(); i++) {
            out.add(insideWorkspace(paths.get(i), json.key("delete") + "[" + i + "]"));
        }
        return new Reset(out);
    }

    /** {@code path} normalized, refused unless it names something strictly inside the workspace. */
    private static String insideWorkspace(String path, String key) {
        String slashes = path.replace('\\', '/');
        String refused = key + ": " + Quoted.text(path) + " ";
        if (slashes.isBlank()) {
            throw new IllegalArgumentException(refused + "is blank; give a path inside the workspace");
        }
        if (slashes.startsWith("/") || slashes.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(refused + "is absolute; give a path relative to the workspace");
        }
        Path normal;
        try {
            normal = Path.of(slashes).normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(refused + "is not a path: " + Quoted.message(e.getReason()));
        }
        String written = normal.toString().replace('\\', '/');
        if (written.isEmpty() || written.equals("..") || written.startsWith("../")) {
            throw new IllegalArgumentException(refused + "is not inside the workspace; give a path inside it");
        }
        return written;
    }

    /**
     * Deletes each path under {@code workspace}. A path that is missing is fine. One that leads out of the workspace
     * through a link the agent made is refused before anything is deleted; a link itself is deleted, never followed.
     */
    public void apply(Path workspace) throws IOException {
        Path root = workspace.toRealPath();
        List<Path> targets = new ArrayList<>();
        for (String path : delete) {
            Path target = workspace.resolve(path);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            Path parent = target.getParent().toRealPath();
            if (!parent.startsWith(root)) {
                throw new IOException("resetBeforeChecks: " + path + " leads outside the workspace (through a link "
                        + "to " + parent + "); nothing was deleted");
            }
            targets.add(parent.resolve(target.getFileName()));
        }
        for (Path target : targets) {
            deleteTree(target);
        }
    }

    private static void deleteTree(Path target) throws IOException {
        if (leadsElsewhere(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (leadsElsewhere(dir)) {
                    Files.delete(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Whether {@code path}, whose parent is a real path, is a link of any kind: one the real path does not name. That
     * covers a Windows directory junction too, which Java reports as a plain directory and {@code walkFileTree} would
     * enter, deleting what it points to.
     */
    private static boolean leadsElsewhere(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            return true;
        }
        return !sameEntry(path, path.toRealPath());
    }

    /**
     * Whether {@code real} names the entry {@code expected} itself: the same folder, and the same name up to case, as
     * the file systems that ignore case (Windows, macOS) give it back.
     */
    static boolean sameEntry(Path expected, Path real) {
        return real.getParent() != null && real.getParent().equals(expected.getParent())
                && real.getFileName().toString().equalsIgnoreCase(expected.getFileName().toString());
    }
}
