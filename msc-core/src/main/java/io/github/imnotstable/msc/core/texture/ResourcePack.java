package io.github.imnotstable.msc.core.texture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.server.PidFile;
import io.github.imnotstable.msc.core.server.ServerManager.Lifetime;
import io.github.imnotstable.msc.core.server.WorkspacePorts;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.ServerJava;
import io.github.imnotstable.msc.textures.Models;
import io.github.imnotstable.msc.textures.PackSource;
import io.github.imnotstable.msc.textures.ResourceId;
import io.github.imnotstable.msc.textures.Texture;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
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

    /**
     * Note that {@code connection} (the agent connection the send went to) was sent {@code built}. The build is also
     * recorded with the workspace ({@code .msc/pack-sent}): its URL is what that server's agent hands joining players
     * for as long as the server runs, past this session too, so {@link #prune} keeps its file (ADR 0053).
     */
    public synchronized void sent(Object connection, Built built, boolean required) {
        sent = new Sent(connection, built.url(), built.sha1(), required);
        try {
            AtomicFile.write(sentFile(), to -> Files.writeString(to, built.sha1() + "\n"));
        } catch (IOException e) {
            // Then a prune may drop that build's file: only a standalone host reads it, and it serves the newer ones.
        }
    }

    /** {@code .msc/pack-sent}: the SHA-1 of the build the workspace's server was last sent. */
    private Path sentFile() {
        return outDir.resolveSibling("pack-sent");
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
     * session too, so the standalone host serves it (ADR 0051, #750); while another live session runs it, its host
     * and its builds are that session's (ADR 0053).
     */
    private final Supplier<Lifetime> lifetime;
    /** The command that starts the standalone host, before its host, port and folder arguments. */
    private List<String> hostCommand = List.of(ProcessHandle.current().info().command()
                    .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString()),
            "-cp", System.getProperty("java.class.path"), PackHost.class.getName());
    /**
     * Whether the in-process host is on a free port it took while another live session's host held this workspace's
     * (#753): a standalone host started later goes back to the workspace's port.
     */
    private boolean aside;
    /** Set by {@link #close(boolean)} under this monitor; whatever starts a host checks it under the same one. */
    private boolean closed;
    /** The ports the workspace uses for anything but the pack, read at each use (ADR 0054). */
    private Supplier<WorkspacePorts> ports = () -> WorkspacePorts.DEFAULTS;
    /** Ports this session found its pack host holding when the server needed them (#755): never taken again here. */
    private final Set<Integer> avoided = new HashSet<>();

    /**
     * @param port port to serve on; 0 reuses the port this workspace used last ({@code .msc/pack-port}), so the pack
     *             URL, and with it what {@code server.properties} offers, stays the same from session to session
     */
    public ResourcePack(Path root, Path outDir, String host, int port, int packFormat) {
        this(root, outDir, host, port, packFormat, () -> Lifetime.SESSION);
    }

    /** @param lifetime whether this workspace's server outlives the session, so its pack must too */
    public ResourcePack(Path root, Path outDir, String host, int port, int packFormat, Supplier<Lifetime> lifetime) {
        this.source = new PackSource(root);
        this.outDir = outDir;
        this.host = host;
        this.preferredPort = port;
        this.packFormat = packFormat;
        this.lifetime = lifetime;
    }

    private boolean outlives() {
        return lifetime.get() == Lifetime.OUTLIVES;
    }

    public ResourcePack(Path root, Path outDir, String host, int packFormat) {
        this(root, outDir, host, 0, packFormat);
    }

    /**
     * The ports the workspace uses for anything but the pack (ADR 0054), read each time a pack port is picked, taken
     * again or kept: the pack never takes one of them.
     */
    public synchronized void ports(Supplier<WorkspacePorts> ports) {
        this.ports = ports;
    }

    /** True when {@code port} is one the pack must not take: the workspace uses it, or this session gave it up. */
    private boolean reserved(int port) {
        return avoided.contains(port) || ports.get().reserved(port);
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
        store(sha1, data);
        served = new PackHost.Served(data, sha1);
        lastBuilt = new Built(zipFile(), sha1, url(sha1), files, data.length);
        return lastBuilt;
    }

    /**
     * The build file {@code pack-<sha1>.zip} beside {@code pack.zip}: what a standalone host serves at that build's URL
     * (ADR 0053). Named by its content, so it is written once and never changed; a build in another session writes
     * its own file and leaves this one served.
     */
    private Path buildFile(String sha1) {
        return outDir.resolve("pack-" + sha1 + ".zip");
    }

    /** Writes the build file of {@code data} unless it is there, whole (a standalone host may read it at once). */
    private void store(String sha1, byte[] data) throws IOException {
        if (!Files.isRegularFile(buildFile(sha1))) {
            Files.createDirectories(outDir);
            AtomicFile.write(buildFile(sha1), to -> Files.write(to, data));
        }
    }

    /** {@link #store} of the build this session serves, as {@link #served} holds it. */
    private void storeServed(Built built) throws IOException {
        if (built != null && served.sha1().equals(built.sha1())) {
            store(built.sha1(), served.data());
        }
    }

    /** A build file's name, and a URL's end: {@code pack-<sha1>.zip}. */
    private static final Pattern BUILD_FILE = Pattern.compile("pack-([0-9a-f]{40})\\.zip");

    /**
     * Deletes the build files no one may still ask for (ADR 0053): all but this session's build, the build
     * {@code offered} names (what {@code server.properties} offers) and the one the workspace's server was last sent
     * ({@code .msc/pack-sent}). Never while another live session runs the server: its builds are its own. Best effort.
     */
    public synchronized void prune(String offered) {
        if (lifetime.get() == Lifetime.OTHER_SESSION) {
            return;
        }
        Set<String> keep = new HashSet<>();
        if (lastBuilt != null) {
            keep.add(lastBuilt.sha1());
        }
        try {
            keep.add(Files.readString(sentFile()).strip());
        } catch (IOException e) {
            // Nothing was sent from this workspace yet.
        }
        Matcher url = offered == null ? null : BUILD_FILE.matcher(offered);
        if (url != null && url.find()) {
            keep.add(url.group(1));
        }
        try (var files = Files.list(outDir)) {
            for (Path file : files.toList()) {
                Matcher name = BUILD_FILE.matcher(file.getFileName().toString());
                if (name.matches() && !keep.contains(name.group(1))) {
                    Files.deleteIfExists(file);
                }
            }
        } catch (IOException e) {
            // The next publish prunes again.
        }
    }

    /**
     * The URL of the build with {@code sha1} where it is served now: by the standalone host while it runs (it serves
     * the zip file itself), else by the in-process host, started here. Who should serve it is decided only by
     * {@link #serve}. While the server outlives the session, an earlier session's standalone host is reused or stopped
     * first ({@link #takeLeftoverHost}), so this session's host never waits for its port.
     */
    private String url(String sha1) throws IOException {
        if (!hostAlive() && outlives()) {
            takeLeftoverHost(sha1);
        }
        int port;
        if (hostAlive()) {
            port = hostPort;
        } else {
            if (server != null && reserved(server.port())) {
                // Taken before the workspace used the port otherwise (server.properties changed): move off it (#755).
                server.stop();
                server = null;
            }
            if (server == null) {
                server = startHost();
            }
            port = server.port();
        }
        return "http://" + host + ":" + port + "/pack-" + sha1 + ".zip";
    }

    /**
     * Before a server start: the in-process host lets go of its port when the workspace now uses it otherwise, so the
     * server can bind it (#755). The next publish serves the pack on a free port, which the workspace remembers
     * instead. Returns true when the host let go of it.
     */
    public synchronized boolean leaveReservedPort() {
        if (server == null || !reserved(server.port())) {
            return false;
        }
        server.stop();
        server = null;
        lastBuilt = null; // its URL named the stopped host's port; current() serves the zip again
        return true;
    }

    /**
     * After a start that found its port held by this session's own in-process host (a port the core could not know,
     * such as one set in a Minestom main class): that host lets go of {@code port} and never takes it again in this
     * session; the next publish serves the pack on a free port. Returns true when the host let go of it.
     */
    public synchronized boolean avoid(int port) {
        avoided.add(port);
        return leaveReservedPort();
    }

    /**
     * Who the MSC pack host that answers on a port is, as seen from this workspace (ADR 0054): {@code OWN} for this
     * workspace's in-process host, {@code STANDALONE} for the standalone host {@code .msc/pack-host.pid} names,
     * {@code OTHER} for any other (another workspace's or session's), with the pid of the process it runs in.
     */
    public record Holder(Kind kind, long pid) {
        /** Whose host it is. */
        public enum Kind { OWN, STANDALONE, OTHER }
    }

    /**
     * The {@link Holder} of {@code port}, or null when no MSC pack host answers there. Told by the host's identity
     * (its pid and token), never by what the port serves.
     */
    public Holder holder(int port) {
        PackHost.Identity who = PackHost.identify(host, port);
        if (who == null) {
            return null;
        }
        String own;
        HostId recorded;
        synchronized (this) {
            own = server != null ? server.token() : null;
            recorded = leftoverId();
        }
        if (who.token().equals(own) && who.pid() == ProcessHandle.current().pid()) {
            return new Holder(Holder.Kind.OWN, who.pid());
        }
        if (recorded != null && recorded.process().pid() == who.pid() && recorded.token().equals(who.token())) {
            return new Holder(Holder.Kind.STANDALONE, who.pid());
        }
        return new Holder(Holder.Kind.OTHER, who.pid());
    }

    private boolean hostAlive() {
        return hostProcess != null && hostProcess.isAlive();
    }

    /**
     * A server that outlives the session while an earlier session's standalone host still runs (#750): that host is
     * reused when it is the host recorded with the pid file, on the port recorded there, and serves the build with
     * {@code sha1} (it serves the build files, ADR 0053); else it is stopped, so the host that replaces it
     * ({@link #serve}) can take this workspace's port. The host is told by its identity alone (ADR 0054): the host on
     * the recorded port must answer the recorded pid and token, so neither another process that took the port nor this
     * session's own in-process host, which may answer on a fixed {@code MSC_PACK_PORT}, is taken for it (#756), and the
     * URL names the port that host serves on. Either way one host serves the pack past the session, and what
     * {@code server.properties} offers is its URL. A reused host keeps serving the earlier builds, whose URLs the
     * earlier session's agent may have handed out.
     */
    private void takeLeftoverHost(String sha1) {
        HostId left = leftoverId();
        if (left == null) {
            dropLeftoverHost(); // none, or one with no identity (an MSC before ADR 0054): replaced
            return;
        }
        int port = left.port();
        if (!reserved(port) && isHost(left) && serves(port, sha1) && left.process().isAlive()) {
            hostProcess = left.process();
            hostPort = port;
            adopted = true;
            if (server != null) {
                // Served in-process while another live session ran the server: the reused host serves it now.
                server.stop();
                server = null;
            }
            return;
        }
        dropLeftoverHost();
    }

    /** True when the host on {@code left}'s port says it is {@code left}: the same pid and token. */
    private boolean isHost(HostId left) {
        PackHost.Identity who = PackHost.identify(host, left.port());
        return who != null && who.pid() == left.process().pid() && who.token().equals(left.token());
    }

    /** True when {@code port} serves the build with {@code sha1} (a 200), once the host there is known. */
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
        Optional<ProcessHandle> left = PidFile.take(hostPidFile());
        forgetHostId();
        return left.map(h -> {
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
     * otherwise this session does, and no standalone host runs ({@link #release}, ADR 0053). The host serves the build
     * from its file {@code pack-<sha1>.zip}, written again here when missing. A host that does not come up
     * is an answer, not a failure: the pack is then served in-process ({@link Serving#trouble} says why), and the next
     * call tries again.
     */
    public synchronized Serving serve() throws IOException, InterruptedException {
        if (closed) {
            throw ToolException.workspaceClosed("the pack was not served");
        }
        boolean outlive = outlives();
        if (!outlive) {
            release();
        }
        Built built = current();
        String trouble = null;
        if (built != null && outlive) {
            // Its file is what the standalone host serves; a prune elsewhere may have dropped it (ADR 0053).
            storeServed(built);
            if (!hostAlive()) {
                // Also for a build made while another live session ran the server, which has ended since.
                takeLeftoverHost(built.sha1());
            }
            if (!hostAlive()) {
                trouble = startStandalone();
                if (trouble == null) {
                    storeServed(built); // again once the host is recorded, so no prune in between drops it
                }
            }
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
        // pack.zip is the workspace's current build; the build file beside it is what a standalone host serves.
        PackSource.Zipped zipped = source.zip(zipFile());
        byte[] data = Files.readAllBytes(zipped.zip());
        String sha1 = zipped.sha1();
        store(sha1, data);
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
        AtomicFile.write(zipFile(), to -> Files.write(to, snapshot.served().data()));
        store(snapshot.served().sha1(), snapshot.served().data());
        served = snapshot.served();
        lastBuilt = snapshot.built();
    }

    /**
     * Serve on the configured or remembered port, or on a free one when that is taken. Never on a port the workspace
     * uses otherwise (ADR 0054): a remembered one is dropped, and a configured one is refused, naming its use (#755).
     */
    private PackHost.Host startHost() throws IOException {
        if (preferredPort > 0 && reserved(preferredPort)) {
            String use = ports.get().use(preferredPort);
            throw new ToolException("MSC_PACK_PORT=" + preferredPort + " is "
                    + (use != null ? use : "a port this workspace's server needed") + ", so the resource pack is not"
                    + " served there: a pack host on it would keep that port from what needs it. Set MSC_PACK_PORT"
                    + " to another port, or unset it to serve the pack on a free one");
        }
        int want = preferredPort > 0 ? preferredPort : rememberedPort();
        // While another live session runs the server, its pack host holds the port: no use waiting, and the free port
        // taken instead is not this workspace's to remember (#753).
        boolean theirs = lifetime.get() == Lifetime.OTHER_SESSION;
        PackHost.Host started;
        if (want == 0) {
            // A first pack: a port no other process is handed by the system, so it can be taken again (ADR 0053).
            started = PackHost.startOnFreePort(host, () -> served, this::reserved, null);
        } else {
            try {
                // The port may have been let go a moment ago, by the leftover host or this session's own.
                started = PackHost.startWithin(host, want, () -> served,
                        theirs ? Duration.ZERO : PackHost.PORT_PATIENCE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.io.InterruptedIOException("interrupted while waiting for port " + want);
            } catch (IOException e) {
                started = PackHost.startOnFreePort(host, () -> served, this::reserved, null);
            }
        }
        int port = started.port();
        aside = theirs && port != want;
        if (port != want && !theirs) {
            Files.createDirectories(portFile().getParent());
            Files.writeString(portFile(), port + "\n");
        }
        return started;
    }

    /** {@code .msc/pack-port}: the port this workspace's pack was last served on. */
    private Path portFile() {
        return outDir.resolveSibling("pack-port");
    }

    /**
     * The port in {@code .msc/pack-port}, or 0. A port the workspace now uses otherwise (ADR 0054) is dropped: the
     * file is deleted and a free port picked, which the file then remembers (#755).
     */
    private int rememberedPort() {
        int port;
        try {
            port = Integer.parseInt(Files.readString(portFile()).strip());
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
        if (port <= 0 || port >= 65536) {
            return 0;
        }
        if (reserved(port)) {
            try {
                Files.deleteIfExists(portFile());
            } catch (IOException e) {
                // A free port is picked anyway, and written over it.
            }
            return 0;
        }
        return port;
    }

    private Path zipFile() {
        return outDir.resolve("pack.zip");
    }

    /** {@code .msc/pack-host.pid}: the standalone pack host a detached session left running. */
    private Path hostPidFile() {
        return outDir.resolveSibling("pack-host.pid");
    }

    /**
     * {@code .msc/pack-host.id}: {@code <pid> <port> <token>}, the standalone host's identity beside its pid file
     * (ADR 0054): the port it was started on and the token it answers on {@link PackHost#IDENTITY_PATH}. Written
     * before the pid file, so a host the pid file names has one; a file of its own, so an MSC from before it still
     * reads the pid file as it did.
     */
    private Path hostIdFile() {
        return outDir.resolveSibling("pack-host.id");
    }

    /** What {@code .msc/pack-host.id} records of the live host {@code .msc/pack-host.pid} names. */
    private record HostId(ProcessHandle process, int port, String token) {
    }

    /** The live host the pid file names, with the identity recorded for that pid; null when either is missing. */
    private HostId leftoverId() {
        ProcessHandle left = PidFile.peek(hostPidFile()).orElse(null);
        if (left == null) {
            return null;
        }
        try {
            String[] words = Files.readString(hostIdFile()).strip().split(" ");
            if (words.length != 3 || Long.parseLong(words[0]) != left.pid()) {
                return null;
            }
            int port = Integer.parseInt(words[1]);
            return port > 0 && port < 65536 ? new HostId(left, port, words[2]) : null;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    private void writeHostId(long pid, int port, String token) {
        try {
            AtomicFile.write(hostIdFile(), to -> Files.writeString(to, pid + " " + port + " " + token + "\n"));
        } catch (IOException e) {
            // Without it the next session replaces the host instead of reusing it; nothing else breaks.
        }
    }

    /** Deletes the pid file and the identity beside it. */
    private void forgetHost() {
        try {
            Files.deleteIfExists(hostPidFile());
        } catch (IOException ignored) {
            // A stale file is checked against the process start time anyway.
        }
        forgetHostId();
    }

    private void forgetHostId() {
        try {
            Files.deleteIfExists(hostIdFile());
        } catch (IOException ignored) {
            // Keyed by a pid the pid file must name with its start time, so a stale one is never matched.
        }
    }

    /**
     * Start the standalone host on the in-process host's port (started first when none runs, so the port is the one
     * this workspace's pack is served on) and stop that one. Returns null when it came up, else why not, in one line;
     * the pack is then served in-process again.
     */
    private String startStandalone() throws IOException, InterruptedException {
        // An earlier session's host this one did not reuse: the pid file is about to name the new one (#750).
        dropLeftoverHost();
        if (server != null && aside) {
            // Served on a free port while another live session ran the server: the host takes the workspace's (#753).
            server.stop();
            server = null;
        }
        if (server == null) {
            server = startHost();
        }
        int port = server.port();
        server.stop();
        server = null;
        String token = PackHost.newToken();
        List<String> command = new java.util.ArrayList<>(hostCommand);
        command.addAll(List.of(host, String.valueOf(port), outDir.toString(), token));
        Process p;
        try {
            // Its log is read as UTF-8 when it does not come up (#478).
            p = ServerJava.processBuilder(command)
                    .redirectError(hostLog().toFile())
                    .start();
        } catch (IOException e) {
            server = startHost();
            return failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        // Owned from the moment it exists, so an interrupt below (the session ending while the host starts) leaves
        // it where close and a later session's release find it, never as an orphan with no pid file (#297).
        hostProcess = p.toHandle();
        adopted = false;
        hostPort = port;
        writeHostId(p.pid(), port, token);
        PidFile.write(hostPidFile(), p.toHandle());
        Boolean ready;
        try {
            p.getOutputStream().close();
            ready = ready(p, port);
        } catch (IOException | RuntimeException e) {
            abandonHost(p);
            return failed(e.getMessage() != null ? e.getMessage() : e.toString());
        }
        if (Boolean.TRUE.equals(ready) && p.isAlive()) {
            return null;
        }
        boolean hung = ready == null;
        if (!hung) {
            p.waitFor(5, TimeUnit.SECONDS); // its output ended a moment before it exits
        }
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
        forgetHost();
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

    /**
     * Waits up to 15 s for the standalone host {@code p} to say it serves on {@code port} ({@link PackHost#READY}); false
     * when it exits or stays silent first. Only its own word counts: a connection to the port could reach anything
     * that took the port while it changed hands, which then looked like a host that came up and served nothing (#753).
     * Its output is read to the end, so the host never blocks on it. Null when it stayed silent (still running); false
     * when it ended its output, so exited, without saying it.
     */
    private static Boolean ready(Process p, int port) throws InterruptedException {
        java.util.concurrent.CompletableFuture<Boolean> said = new java.util.concurrent.CompletableFuture<>();
        Thread.ofVirtual().name("msc-pack-host-output").start(() -> {
            try (java.io.BufferedReader out = p.inputReader(java.nio.charset.StandardCharsets.UTF_8)) {
                for (String line = out.readLine(); line != null; line = out.readLine()) {
                    if (line.strip().equals(PackHost.READY + port)) {
                        said.complete(true);
                    }
                }
            } catch (IOException e) {
                // Gone: nothing more to read.
            }
            said.complete(false);
        });
        try {
            return said.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        } catch (java.util.concurrent.TimeoutException e) {
            return null;
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
        forgetHost();
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
     * What {@link #release} stopped: the pid of a host an earlier session left running (reused by this one or not),
     * or -1, and whether this session's own standalone host stopped.
     */
    public record Released(long earlier, boolean own) {
        /** The note on the earlier session's host, or null. */
        public String note() {
            return earlier < 0 ? null
                    : "stopped the pack host left running by an earlier session (pid " + earlier + ")";
        }
    }

    /**
     * A standalone host runs only while the server outlives the session (ADR 0053, #752): once it does not, this stops
     * the host this session serves with (started here or reused) and, unless another live session runs the server
     * (whose host it is), the host {@code .msc/pack-host.pid} names, as no server is left for it: one an earlier
     * session left with its leftover, which may have been stopped or exited on its own. Called after every stop of a
     * server, by every publish and at the session end; while the server outlives the session it does nothing.
     */
    public synchronized Released release() {
        Lifetime life = lifetime.get();
        if (life == Lifetime.OUTLIVES) {
            return new Released(-1, false);
        }
        long earlier = adopted && hostAlive() ? hostProcess.pid() : -1;
        boolean own = !adopted && hostAlive();
        stopOwnHost();
        if (life == Lifetime.SESSION) {
            long dropped = dropLeftoverHost();
            if (earlier < 0) {
                earlier = dropped;
            }
        }
        return new Released(earlier, own);
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
            forgetHost();
        }
    }

    @Override
    public void close() {
        close(false);
    }
}
