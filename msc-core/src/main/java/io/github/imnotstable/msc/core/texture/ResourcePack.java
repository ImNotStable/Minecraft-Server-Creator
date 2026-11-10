package io.github.imnotstable.msc.core.texture;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * The project's resource pack: source files under {@code <workspace>/.msc/pack}, zipped on demand and
 * served over HTTP so the server can send it to players.
 */
public final class ResourcePack implements AutoCloseable {
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");

    /** A built pack. */
    public record Built(Path zip, String sha1, String url, int files, long bytes) {
    }

    /** A parsed texture id. {@code path} is below {@code textures/}, without {@code .png}. */
    public record TextureId(String namespace, String path) {
        public String name() {
            return path.substring(path.lastIndexOf('/') + 1);
        }

        @Override
        public String toString() {
            return namespace + ":" + path;
        }
    }

    private final Path root;
    private final Path outDir;
    private final String host;
    private final int packFormat;
    private HttpServer server;
    private volatile byte[] served = new byte[0];
    private volatile String servedSha1 = "";

    public ResourcePack(Path root, Path outDir, String host, int packFormat) {
        this.root = root;
        this.outDir = outDir;
        this.host = host;
        this.packFormat = packFormat;
    }

    /** {@code ns:block/ruby_ore} or {@code block/ruby_ore} (namespace {@code msc}). */
    public static TextureId id(String text) {
        String t = text.trim();
        if (t.endsWith(".png")) {
            t = t.substring(0, t.length() - 4);
        }
        int colon = t.indexOf(':');
        String ns = colon < 0 ? "msc" : t.substring(0, colon);
        String path = colon < 0 ? t : t.substring(colon + 1);
        if (!NAMESPACE.matcher(ns).matches() || !PATH.matcher(path).matches() || path.contains("..")) {
            throw new ToolException("bad texture name \"" + text + "\"; use lowercase like block/ruby_ore or myplugin:item/ruby");
        }
        return new TextureId(ns, path);
    }

    private Path texturePath(TextureId id) {
        return root.resolve("assets").resolve(id.namespace()).resolve("textures").resolve(id.path() + ".png");
    }

    public BufferedImage readTexture(TextureId id) throws IOException {
        Path file = texturePath(id);
        if (!Files.exists(file)) {
            throw new ToolException("no texture " + id + " in the pack");
        }
        return ImageIO.read(file.toFile());
    }

    /**
     * Write a texture and, for {@code item} or {@code block}, the model and item definition that make
     * it usable through the {@code item_model} component.
     *
     * @param model {@code item}, {@code block} or {@code none}
     * @return what to put in {@code item_model}, or null when no model was written
     */
    public String writeTexture(TextureId id, BufferedImage image, String model) throws IOException {
        Path file = texturePath(id);
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
        if (model.equals("none")) {
            return null;
        }
        Path assets = root.resolve("assets").resolve(id.namespace());
        JsonObject modelJson = new JsonObject();
        JsonObject textures = new JsonObject();
        String modelPath;
        if (model.equals("block")) {
            modelPath = "block/" + id.name();
            modelJson.addProperty("parent", "minecraft:block/cube_all");
            textures.addProperty("all", id.toString());
        } else if (model.equals("item")) {
            modelPath = "item/" + id.name();
            modelJson.addProperty("parent", "minecraft:item/generated");
            textures.addProperty("layer0", id.toString());
        } else {
            throw new ToolException("model must be item, block or none");
        }
        modelJson.add("textures", textures);
        write(assets.resolve("models").resolve(modelPath + ".json"), modelJson);
        JsonObject definition = new JsonObject();
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "minecraft:model");
        ref.addProperty("model", id.namespace() + ":" + modelPath);
        definition.add("model", ref);
        write(assets.resolve("items").resolve(id.name() + ".json"), definition);
        return id.namespace() + ":" + id.name();
    }

    private static void write(Path file, JsonObject json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json + "\n", StandardCharsets.UTF_8);
    }

    /** Zip the pack. The same sources always give the same bytes, so the hash only changes with content. */
    public synchronized Built build(String description) throws IOException {
        if (!Files.isDirectory(root.resolve("assets"))) {
            throw new ToolException("the pack is empty. Create a texture with texture_create first.");
        }
        JsonObject pack = new JsonObject();
        pack.addProperty("description", description);
        pack.addProperty("pack_format", packFormat);
        pack.addProperty("min_format", packFormat);
        pack.addProperty("max_format", Math.max(packFormat, 9999));
        JsonObject meta = new JsonObject();
        meta.add("pack", pack);
        write(root.resolve("pack.mcmeta"), meta);
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Path file : files) {
                ZipEntry entry = new ZipEntry(root.relativize(file).toString().replace('\\', '/'));
                entry.setLastModifiedTime(FileTime.fromMillis(946684800000L));
                zip.putNextEntry(entry);
                zip.write(Files.readAllBytes(file));
                zip.closeEntry();
            }
        }
        byte[] data = bytes.toByteArray();
        String sha1;
        try {
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        Files.createDirectories(outDir);
        Path zipFile = outDir.resolve("pack.zip");
        Files.write(zipFile, data);
        served = data;
        servedSha1 = sha1;
        if (server == null) {
            server = HttpServer.create(new InetSocketAddress(host, 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = served;
                boolean match = exchange.getRequestURI().getPath().equals("/pack-" + servedSha1 + ".zip");
                exchange.getResponseHeaders().add("Content-Type", "application/zip");
                exchange.sendResponseHeaders(match ? 200 : 404, match ? body.length : -1);
                if (match) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                exchange.close();
            });
            server.start();
        }
        String url = "http://" + host + ":" + server.getAddress().getPort() + "/pack-" + sha1 + ".zip";
        return new Built(zipFile, sha1, url, files.size(), data.length);
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }
}
