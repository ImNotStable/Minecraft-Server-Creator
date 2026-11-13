package io.github.imnotstable.msc.textures;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
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
     * Write a model and, for item kinds or when {@code item} is true, an item definition named like the
     * model without its {@code item/} or {@code block/} folder ({@code block/ores/ruby} gives
     * {@code ores/ruby}), so it can be used through the {@code item_model} component. An item definition
     * that already shows another model is never replaced: that would change what an item shows unseen.
     *
     * @return the {@code item_model} value, or null when no item definition was written
     */
    public String writeModel(ResourceId model, Models.Kind kind, Map<String, ResourceId> textures, boolean item)
            throws IOException {
        JsonObject json = Models.model(kind, textures);
        if (!kind.isItem() && !item) {
            writeJson(modelPath(model), json);
            return null;
        }
        ResourceId itemId = new ResourceId(model.namespace(), withoutFolder(model.path()));
        JsonObject definition = Models.itemDefinition(model);
        Path itemFile = itemPath(itemId);
        JsonElement existing = Files.isRegularFile(itemFile) ? readJson(itemFile) : definition;
        if (!definition.equals(existing)) {
            JsonElement shown = existing instanceof JsonObject o && o.get("model") instanceof JsonObject m
                    ? m.get("model") : null;
            throw new TextureException("item " + itemId + " already shows "
                    + (shown != null && shown.isJsonPrimitive() ? "model " + shown.getAsString() : "something else")
                    + "; give this one another name");
        }
        writeJson(modelPath(model), json);
        writeJson(itemFile, definition);
        return itemId.toString();
    }

    /**
     * Write the model of {@code kind} that shows one texture, and its item definition, so the texture can be
     * used through the {@code item_model} component. A model that already shows another texture
     * ({@code block/ruby} and {@code item/ruby} share {@code item/ruby}) is never repointed.
     *
     * @return the {@code item_model} value
     */
    public String writeTextureModel(ResourceId texture, Models.Kind kind) throws IOException {
        ResourceId model = new ResourceId(texture.namespace(),
                (kind.isItem() ? "item/" : "block/") + withoutFolder(texture.path()));
        Path modelFile = modelPath(model);
        if (Files.isRegularFile(modelFile)
                && readJson(modelFile) instanceof JsonObject old && old.get("textures") instanceof JsonObject slots
                && slots.get(kind.slots().getFirst()) instanceof JsonElement shown && shown.isJsonPrimitive()
                && !shown.getAsString().equals(texture.toString())) {
            throw new TextureException("model " + model + " already shows texture " + shown.getAsString()
                    + "; give this one another name");
        }
        return writeModel(model, kind, Map.of(kind.slots().getFirst(), texture), true);
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

    /** {@code item/red/gem} and {@code block/red/gem} give {@code red/gem}; other paths stay as they are. */
    private static String withoutFolder(String path) {
        for (String folder : new String[] {"item/", "block/"}) {
            if (path.startsWith(folder)) {
                return path.substring(folder.length());
            }
        }
        return path;
    }

    /** The file's JSON, or null when it is not JSON. */
    private static JsonElement readJson(Path file) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (JsonParseException e) {
            return null;
        }
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

    /** Lowercase hex SHA-1, as {@code server.properties} and the resource pack packet want it. */
    public static String sha1(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
