package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.agent.AgentHub;
import io.github.imnotstable.msc.core.server.Platform;
import io.github.imnotstable.msc.core.server.ServerManager;
import io.github.imnotstable.msc.core.texture.PackProperties;
import io.github.imnotstable.msc.core.texture.ResourcePack;
import io.github.imnotstable.msc.process.ServerPropertiesFile;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Publishing the workspace's pack (ADR 0051): the one function every call that changes the build, or who must serve
 * it, ends with: {@code pack}, dev_cycle's rebuild, a start's send, {@code server_detach}, a switch away and back, and
 * the session end. It goes by the state alone (the build, and whether the server is detached), never by what ran
 * before, so the order of those calls does not matter. In this order:
 * <ol>
 *   <li>serve: the standalone host serves the build while the server is detached, else this session does
 *       ({@link ResourcePack#serve}); a host that does not come up leaves it served by this session, and says why;</li>
 *   <li>offer: where the platform reads {@code server.properties}, the file offers the build while it outlives the
 *       session; once it offers this workspace's pack it keeps following the build (ADR 0017) while the server ends
 *       with the session, and a detached server's offer is withdrawn while no standalone host serves the build past
 *       it (#747); with no build left, such an offer is cleared;</li>
 *   <li>send: the connected agent gets the build, unless it already has exactly this one ({@link Send}).</li>
 * </ol>
 */
public final class PackPublisher {
    /** Whether a publish sends the build to the connected agent. */
    public enum Send {
        /** Never: no agent is connected yet (a start), or the call was asked not to. */
        NEVER,
        /** When the connected agent was not last sent exactly this build, and the workspace has a pack to send. */
        IF_NEW,
        /** Whenever an agent is connected. */
        ALWAYS
    }

    static final String SERVE = "serve the pack";
    static final String OFFER = "offer the pack in " + ServerPropertiesFile.NAME;
    static final String SEND = "send it to the server's players";

    /** What a publish did to the offer in {@code server.properties}. */
    public enum Offer {
        /** Not touched: the platform does not read it, or the file offers no pack of this workspace's. */
        NONE,
        /** It offers the build now. */
        OFFERED,
        /** It offered this workspace's pack, but no longer: no standalone host serves the build past the session. */
        WITHDRAWN,
        /** It offered this workspace's pack, but no longer: there is no build. */
        CLEARED
    }

    /**
     * What a publish did: the build served (null when there is none), whether the standalone host serves it past the
     * session, why that host did not come up (null when it did or was not needed), what became of the offer in
     * {@code server.properties}, and the agent's answer when it was sent (else null). Every answer line on the pack
     * host and the offer is made here, from what outlives the session (#747), so no answer promises a host or an
     * offer that ends with the session.
     */
    public record Published(ResourcePack.Built built, boolean outlives, String trouble, Offer offer,
                            Ops.PackResult sent) {
        /** True when {@code server.properties} offers the build now. */
        public boolean offered() {
            return offer == Offer.OFFERED;
        }

        /**
         * The line on a standalone host that did not come up, or null. {@code leaving}: the in-process host stops
         * right after (a switch away), so nothing serves the pack. It says when the offer was withdrawn.
         */
        String hosting(boolean leaving) {
            String withdrawn = offer != Offer.WITHDRAWN ? null
                    : ServerPropertiesFile.NAME + " offers no pack until a standalone host serves it";
            if (trouble == null) {
                return withdrawn;
            }
            return trouble + (leaving ? ", so nothing serves the resource pack until the switch back"
                    : ", so the resource pack is served by this session only, until it ends")
                    + (withdrawn == null ? "" : "; " + withdrawn);
        }

        /**
         * server_detach's line on a restart outside MSC, or null: the offer only when the standalone host serves it
         * past the session; on a platform that does not read the file, that it is not offered there.
         */
        String restart(Platform platform) {
            if (!outlives) {
                return null;
            }
            if (offered()) {
                return ServerPropertiesFile.NAME + " now offers the pack too, so a restart outside MSC keeps it.";
            }
            return platform.readsPackProperties() ? null : platform.displayName() + " does not read the pack from "
                    + ServerPropertiesFile.NAME + ", so a restart outside MSC does not offer it.";
        }

        /** server_detach's first line, on what keeps running after this session ends. */
        String detached() {
            return "server detached: it keeps running after this session ends"
                    + (built == null ? " (no pack built yet; the pack tool starts a pack host that does too)"
                    : outlives ? ", and so does the resource pack host" : "") + ".";
        }

        /** What the next session's start or stop stops beside the server: the pack host, when one outlives this one. */
        String alsoStops() {
            return outlives ? " and the pack host" : "";
        }
    }

    /** A part of a publish. */
    @FunctionalInterface
    private interface Part<T> {
        T get() throws IOException, InterruptedException;
    }

    private final ResourcePack pack;
    private final ServerManager server;
    private final AgentHub hub;
    private final Path properties;

    public PackPublisher(ResourcePack pack, ServerManager server, AgentHub hub, Path properties) {
        this.pack = pack;
        this.server = server;
        this.hub = hub;
        this.properties = properties;
    }

    /**
     * True when a publish now may write {@code server.properties}: the platform reads the pack there, the file exists,
     * and the server is detached or the file offers this workspace's pack. Checked first by the calls that publish as
     * steps, so the file is known to be writable.
     */
    boolean offers() {
        Platform platform = server.platform();
        return platform.readsPackProperties() && Files.isRegularFile(properties)
                && (server.detached() || pack.ours(PackProperties.url(platform, properties)));
    }

    /** {@code server.properties} as it is when a publish may change it ({@link #offers}), to put back; else null. */
    byte[] propertiesBefore() throws IOException {
        return offers() ? Files.readAllBytes(properties) : null;
    }

    /**
     * Publish the workspace's current build: serve, offer, send, each as a step of {@code steps} when it is given.
     * {@code required}: the pack tool's own; null takes the workspace's pack to send, else what the file says.
     */
    Published publish(Steps steps, Send send, Boolean required) throws IOException, InterruptedException {
        ResourcePack.Serving serving = step(steps, SERVE, pack::serve);
        ResourcePack.Built built = serving.built();
        Platform platform = server.platform();
        ResourcePack.Applied applied = pack.applied();
        boolean req = required != null ? required : applied != null ? applied.required()
                : PackProperties.required(platform, properties);
        boolean ours = pack.ours(PackProperties.url(platform, properties));
        Offer offer = Offer.NONE;
        if (built != null && (serving.outlives() || ours && !server.detached())) {
            // Served past the session, or followed for the next MSC start of a server that ends with the session.
            if (ran(steps, OFFER, () -> PackProperties.write(platform, properties, built.url(), built.sha1(), req))) {
                offer = Offer.OFFERED;
            }
        } else if (ours) {
            // No build left, or a detached server, which outlives the session, would offer a URL that does not (#747).
            if (ran(steps, OFFER, () -> PackProperties.clear(platform, properties))) {
                offer = built == null ? Offer.CLEARED : Offer.WITHDRAWN;
            }
        }
        Ops.PackResult sent = null;
        CoreServer.AgentSession to = hub.current();
        if (built != null && to != null && (send == Send.ALWAYS
                || send == Send.IF_NEW && applied != null && !pack.wasSent(to, built, req))) {
            sent = step(steps, SEND, () -> {
                Ops.PackResult r = hub.call(Ops.PACK_APPLY, new Ops.PackParams(built.url(), built.sha1(), req));
                // So a later publish does not send this connection the same pack again (#667).
                pack.sent(to, built, req);
                return r;
            });
        }
        return new Published(built, serving.outlives(), serving.trouble(), offer, sent);
    }

    /**
     * Publish for a switch away ({@code leaving}, before the in-process host stops) or back, or the session end: never
     * fails. Returns a line when the pack is not served as it should be, else null.
     */
    public String settle(boolean leaving) {
        try {
            return publish(null, Send.IF_NEW, null).hosting(leaving);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException e) {
            return "the resource pack was not published (" + Steps.reason(e) + ")";
        }
    }

    private static <T> T step(Steps steps, String name, Part<T> part) throws IOException, InterruptedException {
        return steps == null ? part.get() : steps.run(name, part::get);
    }

    private static boolean ran(Steps steps, String name, Part<Boolean> part) throws IOException, InterruptedException {
        return steps == null ? part.get() : steps.ran(name, part::get);
    }
}
