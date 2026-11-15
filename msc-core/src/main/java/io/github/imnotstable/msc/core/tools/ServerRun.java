package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.CrashTriage;
import io.github.imnotstable.msc.core.server.LogCondenser;
import io.github.imnotstable.msc.core.server.MachineEula;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.server.ServerProperties;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.core.tool.Args;
import io.github.imnotstable.msc.core.tool.Budget;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/** Starting, stopping and detaching the dev server, and the start report the AI reads. */
final class ServerRun {
    private static final int GROUND_RADIUS = 32;

    private final CoreConfig config;
    private final AgentHub hub;
    private final ServerManager server;
    private final ResourcePack pack;
    private final ServerSetup setup;
    private final EulaPrompts eula;
    private final LogMarks marks;

    /**
     * A start report as parts of a result (#473): its header lines, then the startup log cut to its share; and how
     * long the start took and when the agent connected (for dev_cycle's timings).
     */
    record Report(Budget.Parts parts, ServerManager.StartResult result) {
    }

    /** The hint of a cut of a start report's header lines, which no later call shows again. */
    static final String HEADER_CUT = "The startup log is in server_logs.";

    ServerRun(CoreConfig config, AgentHub hub, ServerManager server, ResourcePack pack, ServerSetup setup,
              EulaPrompts eula, LogMarks marks) {
        this.config = config;
        this.hub = hub;
        this.server = server;
        this.pack = pack;
        this.setup = setup;
        this.eula = eula;
        this.marks = marks;
    }

    static final String ACCEPT_EULA = "write the user's EULA acceptance";
    static final String LEFTOVER_SERVER = "stop the server an earlier session left running";
    static final String SWEEP = "remove what an interrupted earlier call left in plugins/, mods/ and libs/";
    static final String TAKE_OVER_PACK = "take over the pack an earlier session served";
    static final String REFRESH_AGENT = "update the installed agent jar";
    static final String START = "start the server";
    /** Longest server_start may wait for the server to be ready. */
    static final int MAX_START_SECONDS = 3600;
    static final String DETACH = "detach the server";
    static final String KEEP_SERVING = "hand the pack to a standalone host";
    static final String OFFER_PACK = "offer the pack in " + ServerPropertiesFile.NAME;

    /**
     * Checked first ({@link #checkStart}): not running, a server to start, the EULA accepted or accepted now, eula.txt
     * writable. Then: accept the EULA, then {@link #startAndReport}.
     */
    String start(Args args) throws IOException, InterruptedException {
        int timeoutSeconds = args.integer("timeoutSeconds", 180);
        if (server.running()) {
            throw new ToolException(server.alreadyRunning());
        }
        server.checkNoOtherSession();
        Platform platform = server.platform();
        // acceptEula on a platform with no EULA is refused, as server_setup refuses it, not dropped with an OK (#625).
        WayArgs.checkEula(args, platform);
        boolean accepting = args.bool("acceptEula", false);
        checkStart(platform, accepting);
        Steps steps = new Steps("server_start");
        String accepted = "";
        if (accepting) {
            Path file = OutputFile.check(server.eulaFile(), "eula.txt");
            steps.then(ACCEPT_EULA);
            plan(steps);
            accepted = steps.run(ACCEPT_EULA, () -> {
                if (!ServerManager.eulaAccepted(file)) {
                    Files.createDirectories(config.serverDir());
                    Files.writeString(file, "# Minecraft EULA (" + MachineEula.EULA_URL + ") accepted by the user\neula=true\n");
                }
                return eula.recordForMachine();
            });
        }
        return new Budget.Parts().add(accepted).add(startAndReport(steps, true, timeoutSeconds).parts())
                .text(Budget.DEFAULT_CHARS, null, HEADER_CUT);
    }

    /**
     * The refusals of a start of {@code platform}, decided before anything changes (the Steps rule "check first"), so
     * each is the plain one-line answer and nothing was touched: no server jar, the EULA neither accepted nor
     * {@code accepting} on this call, eula.txt not writable where the machine acceptance is to be written into it.
     */
    void checkStart(Platform platform, boolean accepting) {
        server.checkStartable(platform, accepting);
        if (config.serverCommand() == null && platform.needsEula() && !accepting
                && !ServerManager.eulaAccepted(server.eulaFile())) {
            OutputFile.check(server.eulaFile(), "eula.txt");
        }
    }

