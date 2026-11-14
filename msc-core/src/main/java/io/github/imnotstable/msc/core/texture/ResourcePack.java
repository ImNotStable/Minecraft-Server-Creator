package io.github.imnotstable.msc.core.texture;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.files.AtomicFile;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.textures.Models;
import io.github.imnotstable.msc.textures.PackSource;
import io.github.imnotstable.msc.textures.ResourceId;
import io.github.imnotstable.msc.textures.Texture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The project's resource pack: source files under {@code <workspace>/.msc/pack}, zipped on demand and
 * served over HTTP so the server can send it to players.
 */
public final class ResourcePack implements AutoCloseable {
    /** A built pack. */
    public record Built(Path zip, String sha1, String url, int files, long bytes) {
    }

    /** What pack last sent to the server. A restarted server has forgotten it, so the next start sends it again. */
    public record Applied(String description, boolean required) {
    }

    private volatile Applied applied;

    /** Null until pack has sent a pack to the server in this session. */
    public Applied applied() {
        return applied;
    }

    public void applied(Applied applied) {
        this.applied = applied;
    }

    private final PackSource source;
    private final Path outDir;
    private final String host;
    private final int packFormat;
    private final int preferredPort;
    private HttpServer server;
    private volatile Built lastBuilt;
    private volatile PackHost.Served served = PackHost.Served.NONE;
    private Process hostProcess;
    private int hostPort;
    /** Set by {@link #close(boolean)} under this monitor; whatever starts a host checks it under the same one. */
    private boolean closed;

    /**
     * @param port port to serve on; 0 reuses the port this workspace used last ({@code .msc/pack-port}), so the pack
     *             URL, and with it what {@code server.properties} offers, stays the same from session to session
     */
    public ResourcePack(Path root, Path outDir, String host, int port, int packFormat) {
        this.source = new PackSource(root);
        this.outDir = outDir;
        this.host = host;
        this.preferredPort = port;
        this.packFormat = packFormat;
    }

    public ResourcePack(Path root, Path outDir, String host, int packFormat) {
        this(root, outDir, host, 0, packFormat);
    }

    /** The pack_format every build writes into pack.mcmeta. */
    public int packFormat() {
        return packFormat;
    }

    /** The last pack built in this session, or null. */
    public Built lastBuilt() {
        return lastBuilt;
    }

    /** True when {@code url} has the shape of a URL an MSC pack host hands out on this host. */
    public boolean ours(String url) {
        return url != null && url.matches("http://" + Pattern.quote(host) + ":\\d+/pack-[0-9a-f]{40}\\.zip");
    }

    /** The pack's source folder, holding {@code assets/}. */
    public Path root() {
        return source.root();
    }

    /** The pack's source files: textures, animations, models and item definitions. */
    public PackSource source() {
        return source;
    }

    /**
     * Write a texture and, for {@code item} or {@code block}, the model and item definition that make
     * it usable through the {@code item_model} component.
     *
     * @param model {@code item}, {@code handheld}, {@code block} or {@code none}
     * @return what to put in {@code item_model}, or null when no model was written
     */
    public String writeTexture(ResourceId id, Texture texture, String model) throws IOException {
        Models.Kind kind = switch (model) {
            case "none" -> null;
            case "item" -> Models.Kind.ITEM;
            // handheld is an item held like a sword or tool: tilted in the hand instead of flat.
            case "handheld" -> Models.Kind.HANDHELD;
            case "block" -> Models.Kind.CUBE_ALL;
            default -> throw new ToolException("model must be item, handheld, block or none");
        };
        String itemModel = kind == null ? null : source.writeTextureModel(id, kind);
        // A still image replaces an animation, so this drops its frame timing.
        source.writeTexture(id, texture);
        return itemModel;
    }

    /** Zip the pack. The same sources always give the same bytes, so the hash only changes with content. */
    public synchronized Built build(String description) throws IOException {
        if (closed) {
            throw ToolException.workspaceClosed("the pack was not built or served");
        }
        if (!Files.isDirectory(root().resolve("assets"))) {
            throw new ToolException("the pack is empty. Add one with the texture tool first.");
        }
        source.writePackMeta(packFormat, description);
        PackSource.Zipped zipped = source.zip(zipFile());
        byte[] data = Files.readAllBytes(zipped.zip());
        String sha1 = zipped.sha1();
        served = new PackHost.Served(data, sha1);
        int port;
        if (hostProcess != null && hostProcess.isAlive()) {
            port = hostPort; // the standalone host serves the zip file itself
        } else {
            if (server == null) {
                server = startHost();
            }
            port = server.getAddress().getPort();
        }
        String url = "http://" + host + ":" + port + "/pack-" + sha1 + ".zip";
        Built built = new Built(zipped.zip(), sha1, url, zipped.files(), data.length);
        lastBuilt = built;
        return built;
    }

    /** What the pack host serves, so a build whose send failed can be taken back ({@link #restore}). */
    public record Snapshot(PackHost.Served served, Built built) {
    }

    /** What is served now. */
    public synchronized Snapshot snapshot() {
        return new Snapshot(served, lastBuilt);
    }

