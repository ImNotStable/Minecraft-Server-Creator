package io.github.imnotstable.msc.core.backup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The world folders of a server directory: each folder directly in it that holds a world in a layout some platform
 * writes. Folders starting with a dot are MSC's own.
 * <ul>
 * <li>a {@code level.dat}: Paper's {@code world}, {@code world_nether} and {@code world_the_end}; the one
 * {@code world} with its dimensions inside on Fabric, Quilt, NeoForge and Forge;</li>
 * <li>a {@code region} folder: Minestom's {@code AnvilLoader(path)}, which writes no {@code level.dat};</li>
 * <li>a {@code DIM-1/region} or {@code DIM1/region}: a nether or end folder from before Minecraft 26.1;</li>
 * <li>a {@code dimensions/<namespace>/<path>/region}: Minestom's {@code AnvilLoader(path, key)}, which the
 * templates use, and vanilla's own dimension folders, also without a {@code level.dat} (#654).</li>
 * </ul>
 */
public final class WorldFolders {
    /** How deep under {@code dimensions/} a {@code region} folder may be: namespace, then a key path of up to 4. */
    private static final int DIMENSION_DEPTH = 6;

    /** The layouts it counts, for a refusal that finds none. */
    public static final String LAYOUTS = "none with level.dat, region/ or dimensions/<namespace>/<name>/region/";

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
                if (!name.startsWith(".") && Files.isDirectory(p) && isWorld(p)) {
                    out.add(name);
                }
            }
        }
        out.sort(null);
        return out;
    }

    /** Whether {@code dir} holds a world in one of the layouts above. */
    static boolean isWorld(Path dir) throws IOException {
        if (Files.isRegularFile(dir.resolve("level.dat")) || Files.isDirectory(dir.resolve("region"))
                || Files.isDirectory(dir.resolve("DIM-1/region")) || Files.isDirectory(dir.resolve("DIM1/region"))) {
            return true;
        }
        Path dimensions = dir.resolve("dimensions");
        if (!Files.isDirectory(dimensions)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dimensions, DIMENSION_DEPTH)) {
            return walk.anyMatch(p -> p.getFileName().toString().equals("region")
                    && dimensions.relativize(p).getNameCount() >= 3 && Files.isDirectory(p));
        }
    }
}
