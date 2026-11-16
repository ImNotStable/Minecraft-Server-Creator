package io.github.imnotstable.msc.core.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.ServerJava;
import io.github.imnotstable.msc.textures.Models;
import io.github.imnotstable.msc.textures.PackSource;
import io.github.imnotstable.msc.textures.ResourceId;
import io.github.imnotstable.msc.textures.Texture;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * The project's resource pack: source files under {@code <workspace>/.msc/pack}, zipped on demand and
 * served over HTTP so the server can send it to players.
 */
public final class ResourcePack implements AutoCloseable {
    /** A built pack. */
    public record Built(Path zip, String sha1, String url, int files, long bytes) {
    }

    /**
     * The pack the pack tool built to be sent, kept with the workspace in {@code .msc/pack-applied.json} (#655): a
     * restarted server has forgotten it, and a server started later, in this session or another, never had it, so
     * each start that connects an agent sends it.
     */
    public record Applied(String description, boolean required) {
    }

    /** The workspace's pack to send, or null when the pack tool never built one to send (or the record is unreadable). */
    public Applied applied() {
        try {
            JsonObject json = JsonParser.parseString(Files.readString(appliedFile())).getAsJsonObject();
            return new Applied(json.get("description").getAsString(), json.get("required").getAsBoolean());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Keep {@code applied} as the workspace's pack to send; null forgets it. */
    public void applied(Applied applied) throws IOException {
        if (applied == null) {
            Files.deleteIfExists(appliedFile());
            return;
        }
        JsonObject json = new JsonObject();
        json.addProperty("description", applied.description());
        json.addProperty("required", applied.required());
        Files.createDirectories(appliedFile().getParent());
        AtomicFile.write(appliedFile(), to -> Files.writeString(to, json + "\n"));
    }

    /** The pack an agent connection was last sent: its URL, SHA-1 and whether it is required. */
    private record Sent(Object connection, String url, String sha1, boolean required) {
    }

    /** What the last send went to; in memory only, as a new connection (a restart, a late agent) always gets it. */
    private Sent sent;

    /** Note that {@code connection} (the agent connection the send went to) was sent {@code built}. */
    public synchronized void sent(Object connection, Built built, boolean required) {
        sent = new Sent(connection, built.url(), built.sha1(), required);
    }

    /**
     * True when {@code connection} was last sent exactly {@code built}, so its players already have it: dev_cycle with
     * an unchanged jar sends the pack only when this is false (#667).
     */
    public synchronized boolean wasSent(Object connection, Built built, boolean required) {
        return connection != null && new Sent(connection, built.url(), built.sha1(), required).equals(sent);
    }

    /** {@code .msc/pack-applied.json}: the workspace's pack to send ({@link #applied()}). */
    private Path appliedFile() {
        return outDir.resolveSibling("pack-applied.json");
    }

    private final PackSource source;
    private final Path outDir;
    private final String host;
    private final int packFormat;
    private final int preferredPort;
    private PackHost.Host server;
    private volatile Built lastBuilt;
    private volatile PackHost.Served served = PackHost.Served.NONE;
    /** The standalone host this session serves the pack with: one it started, or an earlier session's it reuses. */
    private ProcessHandle hostProcess;
    private int hostPort;
    /** Whether {@link #hostProcess} is an earlier session's host, reused rather than started here (#750). */
    private boolean adopted;
    /**
     * Whether this workspace's server outlives this session ({@code ServerManager.lifetime}: detached here, or left
     * running by an earlier session), the one state {@link #serve} goes by: while it does, the pack must outlive this
     * session too, so the standalone host serves it (ADR 0051, #750).
     */
    private final BooleanSupplier outlives;
    /** The command that starts the standalone host, before its host, port and zip arguments. */
    private List<String> hostCommand = List.of(ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()),
            "-cp", System.getProperty("java.class.path"), PackHost.class.getName());
    /** Set by {@link #close(boolean)} under this monitor; whatever starts a host checks it under the same one. */
    private boolean closed;

    /**
     * @param port port to serve on; 0 reuses the port this workspace used last ({@code .msc/pack-port}), so the pack
     *             URL, and with it what {@code server.properties} offers, stays the same from session to session
     */
    public ResourcePack(Path root, Path outDir, String host, int port, int packFormat) {
        this(root, outDir, host, port, packFormat, () -> false);
    }

    /** @param outlives whether this workspace's server outlives the session, so its pack must too */
    public ResourcePack(Path root, Path outDir, String host, int port, int packFormat, BooleanSupplier outlives) {
        this.source = new PackSource(root);
        this.outDir = outDir;
        this.host = host;
        this.preferredPort = port;
        this.packFormat = packFormat;
        this.outlives = outlives;
    }

    public ResourcePack(Path root, Path outDir, String host, int packFormat) {
        this(root, outDir, host, 0, packFormat);
    }

    /** Replace the command that starts the standalone host (up to its arguments); tests use a stand-in. */
    public synchronized void hostCommand(List<String> command) {
        this.hostCommand = List.copyOf(command);
    }

    /** The pack_format every build writes into pack.mcmeta. */
    public int packFormat() {
        return packFormat;
    }

    /** The last pack built in this session, or null. */
    public Built lastBuilt() {
        return lastBuilt;
    }

    /** True when the workspace has a built pack: one built in this session, or the zip an earlier build left. */
    public boolean hasBuild() {
        return lastBuilt != null || Files.isRegularFile(zipFile());
    }

    /**
     * The workspace's built pack, served: the last one built in this session, else the zip an earlier build left in
     * {@code .msc/build} (another session's, or this one's before an {@code msc_workspace} switch away and back),
     * served as it is. Null when there is none.
     */
    public synchronized Built current() throws IOException {
        if (lastBuilt != null || !Files.isRegularFile(zipFile())) {
            return lastBuilt;
        }
        if (closed) {
            throw ToolException.workspaceClosed("the pack was not served");
        }
        byte[] data = Files.readAllBytes(zipFile());
        int files;
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(zipFile().toFile())) {
            files = zip.size();
        }
        String sha1 = PackSource.sha1(data);
        served = new PackHost.Served(data, sha1);
        lastBuilt = new Built(zipFile(), sha1, url(sha1), files, data.length);
        return lastBuilt;
    }