    /**
     * Serve {@code snapshot} again, in-process and through the zip file a standalone host reads, so the URL the
     * players were last sent works again after a build whose send failed.
     */
    public synchronized void restore(Snapshot snapshot) throws IOException {
        if (snapshot.built() == null || snapshot.served().sha1().isEmpty()) {
            served = PackHost.Served.NONE;
            lastBuilt = null;
            Files.deleteIfExists(zipFile());
            return;
        }
        // As a build writes it (in place), but whole: a standalone host may be reading it.
        AtomicFile.write(zipFile(), to -> Files.write(to, snapshot.served().data()));
        served = snapshot.served();
        lastBuilt = snapshot.built();
    }

    /** Serve on the configured or remembered port, or on a free one when that is taken. */
    private HttpServer startHost() throws IOException {
        int want = preferredPort > 0 ? preferredPort : rememberedPort();
        HttpServer started;
        try {
            // The port may have been let go a moment ago, by the leftover host or this session's own.
            started = PackHost.startWithin(host, want, () -> served, PackHost.PORT_PATIENCE);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted while waiting for port " + want);
        } catch (IOException e) {
            if (want == 0) {
                throw e;
            }
            started = PackHost.start(host, 0, () -> served);
        }
        int port = started.getAddress().getPort();
        if (port != want) {
            Files.createDirectories(portFile().getParent());
            Files.writeString(portFile(), port + "\n");
        }
        return started;
    }

    /** {@code .msc/pack-port}: the port this workspace's pack was last served on. */
    private Path portFile() {
        return outDir.resolveSibling("pack-port");
    }

    private int rememberedPort() {
        try {
            int port = Integer.parseInt(Files.readString(portFile()).strip());
            return port > 0 && port < 65536 ? port : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
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
        if (closed) {
            throw ToolException.workspaceClosed("no standalone pack host was started");
        }
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
                .redirectError(hostLog().toFile())
                .start();
        // Owned from the moment it exists, so an interrupt below (the session ending while the host starts) leaves
        // it where close and a later session's stopLeftoverHost find it, never as an orphan with no pid file (#297).
        hostProcess = p;
        hostPort = port;
        PidFile.write(hostPidFile(), p.toHandle());
        try {
            p.getOutputStream().close();
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (p.isAlive() && System.nanoTime() < deadline && !accepts(port)) {
                Thread.sleep(100);
            }
        } catch (IOException | RuntimeException e) {
            abandonHost(p, port);
            throw e;
        }
        if (!p.isAlive() || !accepts(port)) {
            abandonHost(p, port);
            String why = lastLine(hostLog());
            throw new ToolException("could not start a standalone pack host" + (why.isEmpty() ? "" : " (" + why + ")")
                    + "; the pack is still served by this session");
        }
        return true;
    }

    /** Kill a standalone host that did not come up, and serve in-process again on its port. */
    private void abandonHost(Process p, int port) throws IOException, InterruptedException {
        p.destroyForcibly();
        hostProcess = null;
        try {
            Files.deleteIfExists(hostPidFile());
        } catch (IOException ignored) {
            // A stale file is checked against the process start time anyway.
        }
        p.waitFor(5, TimeUnit.SECONDS);
        server = PackHost.startWithin(host, port, () -> served, PackHost.PORT_PATIENCE);
    }

    /** {@code .msc/pack-host.log}: what the standalone host printed, so a failed start can say why. */
    private Path hostLog() {
        return outDir.resolveSibling("pack-host.log");
    }

    private static String lastLine(Path log) {
        try {
            List<String> lines = Files.readAllLines(log).stream().map(String::strip).filter(l -> !l.isEmpty()).toList();
            return lines.isEmpty() ? "" : lines.getLast();
        } catch (IOException e) {
            return "";
        }
    }

    private boolean accepts(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Stop the standalone host this session started on server_detach, once the detached server was stopped after
     * all. The next build serves in-process again, on the same port. Returns true when there was one.
     */
    public synchronized boolean stopOwnHost() {
        if (hostProcess == null) {
            return false;
        }
        ProcessHandle h = hostProcess.toHandle();
        hostProcess.destroy();
        hostProcess = null;
        try {
            h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // Its port frees a moment later; a build then falls back to a free port.
        }
        try {
            Files.deleteIfExists(hostPidFile());
        } catch (IOException ignored) {
            // A stale file is checked against the process start time anyway.
        }
        return true;
    }

    /** Stop a pack host an earlier, detached session left running. Returns a note, or null when there was none. */
    public synchronized String stopLeftoverHost() {
        if (hostProcess != null && hostProcess.isAlive()) {
            return null;
        }
        return PidFile.take(hostPidFile()).map(h -> {
            h.destroy();
            try {
                // Wait for its port: this session serves the pack on the same one.
                h.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // Then the pack moves to a free port and server.properties follows it.
            }
            return "stopped the pack host left running by an earlier session (pid " + h.pid() + ")";
        }).orElse(null);
    }

    /** Stop serving. {@code keepHost} leaves a standalone host running for a detached server. */
    public synchronized void close(boolean keepHost) {
        closed = true;
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
