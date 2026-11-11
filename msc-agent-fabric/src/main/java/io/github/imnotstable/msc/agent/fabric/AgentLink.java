package io.github.imnotstable.msc.agent.fabric;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.AgentInfo;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.transport.AgentClient;
import io.github.imnotstable.msc.protocol.transport.Connection;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.OpRouter;
import java.io.IOException;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;

/**
 * The connection to msc-core: connects off the server thread with backoff, reconnects when the
 * core drops it, and sends events. {@code server.ready} goes out once both the connection is up and
 * the server has finished starting, whichever comes last. Same behaviour as the Paper agent.
 */
final class AgentLink {
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int ATTEMPTS = 8;

    private final AgentInfo info;
    private final OpRouter router;
    private final Supplier<Optional<CoreAddress>> address;
    private final Logger log;
    private volatile AgentClient.Session session;
    private volatile boolean loaded;
    private volatile boolean readySent;
    private volatile boolean stopping;

    AgentLink(AgentInfo info, OpRouter router, Supplier<Optional<CoreAddress>> address, Logger log) {
        this.info = info;
        this.router = router;
        this.address = address;
        this.log = log;
    }

    /** Start connecting on a virtual thread. */
    void start() {
        Thread.ofVirtual().name("msc-agent-connect").start(this::connect);
    }

    /** The open session, or {@code null} when not connected. */
    AgentClient.Session session() {
        return session;
    }

    /** The server finished starting. */
    void serverLoaded() {
        loaded = true;
        maybeSendReady();
    }

    /** Send {@code server.stopping} and close the connection for good. */
    void stop() {
        stopping = true;
        AgentClient.Session s = session;
        if (s != null) {
            send(Events.SERVER_STOPPING, new JsonObject());
            s.connection().close(ErrorCodes.SHUTDOWN, "server stopping");
            session = null;
        }
    }

    /** Send an event if connected; dropped otherwise. */
    void send(String event, JsonObject data) {
        AgentClient.Session s = session;
        if (s == null) {
            return;
        }
        try {
            s.connection().sendEvent(event, data);
        } catch (IOException e) {
            log.debug("Could not send {}: {}", event, e.getMessage());
        }
    }

    private void connect() {
        long backoff = 500;
        for (int attempt = 1; attempt <= ATTEMPTS && !stopping; attempt++) {
            try {
                CoreAddress core = address.get().orElse(null);
                if (core == null) {
                    log.info("{} is not set; agent is idle.", Limits.ENV_CORE_ADDRESS);
                    return;
                }
                AgentClient.Session s = AgentClient.connect(core.host(), core.port(), core.token(), info,
                        router, new CloseListener(), CONNECT_TIMEOUT);
                session = s;
                log.info("Connected to msc-core, session {}", s.sessionId());
                maybeSendReady();
                return;
            } catch (OpException e) {
                log.error("msc-core refused the connection: {}", e.getMessage());
                return;
            } catch (IllegalArgumentException e) {
                log.error("Bad msc-core address: {}", e.getMessage());
                return;
            } catch (IOException | RuntimeException e) {
                log.warn("Could not reach msc-core (attempt {}): {}", attempt, e.getMessage());
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
                log.warn("Lost connection to msc-core{}; reconnecting.", cause == null ? "" : ": " + cause.getMessage());
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
