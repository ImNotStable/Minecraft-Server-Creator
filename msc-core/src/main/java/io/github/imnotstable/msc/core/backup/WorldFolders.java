package io.github.imnotstable.msc.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The world folders of a server directory: each folder directly in it that holds a {@code level.dat} (Paper's
 * {@code world}, {@code world_nether} and {@code world_the_end}; the one {@code world} with its dimensions inside on
 * Fabric, Quilt, NeoForge and Forge) or a {@code region} folder (Minestom's {@code AnvilLoader}, which writes no
 * {@code level.dat}). Folders starting with a dot are MSC's own.
 */
public final class WorldFolders {
    private WorldFolders() {
    }

    /** The world folders' names, sorted. */
    public static List<String> in(Path serverDir) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(serverDir)) {
            return out;
        }
        try (Stream<Path> children = Files.list(serverDir)) {
            for (Path p : children.toList()) {
                String name = p.getFileName().toString();
                if (!name.startsWith(".") && Files.isDirectory(p)
                        && (Files.isRegularFile(p.resolve("level.dat")) || Files.isDirectory(p.resolve("region")))) {
                    out.add(name);
                }
            }
        }
        out.sort(null);
        return out;
    }
}
