package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.process.AtomicFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The folders a server scans for code to load ({@code plugins/}, {@code mods/}, Minestom's {@code libs/}) and how
 * msc-core writes into them (#417). Rule: nothing msc-core writes there may be loadable, and nothing may be left there
 * by a call that ended halfway. So a jar is written to a temp file in {@link #staging}, outside every scan folder but
 * on the server folder's volume, and renamed into place; a jar being removed is first renamed into {@link #staging}
 * too. Only where the staging folder is on another volume does a temp or aside file sit in the scan folder, under a
 * name no loader loads ({@link Loader}); dev_cycle and server_start remove what an interrupted call left, in both
 * places ({@link #sweep}).
 */
final class ScanFolders {
    /** The suffix of a jar set aside while it is removed. */
    static final String ASIDE = ".msc-removing";

    private ScanFolders() {
    }

    /**
     * What each loader loads from its scan folder, as its own code decides, read with javap from the jars (Paper from
     * its source, as its server jar is not in the Gradle cache). Our names must load in none of them, whatever loader
     * the workspace runs (a Fabric mod also runs on Quilt), and must not rely on any of them.
     */
    enum Loader {
        /**
         * Paper (paper-server {@code DirectoryProviderSource.isValidFile}, {@code FileProviderSource.prepareContext}):
         * a regular file (links followed) whose name ends in {@code .jar}. Its dot check is {@code Path.startsWith(".")},
         * which compares path elements and never matches, so {@code .x.jar} loads too.
         */
        PAPER,
        /**
         * Fabric Loader 0.20.1 ({@code DirectoryModCandidateFinder.isValidFile}): a regular file, not hidden, whose
         * name ends in {@code .jar} and does not start with a dot.
         */
        FABRIC,
        /**
         * Quilt Loader 0.30.2 ({@code QuiltPluginManagerImpl.scanModFile}): every entry whose name neither starts with
         * a dot nor ends in {@code .disabled}. A folder is scanned as a mod, any other file is opened as a zip whatever
         * its name, so {@code x.jar.msc-removing} or {@code x.zip} loads.
         */
        QUILT,
        /** NeoForge FML loader 12.0.12 ({@code ModsFolderLocator}): a file whose name ends in {@code .jar}. */
        NEOFORGE,
        /** Forge fmlloader 26.3-66.1.5 ({@code ModsFolderLocator}): a file whose name ends in {@code .jar}. */
        FORGE;

        boolean loads(String name, boolean folder) {
            return switch (this) {
                case PAPER, NEOFORGE, FORGE -> !folder && name.endsWith(".jar");
                case FABRIC -> !folder && name.endsWith(".jar") && !name.startsWith(".");
                case QUILT -> !name.startsWith(".") && !name.endsWith(".disabled");
            };
        }
    }

    /**
     * The scan folders of every platform in {@code serverDir}, and Minestom's {@code libs/}: a workspace that switched
     * platform still has the others' folders, and what is left there loads once it switches back.
     */
    static List<Path> all(Path serverDir) {
        Set<Path> folders = new LinkedHashSet<>();
        for (Platform p : Platform.values()) {
            if (p.addonDir() != null) {
                folders.add(serverDir.resolve(p.addonDir()));
            }
        }
        folders.add(serverDir.resolve("libs"));
        return List.copyOf(folders);
    }

    /** Where temp and aside files go: the server folder's own {@code .msc/staging}, which no server scans. */
    static Path staging(Path serverDir) {
        return serverDir.resolve(".msc").resolve("staging");
    }

    /**
     * Copies {@code source} to {@code target} in a scan folder, whole or not at all, through a temp file in
     * {@link #staging} (beside the target, under a name no loader loads, only when staging is on another volume).
     */
    static void copy(Path source, Path target, Path serverDir) throws IOException {
        AtomicFile.replace(target, to -> {
            Steps.killPoint("temp created for " + target.getFileName());
            Files.copy(source, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Steps.killPoint("temp written for " + target.getFileName());
        }, staging(serverDir));
        Steps.killPoint("moved into place: " + target.getFileName());
    }

    /** Where {@code earlier} goes while it is removed: staging, else (another volume) a dot name beside it. */
    static Path aside(Path earlier, Path serverDir, boolean beside) {
        String name = earlier.getFileName() + ASIDE;
        return beside ? earlier.resolveSibling("." + name) : staging(serverDir).resolve(name);
    }

    /** True for a name only an interrupted call of ours leaves in a scan folder. */
    static boolean leftover(String name) {
        return name.endsWith(ASIDE) || AtomicFile.isTempName(name);
    }

    /**
     * Removes what interrupted calls left: everything in {@link #staging}, and in each of {@code folders} the files
     * {@link #leftover} names (also {@code x.jar.msc-removing}, the aside name of cores before #417). Returns what
     * was removed.
     */
    static List<Path> sweep(Path serverDir, List<Path> folders) throws IOException {
        List<Path> removed = new ArrayList<>();
        Path staging = staging(serverDir);
        if (Files.isDirectory(staging, LinkOption.NOFOLLOW_LINKS)) {
            for (Path p : list(staging)) {
                Steps.killPoint("sweep " + p.getFileName());
                if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    Scaffold.deleteTree(p);
                } else {
                    Files.deleteIfExists(p);
                }
                removed.add(p);
            }
        }
        for (Path folder : folders) {
            if (!Files.isDirectory(folder)) {
                continue;
            }
            for (Path p : list(folder)) {
                if (leftover(p.getFileName().toString()) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    Steps.killPoint("sweep " + p.getFileName());
                    Files.deleteIfExists(p);
                    removed.add(p);
                }
            }
        }
        return removed;
    }

    /**
     * Removes the temp files an interrupted {@link AtomicFile} write of {@code dir/name} left beside it (a file outside
     * the scan folders, written through a temp file beside it). Returns what was removed.
     */
    static List<Path> sweepTemps(Path dir, String name) throws IOException {
        List<Path> removed = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return removed;
        }
        for (Path p : list(dir)) {
            String n = p.getFileName().toString();
            if (AtomicFile.isTempFor(name, n)
                    && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                Steps.killPoint("sweep " + n);
                Files.deleteIfExists(p);
                removed.add(p);
            }
        }
        return removed;
    }

    private static List<Path> list(Path dir) throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.toList();
        }
    }
}