    /**
     * The URL of the build with {@code sha1} where it is served now: by the standalone host while it runs (it serves
     * the zip file itself), else by the in-process host, started here. Who should serve it is decided only by
     * {@link #serve}. While the server outlives the session, an earlier session's standalone host is reused or stopped
     * first ({@link #takeLeftoverHost}), so this session's host never waits for its port.
     */
    private String url(String sha1) throws IOException {
        if (!hostAlive() && server == null && outlives.getAsBoolean()) {
            takeLeftoverHost(sha1);
        }
        int port;
        if (hostAlive()) {
            port = hostPort;
        } else {
            if (server == null) {
                server = startHost();
            }
            port = server.port();
        }
        return "http://" + host + ":" + port + "/pack-" + sha1 + ".zip";
    }

    private boolean hostAlive() {
        return hostProcess != null && hostProcess.isAlive();
    }

    /**
     * A server that outlives the session while an earlier session's standalone host still runs (#750): that host is
     * reused when it serves the build with {@code sha1} (it serves the zip file itself) on the port this workspace's
     * pack is served on, else stopped, so the host that replaces it ({@link #serve}) can take that port. Either way
     * one host serves the pack past the session, and what {@code server.properties} offers is its URL.
     */
    private void takeLeftoverHost(String sha1) {
        ProcessHandle left = PidFile.peek(hostPidFile()).orElse(null);
        if (left == null) {
            return;
        }
        int port = preferredPort > 0 ? preferredPort : rememberedPort();
        if (port > 0 && serves(port, sha1) && left.isAlive()) {
            hostProcess = left;
            hostPort = port;
            adopted = true;
            return;
        }
        dropLeftoverHost();
    }

