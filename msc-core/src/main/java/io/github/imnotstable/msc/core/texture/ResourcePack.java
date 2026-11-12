package io.github.imnotstable.msc.core.texture;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    private volatile PackHost.Served served = PackHost.Served.NONE;
    private Process hostProcess;
    private int hostPort;

    public ResourcePack(Path root, Path outDir, String host, int packFormat) {
        this.root = root;
        this.outDir = outDir;
        this.host = host;
        this.packFormat = packFormat;
    }

    /** The pack's source folder, holding {@code assets/}. */
    public Path root() {
        return root;
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
        // A still image replaces an animation, so drop its frame timing.
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".mcmeta"));
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
        String sha1 = PackHost.sha1(data);
        Files.createDirectories(outDir);
        Path zipFile = zipFile();
        Files.write(zipFile, data);
        served = new PackHost.Served(data, sha1);
        int port;
        if (hostProcess != null && hostProcess.isAlive()) {
            port = hostPort; // the standalone host serves the zip file itself
        } else {
            if (server == null) {
                server = PackHost.start(host, 0, () -> served);
            }
            port = server.getAddress().getPort();
        }
        String url = "http://" + host + ":" + port + "/pack-" + sha1 + ".zip";
        return new Built(zipFile, sha1, url, files.size(), data.length);
    }

    private Path zipFile() {
        return outDir.resolve("pack.zip");
    }

    /** {@code .msc/pack-host.pid}: the standalone pack host a detached session left running. */
    private Path hostPidFile() {
        return outDir.resolveSibling("pack-host.pid");
    }

    /**
     * Move serving to a standalone process on the same port, so players who were sent the pack can still fetch it
     * after this core exits. Later builds in this session are served by it too. Returns false when nothing was
     * built yet (there is no pack to keep up).
     */
    public synchronized boolean keepServing(String java, String classPath) throws IOException, InterruptedException {
        if (hostProcess != null && hostProcess.isAlive()) {
            return true;
        }
        if (server == null) {
            return false;
        }
        int port = server.getAddress().getPort();
        server.stop(0);
        server = null;
        Process p = new ProcessBuilder(java, "-cp", classPath, PackHost.class.getName(), host, String.valueOf(port),
                zipFile().toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        p.getOutputStream().close();
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (p.isAlive() && System.nanoTime() < deadline && !accepts(port)) {
            Thread.sleep(100);
        }
        if (!p.isAlive() || !accepts(port)) {
            p.destroyForcibly();
            server = PackHost.start(host, port, () -> served);
            throw new ToolException("could not start a standalone pack host; the pack is still served by this session");
        }
        hostProcess = p;
        hostPort = port;
        PidFile.write(hostPidFile(), p.toHandle());
        return true;
    }

    private boolean accepts(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Stop a pack host an earlier, detached session left running. Returns a note, or null when there was none. */
    public synchronized String stopLeftoverHost() {
        if (hostProcess != null && hostProcess.isAlive()) {
            return null;
        }
        return PidFile.take(hostPidFile()).map(h -> {
            h.destroy();
            return "stopped the pack host left running by an earlier session (pid " + h.pid() + ")";
        }).orElse(null);
    }

    /** Stop serving. {@code keepHost} leaves a standalone host running for a detached server. */
    public synchronized void close(boolean keepHost) {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (!keepHost && hostProcess != null) {
            hostProcess.destroy();
            hostProcess = null;
            try {
                Files.deleteIfExists(hostPidFile());
            } catch (IOException ignored) {
                // A stale file is checked against the process start time anyway.
            }
        }
    }

    @Override
    public void close() {
        close(false);
    }
}