    /**
     * What {@link #stopAny} found: whether a server was running, this session's or one an earlier session left (so a
     * call that stops it to change the world starts it again), and the leftover's stop note, or null.
     */
    record Stopped(boolean wasRunning, String leftover) {
    }

    /**
     * Stops whichever server runs in the server folder, as steps of {@code steps}: one an earlier session left running
     * ({@link #LEFTOVER_SERVER}, with the note server_start and dev_cycle report), then this session's ({@code stop}).
     * The caller has refused another live session's server ({@link ServerManager#checkNoOtherSession}).
     */
    Stopped stopAny(Steps steps, String stop) throws InterruptedException {
        String leftover = steps.note(LEFTOVER_SERVER, server::stopLeftover);
        boolean own = steps.run(stop, () -> server.stop(Duration.ofSeconds(30)));
        return new Stopped(own || leftover != null, leftover);
    }

    /** Declares the steps of {@link #startAndReport}, with what a failure of each leaves. */
    static Steps plan(Steps steps) {
        return steps.then(LEFTOVER_SERVER).then(TAKE_OVER_PACK).then(REFRESH_AGENT).then(START)
                .ifFails(TAKE_OVER_PACK, "the server is not started; if the earlier session's pack host was stopped, "
                        + ServerPropertiesFile.NAME + " may offer a pack no one serves: server_start again serves it")
                .ifFails(REFRESH_AGENT, "the server is not started; the installed agent jar is still the earlier "
                        + "one: server_start again updates it")
                .ifFails(START, "the server is not running; server_start starts it");
    }

    String stop(Args args) throws InterruptedException {
        // Checked first: another live session's server and its pack host are that session's (ADR 0037).
        server.checkNoOtherSession();
        String host = pack.stopLeftoverHost();
        if (server.stop(Duration.ofSeconds(30))) {
            // Detached earlier in this session: its pack host goes with it, as server_detach promises.
            return pack.stopOwnHost() ? "server stopped, and the pack host it kept running" : "server stopped";
        }
        String leftover;
        try {
            // The whole stop again, also on a leftover that resisted before: the user asked for exactly this (#408).
            leftover = server.stopLeftover(true);
        } catch (ToolException e) {
            throw host != null ? new ToolException(host + "\n" + e.getMessage()) : e;
        }
        return leftover != null ? leftover : host != null ? host : "server was not running";
    }

    /**
     * Checked first: running, server.properties writable when a pack was built and the platform reads it there. Then:
     * detach the server, hand the pack to a standalone host, offer it in server.properties (only where the platform
     * reads it, #718). A host that does not come up leaves the pack served by this session, which the message says;
     * the call still detaches.
     */
    String detach(Args args) throws IOException, InterruptedException {
        if (!server.running() || server.stopping()) {
            throw new ToolException(server.notRunning());
        }
        Platform platform = server.platform();
        boolean offers = platform.readsPackProperties();
        // The workspace's built pack: this session's last build, or the zip an earlier build left (#655).
        if (offers && pack.hasBuild() && Files.exists(serverProperties())) {
            OutputFile.check(serverProperties(), ServerPropertiesFile.NAME);
        }
        Steps steps = new Steps("server_detach").then(DETACH).then(KEEP_SERVING);
        if (offers) {
            steps.then(OFFER_PACK);
        }
        steps.ifFails(KEEP_SERVING, "the server is detached, but the pack is served by this session only, until it "
                        + "ends; server_stop then server_start and server_detach again keeps it up")
                .ifFails(OFFER_PACK, "the server and the pack host run on, but a restart outside MSC offers no pack");
        if (!steps.run(DETACH, server::detach)) {
            throw new ToolException(server.notRunning());
        }
        String java = ProcessHandle.current().info().command().orElse(config.javaCommand());
        // A host that does not come up is an answer, not a failure: this session keeps serving the pack.
        String[] noHost = new String[1];
        boolean hosting = steps.run(KEEP_SERVING, () -> {
            try {
                pack.current();
                return pack.keepServing(java, System.getProperty("java.class.path"));
            } catch (ToolException e) {
                noHost[0] = e.getMessage();
                return false;
            }
        });
        if (noHost[0] != null) {
            return "server detached: it keeps running after this session ends\n" + noHost[0];
        }
        String properties = "";
        ResourcePack.Built built = pack.lastBuilt();
        ResourcePack.Applied applied = pack.applied();
        if (hosting && !offers) {
            properties = "\n" + platform.displayName() + " does not read the pack from " + ServerPropertiesFile.NAME
                    + ", so a restart outside MSC does not offer it.";
        } else if (hosting && built != null && steps.run(OFFER_PACK, () -> PackProperties.write(platform,
                serverProperties(), built.url(), built.sha1(), applied != null && applied.required()))) {
            properties = "\n" + ServerPropertiesFile.NAME + " now offers the pack too, so a restart outside MSC keeps it.";
        }
        return "server detached: it keeps running after this session ends"
                + (hosting ? ", and so does the resource pack host" : " (no pack built yet, so no pack host)")
                + "." + properties + "\nWorld tools and server_command work until this session ends; the next session's "
                + "server_start, dev_cycle or server_stop stops the server (world saved) and the pack host.";
    }

