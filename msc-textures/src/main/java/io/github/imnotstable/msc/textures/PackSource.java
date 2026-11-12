package io.github.imnotstable.msc.textures;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A resource pack's source folder: textures, animations, models and item definitions written to the
 * right paths, and a deterministic zip (same files give the same bytes and SHA-1).
 */
public final class PackSource {
    private static final long FIXED_TIME = 946684800000L;

    /** A zipped pack. */
    public record Zipped(Path zip, String sha1, int files, long bytes) {
    }

    private final Path root;

    public PackSource(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public Path texturePath(ResourceId id) {
        return assets(id).resolve("textures").resolve(id.path() + ".png");
    }

    public Path modelPath(ResourceId id) {
        return assets(id).resolve("models").resolve(id.path() + ".json");
    }

    public Path itemPath(ResourceId id) {
        return assets(id).resolve("items").resolve(id.path() + ".json");
    }

    private Path assets(ResourceId id) {
        return root.resolve("assets").resolve(id.namespace());
    }

    public boolean hasTexture(ResourceId id) {
        return Files.isRegularFile(texturePath(id));
    }

    public Texture readTexture(ResourceId id) throws IOException {
        Path file = texturePath(id);
        if (!Files.isRegularFile(file)) {
            throw new TextureException("no texture " + id + " in the pack (vanilla textures are not in it)");
        }
        return Texture.readPng(Files.readAllBytes(file));
    }

    /** Write a still texture, removing any animation file left from an earlier version. */
    public Path writeTexture(ResourceId id, Texture texture) throws IOException {
        Path file = texturePath(id);
        Files.createDirectories(file.getParent());
        Files.write(file, texture.toPng());
        Files.deleteIfExists(mcmetaPath(file));
        return file;
    }

    public Path writeAnimation(ResourceId id, Animation animation) throws IOException {
        Path file = texturePath(id);
        Files.createDirectories(file.getParent());
        Files.write(file, animation.strip().toPng());
        writeJson(mcmetaPath(file), animation.mcmeta());
        return file;
    }

    private static Path mcmetaPath(Path png) {
        return png.resolveSibling(png.getFileName() + ".mcmeta");
    }

    /**
     * Write a model and, for item kinds or when {@code item} is true, an item definition with the same
     * name, so it can be used through the {@code item_model} component.
     *
     * @return the {@code item_model} value, or null when no item definition was written
     */
    public String writeModel(ResourceId model, Models.Kind kind, Map<String, ResourceId> textures, boolean item)
            throws IOException {
        writeJson(modelPath(model), Models.model(kind, textures));
        if (!kind.isItem() && !item) {
            return null;
        }
        ResourceId itemId = new ResourceId(model.namespace(), model.name());
        writeJson(itemPath(itemId), Models.itemDefinition(model));
        return itemId.toString();
    }

    public void writePackMeta(int packFormat, String description) throws IOException {
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("min_format", packFormat);
        pack.addProperty("max_format", Math.max(packFormat, 9999));
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        writeJson(root.resolve("pack.mcmeta"), meta);
    }

    private static void writeJson(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
    }

    /** Zip every file under the root, sorted, with fixed timestamps. */
    public Zipped zip(Path out) throws IOException {
        if (!Files.isRegularFile(root.resolve("pack.mcmeta"))) {
            throw new TextureException("pack.mcmeta is missing; write it with writePackMeta first");
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setLastModifiedTime(FileTime.fromMillis(FIXED_TIME));
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        byte[] data = bytes.toByteArray();
        if (out.getParent() != null) {
            Files.createDirectories(out.getParent());
        }
        Files.write(out, data);
        return new Zipped(out, sha1(data), files.size(), data.length);
    }

    static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