    /** True when {@code port} serves the build with {@code sha1} (a 200): this workspace's pack host. */
    private boolean serves(int port, String sha1) {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create("http://" + host + ":" + port + "/pack-" + sha1
                    + ".zip").toURL().openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000);
            c.setReadTimeout(2000);
            try {
                return c.getResponseCode() == 200;
            } finally {
                c.disconnect();
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Stop the standalone host the pid file names, unless this session serves the pack with one; waits for its port,
     * which this session serves the pack on next. Returns its pid, or -1 when there was none.
     */
    private long dropLeftoverHost() {
        if (hostAlive()) {
            return -1;
        }
        return PidFile.take(hostPidFile()).map(h -> {
            h.destroy();
            try {
                h.onExit().get(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                // Then the pack moves to a free port and server.properties follows it.
            }
            return h.pid();
        }).orElse(-1L);
    }

    /**
     * Who serves the workspace's pack after {@link #serve}: the build ({@code null} when there is none), whether the
     * standalone host serves it (so it outlives this session), and why that host did not come up when it should have,
     * as one line (null otherwise).
     */
    public record Serving(Built built, boolean outlives, String trouble) {
    }

    /**
     * The one place that decides who serves the workspace's pack (ADR 0051), from one state: while the server outlives
     * the session (detached, or a leftover, #750) the standalone host serves it, an earlier session's reused when it
     * serves the build, else started here when it is not running (also after it ended meanwhile);
     * otherwise this session does, and a standalone host it started earlier is stopped. A host that does not come up
     * is an answer, not a failure: the pack is then served in-process ({@link Serving#trouble} says why), and the next
     * call tries again.
     */
    public synchronized Serving serve() throws IOException, InterruptedException {
        if (closed) {
            throw ToolException.workspaceClosed("the pack was not served");
        }
        boolean outlive = outlives.getAsBoolean();
        if (!outlive && hostProcess != null) {
            stopOwnHost();
        }
        Built built = current();
        String trouble = null;
        if (built != null && outlive && !hostAlive()) {
            trouble = startStandalone();
        }
        if (built != null) {
            String url = url(built.sha1());
            if (!url.equals(built.url())) {
                built = new Built(built.zip(), built.sha1(), url, built.files(), built.bytes());
            }
            lastBuilt = built;
        }
        return new Serving(built, built != null && hostAlive(), trouble);
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
        Built built = new Built(zipped.zip(), sha1, url(sha1), zipped.files(), data.length);
        lastBuilt = built;
        return built;
    }

    /**
     * What the pack host serves and the workspace's pack to send, so a build whose send failed can be taken back
     * ({@link #restore}).
     */
    public record Snapshot(PackHost.Served served, Built built, Applied applied) {
    }

    /** What is served now; an earlier build's zip ({@link #current}) is served first, so it can be restored. */
    public synchronized Snapshot snapshot() {
        Built built;
        try {
            built = current();
        } catch (IOException | RuntimeException e) {
            built = lastBuilt; // an unreadable zip is nothing to restore
        }
        return new Snapshot(served, built, applied());
    }

    /**
     * Serve {@code snapshot} again, in-process and through the zip file a standalone host reads, so the URL the
     * players were last sent works again after a build whose send failed.
     */
    public synchronized void restore(Snapshot snapshot) throws IOException {
        applied(snapshot.applied());
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
    private PackHost.Host startHost() throws IOException {
        int want = preferredPort > 0 ? preferredPort : rememberedPort();
        PackHost.Host started;
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
        int port = started.port();
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
     * Start the standalone host on the in-process host's port (started first when none runs, so the port is the one
     * this workspace's pack is served on) and stop that one. Returns null when it came up, else why not, in one line;
     * the pack is then served in-process again.
     */
    private String startStandalone() throws IOException, InterruptedException {
        // An earlier session's host this one did not reuse: the pid file is about to name the new one (#750).
        dropLeftoverHost();
        if (server == null) {
            server = startHost();
        }
        int port = server.port();
        server.stop();
        server = null;
        List<String> command = new java.util.ArrayList<>(hostCommand);
        command.addAll(List.of(host, String.valueOf(port), zipFile().toString()));
        Process p;
        try {
            // Its log is read as UTF-8 when it does not come up (#478).
            p = ServerJava.processBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(hostLog().toFile())
                    .start();
        } catch (IOException e) {
            server = startHost();
            return failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        // Owned from the moment it exists, so an interrupt below (the session ending while the host starts) leaves
        // it where close and a later session's stopLeftoverHost find it, never as an orphan with no pid file (#297).
        hostProcess = p.toHandle();
        adopted = false;
        hostPort = port;
        PidFile.write(hostPidFile(), p.toHandle());
        try {
            p.getOutputStream().close();
            long deadline = System.nanoTime() + 15_000_000_000L;
            while (p.isAlive() && System.nanoTime() < deadline && !accepts(port)) {
                Thread.sleep(100);
            }
        } catch (IOException | RuntimeException e) {
            abandonHost(p);
            return failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        if (p.isAlive() && accepts(port)) {
            return null;
        }
        boolean hung = p.isAlive();
        abandonHost(p);
        String why = reason(readLog());
        return failed(!why.isEmpty() ? why : hung ? "it did not listen within 15 s"
                : "it exited with code " + p.exitValue());
    }

    private static String failed(String why) {
        return "could not start a standalone pack host (" + why + ")";
    }

    /** Kill a standalone host that did not come up, and serve in-process again, on its port when it is free. */
    private void abandonHost(Process p) throws IOException, InterruptedException {
        p.destroyForcibly();
        hostProcess = null;
        adopted = false;
        try {
            Files.deleteIfExists(hostPidFile());
        } catch (IOException ignored) {
            // A stale file is checked against the process start time anyway.
        }
        p.waitFor(5, TimeUnit.SECONDS);
        server = startHost();
    }

    /** {@code .msc/pack-host.log}: what the standalone host printed, so a failed start can say why. */
    private Path hostLog() {
        return outDir.resolveSibling("pack-host.log");
    }

    private List<String> readLog() {
        try {
            return Files.readAllLines(hostLog());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** A line that starts a stack trace: {@code Exception in thread "main" java.net.BindException: why}. */
    private static final Pattern THROWN = Pattern.compile("(?:Exception in thread \"[^\"]*\" )?"
            + "(?:[a-z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error)(?:: (.*))?");
    /** Lines that never say why: the launcher's notes and a stack trace's frames and tail. */
    private static final Pattern NOISE = Pattern.compile("(?:NOTE: )?Picked up |WARNING: |at |\\.\\.\\. \\d+ more"
            + "|Caused by: ");

    /**
     * Why a host that did not come up failed, in one line (#744): the message of the exception a stack trace starts
     * with, else the first line that is neither a launcher note nor part of a trace ({@link PackHost#main} writes the
     * reason alone). Never a trace line such as {@code ... 2 more}.
     */
    static String reason(List<String> log) {
        List<String> lines = log.stream().map(String::strip)
                .filter(l -> !l.isEmpty() && !NOISE.matcher(l).lookingAt()).toList();
        for (String line : lines) {
            java.util.regex.Matcher m = THROWN.matcher(line);
            if (m.matches()) {
                return m.group(1) != null && !m.group(1).isBlank() ? m.group(1).strip() : line;
            }
        }
        return lines.isEmpty() ? "" : lines.getFirst();
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
     * Stop the standalone host this session serves the pack with (started for its detached server, or an earlier
     * session's it reused, #750), once the server no longer outlives the session. The next build serves in-process
     * again, on the same port. Returns true when there was one.
     */
    public synchronized boolean stopOwnHost() {
        if (hostProcess == null) {
            return false;
        }
        ProcessHandle h = hostProcess;
        hostProcess.destroy();
        hostProcess = null;
        adopted = false;
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

    /**
     * A switch away from a workspace kept for its detached server (#735), after {@link #serve} handed its pack to the
     * standalone host: stop the in-process host, so a fixed pack port is free for the next workspace. A pack that
     * host serves is kept; the switch back serves again whatever it did not.
     */
    public synchronized void park() {
        if (server != null) {
            server.stop();
            server = null;
            lastBuilt = null; // its URL named the stopped host's port; current() serves the zip again
        }
    }

    /**
     * Stop a pack host an earlier, detached session left running, also one this session reused for the server that
     * session left (#750). Returns a note, or null when there was none.
     */
    public synchronized String stopLeftoverHost() {
        long pid;
        if (adopted && hostAlive()) {
            pid = hostProcess.pid();
            stopOwnHost();
        } else {
            pid = dropLeftoverHost();
        }
        return pid < 0 ? null : "stopped the pack host left running by an earlier session (pid " + pid + ")";
    }

    /** Stop serving. {@code keepHost} leaves a standalone host running for a detached server. */
    public synchronized void close(boolean keepHost) {
        closed = true;
        if (server != null) {
            server.stop();
            server = null;
        }
        if (!keepHost && hostProcess != null) {
            hostProcess.destroy();
            hostProcess = null;
            adopted = false;
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
