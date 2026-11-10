package io.github.imnotstable.msc.core.agent;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * The core's end of msc-protocol. Listens for agents and keeps the newest one as "the" agent, whether
 * the core launched its server or it was already running.
 */
public final class AgentHub implements AutoCloseable {
    /** How long a world tool waits for an agent that is not connected yet. */
    public static final Duration CONNECT_WAIT = Duration.ofSeconds(30);
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final CoreServer server;
    private final String token;
    private CoreServer.AgentSession session;
    private long connectCount;
    private long readyCount;
    private volatile Duration connectWait = CONNECT_WAIT;

    public AgentHub(int port, String token) throws IOException {
        this.token = token;
        this.server = CoreServer.start(port, token, RequestHandler.none(), new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession s) {
                update(() -> {
                    session = s;
                    connectCount++;
                });
            }

            @Override
            public void event(CoreServer.AgentSession s, Event event) {
                if (Events.SERVER_READY.equals(event.name())) {
                    update(() -> readyCount++);
                }
            }

            @Override
            public void disconnected(CoreServer.AgentSession s, Throwable cause) {
                update(() -> {
                    if (session == s) {
                        session = null;
                    }
                });
            }
        }, Duration.ofSeconds(10));
    }

    private void update(Runnable change) {
        lock.lock();
        try {
            change.run();
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Value for {@code MSC_CORE_ADDRESS}. */
    public String address() {
        return server.address();
    }

    public String token() {
        return token;
    }

    /** Shorten the wait for an agent; tests use this. */
    public void connectWait(Duration wait) {
        this.connectWait = wait;
    }

    /** The connected agent, or null. */
    public CoreServer.AgentSession current() {
        lock.lock();
        try {
            return session != null && session.connection().isOpen() ? session : null;
        } finally {
            lock.unlock();
        }
    }

    /** Number of {@code server.ready} events seen so far; pass to {@link #awaitReady}. */
    public long readyCount() {
        lock.lock();
        try {
            return readyCount;
        } finally {
            lock.unlock();
        }
    }

    /** Wait until an agent reports {@code server.ready} more than {@code seenBefore} times. */
    public boolean awaitReady(long seenBefore, Duration timeout) throws InterruptedException {
        long nanos = timeout.toNanos();
        lock.lock();
        try {
            while (readyCount <= seenBefore) {
                if (nanos <= 0) {
                    return false;
                }
                nanos = changed.awaitNanos(nanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** The connected agent, waiting a little for one to arrive. */
    public CoreServer.AgentSession require() throws InterruptedException {
        long nanos = connectWait.toNanos();
        lock.lock();
        try {
            while (session == null || !session.connection().isOpen()) {
                if (nanos <= 0) {
                    throw new ToolException("no server agent connected. Start the server with server_start "
                            + "(the msc agent plugin must be in its plugins folder).");
                }
                nanos = changed.awaitNanos(nanos);
            }
            return session;
        } finally {
            lock.unlock();
        }
    }

    /** Run one operation on the agent and wait for the answer. */
    public <P, R> R call(OpSpec<P, R> op, P params) throws InterruptedException {
        CoreServer.AgentSession s = require();
        if (!s.agent().operations().contains(op.name())) {
            throw new ToolException("the " + s.agent().platform() + " agent does not support " + op.name());
        }
        try {
            return s.connection().call(op, params, CALL_TIMEOUT).get(CALL_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OpException op2) {
                throw op2;
            }
            throw new ToolException("agent call " + op.name() + " failed: " + e.getCause());
        } catch (TimeoutException e) {
            throw new ToolException("agent call " + op.name() + " timed out");
        }
    }

    @Override
    public void close() throws IOException {
        CoreServer.AgentSession s = current();
        if (s != null) {
            s.connection().close();
        }
        server.close();
    }
}
