package io.github.imnotstable.msc.bench.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shares the files Paper's launcher downloads on first start (the Mojang server jar, libraries and
 * the patched server) between workspaces, so each attempt does not download them again. Paper checks
 * their hashes on start and downloads again if a copy is bad, so a stale cache cannot break a run.
 */
public final class ServerFiles {

    static final List<String> FOLDERS = List.of("cache", "libraries", "versions");
    private static final String COMPLETE = ".complete";

    private ServerFiles() {
    }

    /** Copies the shared files into a workspace's server folder, if any were saved. */
    public static boolean restore(Path shared, Path serverDir) throws IOException {
        if (!Files.isRegularFile(shared.resolve(COMPLETE))) {
            return false;
        }
        for (String folder : FOLDERS) {
            if (Files.isDirectory(shared.resolve(folder))) {
                Workspaces.copyTree(shared.resolve(folder), serverDir.resolve(folder));
            }
        }
        return true;
    }

    /** Saves the files from a server that started successfully, once. */
    public static void save(Path serverDir, Path shared) throws IOException {
        if (Files.isRegularFile(shared.resolve(COMPLETE))) {
            return;
        }
        Files.createDirectories(shared);
        for (String folder : FOLDERS) {
            if (Files.isDirectory(serverDir.resolve(folder))) {
                Workspaces.copyTree(serverDir.resolve(folder), shared.resolve(folder));
            }
        }
        Files.writeString(shared.resolve(COMPLETE), "");
    }
}