    /**
     * The start, as steps of {@code steps}: stop a leftover server, take over a leftover pack host (serving the pack
     * again and pointing server.properties at it before the server reads the file), update the agent jar, start.
     * The caller has run {@link #checkStart}. {@code sweep}: just before the start, remove what interrupted calls left
     * in the scan folders, as housekeeping ({@link Steps#optional}) that changes no result unless it fails (dev_cycle
     * does that itself, before it deploys).
     */
    Report startAndReport(Steps steps, boolean sweep, int timeoutSeconds)
            throws IOException, InterruptedException {
        if (!steps.planned(START)) {
            plan(steps);
        }
        String leftoverServer = steps.note(LEFTOVER_SERVER, server::stopLeftover);
        String[] packNotes = new String[2];
        steps.note(TAKE_OVER_PACK, () -> {
            packNotes[0] = pack.stopLeftoverHost();
            boolean offered = pack.ours(PackProperties.url(server.platform(), serverProperties()));
            packNotes[1] = syncPackProperties();
            return packNotes[0] != null || offered ? "" : null;
        });
        String leftover = LogText.join(leftoverServer, packNotes[0]);
        String packNote = packNotes[1];
        boolean agentUpdated = steps.ran(REFRESH_AGENT, this::refreshAgent);
        // After the leftover stop, which may hold them open: an aside copy (x.jar.msc-removing, from cores before
        // #430) is a second copy of the mod on Quilt, which loads any file in mods/ (#434).
        List<Path> swept = new ArrayList<>();
        String unswept = !sweep ? "" : steps.optional(SWEEP,
                () -> swept.addAll(ScanFolders.sweep(config.serverDir(), ScanFolders.all(config.serverDir()))));
        ServerManager.StartResult r = steps.run(START, () -> server.start(Duration.ofSeconds(timeoutSeconds)));
        marks.started(r.logMark());
        List<String> lines = server.log().since(r.logMark());
        LogCondenser.Condensed analysis = LogCondenser.analyze(lines, Integer.MAX_VALUE);
        IntFunction<String> condensed = n -> LogCondenser.analyze(lines, n).text();
        Budget.Parts parts = new Budget.Parts();
        StringBuilder out = new StringBuilder();
        if (leftover != null) {
            out.append(leftover).append('\n');
        }
        if (!swept.isEmpty()) {
            out.append("removed ").append(swept.size()).append(swept.size() == 1 ? " file" : " files")
                    .append(" an interrupted earlier call left\n");
        }
        if (!unswept.isEmpty()) {
            out.append(unswept.strip()).append('\n');
        }
        if (packNote != null) {
            out.append(packNote).append('\n');
        }
        if (r.ready()) {
            out.append("server ready in ").append(LogText.seconds(r.millis()));
            CoreServer.AgentSession s = hub.current();
            out.append(s != null ? ", agent connected" : server.platform().proxy() ? ", a proxy: no world or agent"
                    : ", no agent connected (world tools unavailable)");
            if (agentUpdated) {
                out.append(", agent jar updated");
            }
            if (s != null) {
                out.append(groundLine());
            }
            String mode = modeLine();
            if (mode != null) {
                out.append('\n').append(mode);
            }
            // Only where Geyser can run: not after a platform switch that kept plugins/, nor behind a proxy (#590).
            String bedrock = BedrockRule.startLine(server.platform(), config.serverDir());
            if (bedrock != null) {
                out.append('\n').append(bedrock);
            }
            if (s != null) {
                out.append(packForAgent());
            }
            out.append('\n');
            if (analysis.clean()) {
                out.append("no errors or warnings")
                        .append(analysis.hidden() > 0 ? " (" + analysis.hidden() + " known-harmless hidden)" : "");
            } else {
                parts.add(out.toString()).cut(condensed);
                out.setLength(0);
            }
        } else if (r.exited()) {
            out.append(r.sawDone() ? "server exited right after it was ready, " : "server exited during startup ")
                    .append("after ").append(LogText.seconds(r.millis())).append('\n');
            // What died and the likely fix first (ADR 0040), then the condensed errors.
            CrashTriage.Result triage = CrashTriage.triage(lines, config.serverDir(), server.lastLaunch(),
                    server.platform());
            if (!triage.isEmpty()) {
                out.append(triage.render(null)).append('\n');
            }
            parts.add(out.toString()).cut(condensed);
            out.setLength(0);
            if (analysis.clean()) {
                String tail = LogText.tail(lines, 15);
                parts.add("\nlast lines:\n").cut(n -> Budget.tail(tail, n, "Use server_logs to see more."));
            }
        } else {
            out.append("server not ready after ").append(timeoutSeconds).append("s (still starting)\n");
            parts.add(out.toString()).cut(condensed);
            out.setLength(0);
        }
        return new Report(parts.add(out.toString()), r);
    }

