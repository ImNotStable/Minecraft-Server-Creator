package io.github.imnotstable.msc.bench.check;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A task folder copied into a workspace for as long as one check runs (#603), such as the hidden tests of a
 * {@code command} check's {@code addFiles}. {@link #close} takes them out again: it deletes each file and folder the
 * copy made and puts back each file it replaced, byte for byte. So after the checks the workspace holds the agent's
 * work only, and a later {@code check} of a kept workspace meets the same files the run's checks met. {@link #close}
 * also undoes a copy that failed part way.
 */
final class AddedFiles implements AutoCloseable {

    private final Path workspace;
    /** Where the replaced files wait, under their paths in the workspace; null until a file is replaced. */
    private Path saved;
    /** The files and the topmost folders the copy made, in the order it made them. */
    private final List<Path> made = new ArrayList<>();
    /** The workspace paths of the files the copy replaced. */
    private final List<Path> replaced = new ArrayList<>();

    private AddedFiles(Path workspace) {
        this.workspace = workspace;
    }

    /** Copies the contents of {@code from} into {@code workspace}, replacing files that are there. */
    static AddedFiles copy(Path from, Path workspace) throws IOException {
        AddedFiles added = new AddedFiles(workspace);
        try {
            added.copyTree(from);
        } catch (IOException | RuntimeException e) {
            try {
                added.close();
            } catch (IOException undo) {
                e.addSuppressed(undo);
            }
            throw e;
        }
        return added;
    }

    private void copyTree(Path from) throws IOException {
        List<Path> sources;
        try (Stream<Path> paths = Files.walk(from)) {
            sources = paths.toList();
        }
        for (Path source : sources) {
            String relative = from.relativize(source).toString();
            if (relative.isEmpty()) {
                continue;
            }
            Path target = workspace.resolve(relative);
            if (Files.isDirectory(source)) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(target);
                    if (!insideMade(target)) {
                        made.add(target);
                    }
                }
            } else if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (saved == null) {
                    saved = Files.createTempDirectory("msc-bench-replaced");
                }
                Path keep = saved.resolve(relative);
                Files.createDirectories(keep.getParent());
                Files.copy(target, keep, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                replaced.add(target);
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.copy(source, target);
                if (!insideMade(target)) {
                    made.add(target);
                }
            }
        }
    }

    /** Whether {@code path} lies in a folder the copy made, which {@link #close} deletes whole. */
    private boolean insideMade(Path path) {
        return made.stream().anyMatch(m -> !m.equals(path) && path.startsWith(m));
    }

    /**
     * Deletes what the copy made, with anything put inside its folders since, and puts back what it replaced. Goes on
     * past a path it cannot undo and throws the first problem at the end, so one locked file leaves no more behind
     * than it must.
     */
    @Override
    public void close() throws IOException {
        IOException problem = null;
        for (Path path : made.reversed()) {
            try {
                deleteTree(path);
            } catch (IOException e) {
                problem = first(problem, e);
            }
        }
        made.clear();
        for (Path target : replaced) {
            try {
                Path keep = saved.resolve(workspace.relativize(target).toString());
                Files.copy(keep, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
            } catch (IOException e) {
                problem = first(problem, e);
            }
        }
        replaced.clear();
        if (saved != null) {
            try {
                deleteTree(saved);
                saved = null;
            } catch (IOException e) {
                problem = first(problem, e);
            }
        }
        if (problem != null) {
            throw problem;
        }
    }

    private static IOException first(IOException problem, IOException e) {
        if (problem == null) {
            return e;
        }
        problem.addSuppressed(e);
        return problem;
    }

    /** Deletes {@code path} and, when it is a folder, everything in it, without following links. */
    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
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
}
