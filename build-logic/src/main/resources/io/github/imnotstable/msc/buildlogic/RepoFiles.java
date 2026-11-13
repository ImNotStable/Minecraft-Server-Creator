package io.github.imnotstable.msc.buildlogic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The repository files outside this module's sources that the tests may read: exactly the named sets the build
 * declares with {@code repoInputs.set(...)} (msc.repo-inputs), which are also the test task's inputs, so a change to
 * any of them reruns the tests (#129, #355). Generated from build-logic; do not edit the copy under build/.
 */
public final class RepoFiles {
    private static final Map<String, List<String>> SETS = new LinkedHashMap<>();
    private static final Path ROOT = load();

    private RepoFiles() {
    }

    /** The files of set {@code name}, as sorted repository-relative paths with forward slashes. */
    public static List<String> list(String name) {
        List<String> files = SETS.get(name);
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("no repoInputs set '" + name + "' with files; declared: " + SETS.keySet());
        }
        return files;
    }

    /** The file {@code relative} (repository-relative), which set {@code name} must declare. */
    public static Path path(String name, String relative) {
        if (!list(name).contains(relative)) {
            throw new IllegalArgumentException(relative + " is not in repoInputs set '" + name + "'");
        }
        return ROOT.resolve(relative);
    }

    /** The folder {@code relative} (repository-relative), under which set {@code name} must declare files. */
    public static Path dir(String name, String relative) {
        String prefix = relative.endsWith("/") ? relative : relative + "/";
        if (list(name).stream().noneMatch(f -> f.startsWith(prefix))) {
            throw new IllegalArgumentException(relative + " has no files in repoInputs set '" + name + "'");
        }
        return ROOT.resolve(relative);
    }

    private static Path load() {
        String manifest = System.getProperty("msc.repoFiles");
        if (manifest == null) {
            throw new IllegalStateException("msc.repoFiles is not set: run the tests through Gradle");
        }
        try {
            Path root = null;
            for (String line : Files.readAllLines(Path.of(manifest))) {
                int tab = line.indexOf('\t');
                String key = line.substring(0, tab);
                String value = line.substring(tab + 1);
                if (key.equals("root")) {
                    root = Path.of(value);
                } else {
                    SETS.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
                }
            }
            if (root == null) {
                throw new IllegalStateException(manifest + " names no root");
            }
            SETS.replaceAll((k, v) -> List.copyOf(v));
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
