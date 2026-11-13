package io.github.imnotstable.msc.renderer;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;

/**
 * Textures from resource packs: folders or zip/jar files that contain {@code assets/<namespace>/textures/}.
 * Earlier roots win, so put the project's generated pack before a vanilla client jar. Files that are
 * missing or unreadable count as "no texture".
 */
public final class PackTextures implements TextureSource {
    private final List<Path> roots;

    public PackTextures(List<Path> roots) {
        this.roots = List.copyOf(roots);
    }

    @Override
    public Optional<BufferedImage> texture(String namespace, String path) {
        if (namespace.contains("..") || path.contains("..")) {
            return Optional.empty();
        }
        String entry = "assets/" + namespace + "/textures/" + path + ".png";
        for (Path root : roots) {
            try {
                BufferedImage image = Files.isDirectory(root) ? fromFolder(root, entry) : fromZip(root, entry);
                if (image != null) {
                    return Optional.of(image);
                }
            } catch (IOException e) {
                // Unreadable pack file: treat as missing and try the next root.
            }
        }
        return Optional.empty();
    }

    /**
     * Changes when a file below a folder root's {@code assets/} or a zip root is added, removed or modified. Walks
     * the folder roots, which hold the project's generated pack and stay small.
     */
    @Override
    public long stamp() {
        long h = 1;
        for (Path root : roots) {
            try {
                if (Files.isDirectory(root)) {
                    Path assets = root.resolve("assets");
                    if (Files.isDirectory(assets)) {
                        try (Stream<Path> files = Files.walk(assets)) {
                            for (Path file : (Iterable<Path>) files::iterator) {
                                h = 31 * h + file.hashCode();
                                h = 31 * h + Files.getLastModifiedTime(file).toMillis();
                                h = 31 * h + Files.size(file);
                            }
                        }
                    }
                } else if (Files.isRegularFile(root)) {
                    h = 31 * h + Files.getLastModifiedTime(root).toMillis();
                    h = 31 * h + Files.size(root);
                }
            } catch (IOException | UncheckedIOException e) {
                // A file that vanished mid-walk: the next call sees a settled folder.
                h = 31 * h + e.getClass().hashCode();
            }
            h = 31 * h + 7;
        }
        return h;
    }

    private static BufferedImage fromFolder(Path root, String entry) throws IOException {
        Path file = root.resolve(entry);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return ImageIO.read(in);
        }
    }

    private static BufferedImage fromZip(Path zip, String entry) throws IOException {
        if (!Files.isRegularFile(zip)) {
            return null;
        }
        try (ZipFile file = new ZipFile(zip.toFile())) {
            ZipEntry e = file.getEntry(entry);
            if (e == null) {
                return null;
            }
            try (InputStream in = file.getInputStream(e)) {
                return ImageIO.read(in);
            }
        }
    }
}
