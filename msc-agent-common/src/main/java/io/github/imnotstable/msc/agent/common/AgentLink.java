package io.github.imnotstable.msc.agent.common;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The connection to msc-core: connects off the server thread with backoff, reconnects when the
 * core drops it, and sends events. {@code server.ready} goes out once both the connection is up and
 * the server has finished starting, whichever comes last.
 */
public final class AgentLink {
    private static final int ATTEMPTS = 8;

    private final AgentInfo info;
    private final OpRouter router;
    private final Supplier<Optional<CoreAddress>> address;
    private final AgentLog log;
    private volatile AgentClient.Session session;
    private volatile boolean loaded;
    private volatile boolean readySent;
    private volatile boolean stopping;

    /** Connects to the core named by the environment or system properties ({@link CoreAddress#resolve()}). */
    public AgentLink(AgentInfo info, OpRouter router, AgentLog log) {
        this(info, router, CoreAddress::resolve, log);
    }

    public AgentLink(AgentInfo info, OpRouter router, Supplier<Optional<CoreAddress>> address, AgentLog log) {
        this.info = info;
        this.router = router;
        this.address = address;
        this.log = log;
    }

    /** Start connecting on a virtual thread. */
    public void start() {
        Thread.ofVirtual().name("msc-agent-connect").start(this::connect);
    }

    /** The open session, or {@code null} when not connected. */
    public AgentClient.Session session() {
        return session;
    }

    /** The server finished starting. */
    public void serverLoaded() {
        loaded = true;
        maybeSendReady();
    }

    /** Send {@code server.stopping} and close the connection for good. Later calls do nothing. */
    public void stop() {
        stopping = true;
        AgentClient.Session s = session;
        if (s != null) {
            send(Events.SERVER_STOPPING, new JsonObject());
            s.connection().close(ErrorCodes.SHUTDOWN, "server stopping");
            session = null;
        }
    }

    public boolean stopping() {
        return stopping;
    }

    /** Send an event if connected; dropped otherwise, and logged as a warning if it cannot be sent at all. */
    public void send(String event, JsonObject data) {
        AgentClient.Session s = session;
        if (s == null) {
            return;
        }
        try {
            s.connection().sendEvent(event, data);
        } catch (ProtocolException e) {
            // The connection is fine but this event could not go (too large, not encodable): never drop it unseen.
            log.warn("Could not send " + event + ": " + e.getMessage());
        } catch (IOException e) {
            log.debug("Could not send " + event + ": " + e.getMessage());
        }
    }

    /** {@code player.join} for a player. */
    public void playerJoined(String name, UUID uuid) {
        send(Events.PLAYER_JOIN, player(name, uuid));
    }

    /** {@code player.quit} for a player. */
    public void playerQuit(String name, UUID uuid) {
        send(Events.PLAYER_QUIT, player(name, uuid));
    }

    private static JsonObject player(String name, UUID uuid) {
        JsonObject data = new JsonObject();
        data.addProperty("name", name);
        data.addProperty("uuid", uuid.toString());
        return data;
    }

    private void connect() {
        long backoff = 500;
        for (int attempt = 1; attempt <= ATTEMPTS && !stopping; attempt++) {
            try {
                CoreAddress core = address.get().orElse(null);
                if (core == null) {
                    log.info(Limits.ENV_CORE_ADDRESS + " is not set; agent is idle.");
                    return;
                }
                AgentClient.Session s = AgentClient.connect(core.host(), core.port(), core.token(), info,
                        router, new CloseListener(), AgentDefaults.CONNECT_TIMEOUT);
                session = s;
                log.info("Connected to msc-core, session " + s.sessionId());
                maybeSendReady();
                return;
            } catch (OpException e) {
                log.error("msc-core refused the connection: " + e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                log.error("Bad msc-core address: " + e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not reach msc-core (attempt " + attempt + "): " + e.getMessage());
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            backoff = Math.min(backoff * 2, 5000);
        }
    }

    private final class CloseListener implements Connection.Listener {
        @Override
        public void onClose(Throwable cause) {
            session = null;
            if (!stopping) {
                log.warn("Lost connection to msc-core" + (cause == null ? "" : ": " + cause.getMessage()) + "; reconnecting.");
                readySent = false;
                Thread.ofVirtual().name("msc-agent-reconnect").start(AgentLink.this::connect);
            }
        }
    }

    private synchronized void maybeSendReady() {
        if (loaded && !readySent && session != null) {
            readySent = true;
            send(Events.SERVER_READY, new JsonObject());
        }
    }
}
