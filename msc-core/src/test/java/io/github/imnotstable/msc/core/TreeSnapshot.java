package io.github.imnotstable.msc.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/** A folder's whole content, so a test can assert that a failed tool call left it exactly as it was. */
final class TreeSnapshot {
    private TreeSnapshot() {
    }

    /** Each entry under {@code root} by relative path: a file's bytes as hex, a folder as {@code dir}; empty when absent. */
    static Map<String, String> of(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> tree = Files.walk(root)) {
            for (Path p : tree.toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // Windows locks are mandatory: a lock file another process holds can't be read, only sized.
                out.put(rel, Files.isDirectory(p) ? "dir" : rel.endsWith(".lock") ? "lock of " + Files.size(p)
                        : HexFormat.of().formatHex(Files.readAllBytes(p)));
            }
        }
        return out;
    }
}