    /**
     * Where the ground is, so the AI does not build in the air: dev worlds are superflat with the surface far below
     * y=64. It is the most common top block within 32 blocks of 0,0, so a build at 0,0 does not count as ground.
     * Best effort; a start never fails because of it.
     */
    private String groundLine() {
        try {
            Ops.ServerInfo info = hub.call(Ops.SERVER_INFO, new Ops.Empty());
            if (info.worlds().isEmpty()) {
                return "";
            }
            String world = info.worlds().getFirst();
            Ops.HeightmapResult h = hub.call(Ops.WORLD_HEIGHTMAP,
                    new Ops.HeightmapParams(world, new BlockPos(-GROUND_RADIUS, 0, -GROUND_RADIUS),
                            new BlockPos(GROUND_RADIUS - 1, 0, GROUND_RADIUS - 1)));
            int y = groundLevel(h.heights());
            return "\nworld " + world + ": ground is y=" + y + " (most common top block around 0,0; build from y="
                    + (y + 1) + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The most common height; the lower one on a tie, since builds stand on the ground. */
    static int groundLevel(int[] heights) {
        if (heights.length == 0) {
            throw new IllegalArgumentException("no heights");
        }
        Map<Integer, Integer> counts = new HashMap<>();
        int best = heights[0];
        for (int y : heights) {
            int n = counts.merge(y, 1, Integer::sum);
            int top = counts.get(best);
            if (n > top || (n == top && y < best)) {
                best = y;
            }
        }
        return best;
    }

    /** Guards {@link #sentTo} and {@link #sentLine}. */
    private final Object sending = new Object();
    /** The agent connection the workspace's pack was last sent to by {@link #packForAgent}, and what that said. */
    private CoreServer.AgentSession sentTo;
    private String sentLine = "";

    /**
     * Send the workspace's pack to the connected agent, once per connection: the start report calls this, and so does
     * the hub when an agent says its server is ready (whichever comes first sends; the other gets the same line).
     */
    String packForAgent() {
        synchronized (sending) {
            CoreServer.AgentSession s = hub.current();
            if (s == null) {
                return "";
            }
            if (s != sentTo) {
                sentLine = resendPack();
                sentTo = s;
            }
            return sentLine;
        }
    }

    /**
     * The workspace's pack (the one the pack tool built to send, in this session or an earlier one): a started server
     * never had it or has forgotten it, and a detached pack host was just stopped, so serve and send it. Best effort,
     * like the ground line. dev_cycle with the server kept running uses {@link #packIfChanged} instead.
     */
    String resendPack() {
        return sendPack(false);
    }

    /**
     * dev_cycle that keeps the server running (an unchanged jar, restart=false, or no jar; #690): the workspace's
     * pack, rebuilt, goes to the connected agent only when it differs from what that connection was last sent (#667),
     * so the players do not download and reload the same pack on every cycle. Says nothing when it was unchanged, or on
     * a proxy, which never has an agent (#694); with no agent connected anywhere else, says it is sent when one is (#698).
     */
    String packIfChanged() {
        synchronized (sending) {
            return sendPack(true);
        }
    }

    private String sendPack(boolean onlyChanged) {
        ResourcePack.Applied applied = pack.applied();
        // No agent connection, nothing to send to. A proxy never has one, so nothing is said there (#694); any other
        // server's agent gets the pack from packForAgent when it connects (#698).
        CoreServer.AgentSession to = hub.current();
        if (applied == null || to == null && hub.noAgentReason() != null) {
            return "";
        }
        if (to == null) {
            return "\nresource pack not sent: no server agent connected; it is sent when one connects";
        }
        try {
            ResourcePack.Snapshot before = pack.snapshot();
            Path file = serverProperties();
            byte[] offeredBefore = pack.ours(PackProperties.url(server.platform(), file)) ? Files.readAllBytes(file)
                    : null;
            ResourcePack.Built built = pack.build(applied.description());
            // The host now serves only this build: a server.properties that offers our pack follows it first.
            TextureTools.keepOffered(pack, server.platform(), file, built, applied.required());
            if (onlyChanged && pack.wasSent(to, built, applied.required())) {
                return "";
            }
            try {
                hub.call(Ops.PACK_APPLY, new Ops.PackParams(built.url(), built.sha1(), applied.required()));
                pack.sent(to, built, applied.required());
            } catch (RuntimeException e) {
                return "\nresource pack not sent (" + Steps.reason(e) + "); "
                        + TextureTools.takeBack(pack, before, file, offeredBefore);
            }
            return "\nresource pack sent: players who join get it";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (IOException | RuntimeException e) {
            return "\nresource pack not sent (" + e.getMessage() + "): run the pack tool";
        }
    }

    private String modeLine() {
        try {
            return ServerProperties.modeLine(serverProperties());
        } catch (IOException e) {
            return null;
        }
    }

    private Path serverProperties() {
        return config.serverDir().resolve(ServerPropertiesFile.NAME);
    }

    /**
     * A detached session left server.properties offering its pack, and the next start just stopped that pack host.
     * Serve the pack again, on the same port when it is free, and point server.properties at it before the server
     * reads the file; if the pack can no longer be built, stop offering it. Returns a note, or null.
     */
    private String syncPackProperties() {
        Path file = serverProperties();
        Platform platform = server.platform();
        if (!pack.ours(PackProperties.url(platform, file))) {
            return null;
        }
        try {
            ResourcePack.Applied applied = pack.applied();
            ResourcePack.Built built = pack.build(applied != null ? applied.description()
                    : LifecycleTools.DEFAULT_PACK_DESCRIPTION);
            PackProperties.write(platform, file, built.url(), built.sha1(),
                    applied != null ? applied.required() : PackProperties.required(platform, file));
            return null;
        } catch (IOException | RuntimeException e) {
            try {
                PackProperties.clear(platform, file);
            } catch (IOException ignored) {
                // Best effort: the server then shows players a failed pack download.
            }
            return ServerPropertiesFile.NAME + " no longer offers the resource pack (" + e.getMessage() + ")";
        }
    }

    /**
     * Before a start, replace an installed agent jar that differs from the one on offer, so a rebuilt agent
     * is picked up without another server_setup. Does nothing for a server that was never set up.
     */
    private boolean refreshAgent() throws IOException {
        Platform platform = server.platform();
        if (platform.addonDir() == null || server.running()) {
            return false;
        }
        Path installed = config.serverDir().resolve(platform.addonDir()).resolve(platform.agentJar());
        Path agent = setup.findAgentJar(platform);
        if (agent == null || !Files.exists(installed) || Files.mismatch(agent, installed) == -1) {
            return false;
        }
        // Whole or not at all: a failed copy leaves the earlier agent jar working, which the next start updates. The
        // temp file is outside the addon folder, where a loader could load it (#417).
        ScanFolders.copy(agent, installed, config.serverDir());
        return true;
    }
}
