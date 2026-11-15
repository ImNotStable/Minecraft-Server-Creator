package io.github.imnotstable.msc.core.agent;

import io.github.imnotstable.msc.core.tool.PortPatience;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.op.Events;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import io.github.imnotstable.msc.protocol.transport.ConnectionClosedException;
import io.github.imnotstable.msc.protocol.transport.CoreServer;
import io.github.imnotstable.msc.protocol.transport.OpException;
import io.github.imnotstable.msc.protocol.transport.RequestHandler;
import java.io.IOException;
import java.io.InterruptedIOException;
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
    /**
     * Longer than every wait an agent may make in one operation ({@link Limits#CORE_CALL_TIMEOUT}), so the agent's
     * answer (or its timeout error, with the undo id of a write's changed blocks) wins.
     */
    public static final Duration CALL_TIMEOUT = Limits.CORE_CALL_TIMEOUT;
    /** The refusal of a world call while this session has no server running. */
    public static final String NOT_RUNNING = "the server is not running. Start it with server_start, or dev_cycle to "
            + "build and deploy first.";
    /** The refusal of a world call while the server is on its way down: its agent does not come back (#703). */
    public static final String STOPPING = "the server is stopping. Start it again with server_start, or dev_cycle to "
            + "build and deploy first.";

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final CoreServer server;
    private final String token;
    private CoreServer.AgentSession session;
    private long readyCount;
    /**
     * Counts every change under {@link #lock}, each signalled on {@link #changed}: {@link #require} asks
     * {@link #refusal} without the lock and then waits only while this is unchanged, so no change in between is lost
     * (#710).
     */
    private long generation;
    /**
     * The last agent said {@code server.stopping}: the run {@link #endingRun} is ending, so none comes back until a new
     * one connects. While it is set, none is connected and that run is still the last one launched, {@link #refusal}
     * answers {@link #notRunning}, which says "stopping" only while this session's server process still runs (#711).
     */
    private boolean ending;
    /** The run ({@link #lastRun}) that {@link #ending} belongs to: a later launch is never stopping for it (#715). */
    private Object endingRun;
    /** Set by {@link #close()} under {@link #lock}: no agent can arrive after it, so nobody waits for one. */
    private boolean closed;
    private volatile Duration connectWait = CONNECT_WAIT;
    private volatile java.util.function.BooleanSupplier agentExpected = () -> true;
    private volatile java.util.function.Supplier<String> noAgentReason = () -> null;
    private volatile java.util.function.Supplier<String> notRunning = () -> NOT_RUNNING;
    private volatile java.util.function.Supplier<Object> lastRun = () -> null;
    private volatile Runnable onReady = () -> { };

    public AgentHub(int port, String token) throws IOException {
        this.token = token;
        CoreServer.AgentListener listener = new CoreServer.AgentListener() {
            @Override
            public void connected(CoreServer.AgentSession s) {
                boolean late;
                lock.lock();
                try {
                    // A handshake that raced close(): CoreServer may finish one after its socket closed.
                    late = closed;
                    if (!late) {
                        session = s;
                        ending = false;
                        generation++;
                        changed.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
                if (late) {
                    s.connection().close();
                }
            }

            @Override
            public void event(CoreServer.AgentSession s, Event event) {
                if (Events.SERVER_STOPPING.equals(event.name())) {
                    Object run = lastRun.get();
                    update(() -> {
                        ending = true;
                        endingRun = run;
                    });
                } else if (Events.SERVER_READY.equals(event.name())) {
                    update(() -> readyCount++);
                    onReady.run();
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
        };
        // The port may have been let go a moment ago: by the workspace a switch just closed (#721), or by a core that
        // is just exiting.
        try {
            this.server = PortPatience.bind(port, PortPatience.PATIENCE,
                    () -> CoreServer.start(port, token, RequestHandler.none(), listener, Duration.ofSeconds(10)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while waiting for port " + port);
        }
    }

    private void update(Runnable change) {
        lock.lock();
        try {
            change.run();
            generation++;
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

    /**
     * Whether an agent can still arrive. When this says no and none is connected, {@link #require} fails at once
     * instead of waiting: the core started no server, so nothing will connect.
     */
    public void agentExpected(java.util.function.BooleanSupplier expected) {
        this.agentExpected = expected;
    }

    /**
     * The refusal when no agent is expected ({@link #agentExpected}): why this session has no server to reach (#705).
     * Asked without the hub lock, like {@link #agentExpected}.
     */
    public void notRunning(java.util.function.Supplier<String> why) {
        this.notRunning = why;
    }

    /**
     * Wakes a caller waiting for an agent, so it asks {@link #agentExpected} again: the server process exited or
     * began to stop, which no session change signals (#703).
     */
    public void serverChanged() {
        update(() -> { });
    }

    /**
     * Which server run is the current one: the server process this session launched last, running or not, or null.
     * It changes in the same critical section as a launch, and is read without the hub lock. A {@code server.stopping}
     * belongs to the run it came in, so a new run is never "stopping" for the last one (#715).
     */
    public void lastRun(java.util.function.Supplier<Object> run) {
        this.lastRun = run;
    }

    /**
     * Why no agent will ever connect (a proxy runs none), or null. When it says so and none is connected,
     * {@link #require} fails at once with that line (ADR 0045).
     */
    public void noAgentReason(java.util.function.Supplier<String> reason) {
        this.noAgentReason = reason;
    }

    /**
     * Run {@code listener} each time an agent says its server is ready. It runs on the connection's thread, before
     * the event's waiters see it, so it must hand any work (an op call) to another thread.
     */
    public void onReady(Runnable listener) {
        this.onReady = listener;
    }

    /** Why no agent will ever connect, or null when one can. */
    public String noAgentReason() {
        return noAgentReason.get();
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

    /**
     * The connected agent, waiting a little for one to arrive. It waits only while one can still come (the server is
     * starting, or its agent reconnects): a server that is stopping or gone fails at once ({@link #refusal}, #703).
     */
    public CoreServer.AgentSession require() throws InterruptedException {
        long deadline = System.nanoTime() + connectWait.toNanos();
        while (true) {
            long seen;
            lock.lock();
            try {
                if (session != null && session.connection().isOpen()) {
                    return session;
                }
                seen = generation;
            } finally {
                lock.unlock();
            }
            String why = refusal();
            if (why != null) {
                throw new ToolException(why);
            }
            lock.lock();
            try {
                if (generation != seen) {
                    continue; // something changed since the refusal was asked: ask again (#710)
                }
                long nanos = deadline - System.nanoTime();
                if (nanos <= 0) {
                    throw new ToolException("no server agent connected. Start the server with server_start "
                            + "(server_setup installs the msc agent; a Minestom project has it built in).");
                }
                changed.awaitNanos(nanos);
            } finally {
                lock.unlock();
            }
        }
    }

    /** Run one operation on the agent and wait for the answer. */
    public <P, R> R call(OpSpec<P, R> op, P params) throws InterruptedException {
        return call(require(), op, params);
    }

    /** As {@link #call(OpSpec, Object)}, on {@code s} only: never on an agent that connected after it (#702). */
    public <P, R> R call(CoreServer.AgentSession s, OpSpec<P, R> op, P params) throws InterruptedException {
        if (!s.agent().operations().contains(op.name())) {
            throw new ToolException("the " + s.agent().platform() + " agent does not support " + op.name()
                    + "; server_command still works");
        }
        try {
            return s.connection().call(op, params, CALL_TIMEOUT).get(CALL_TIMEOUT.toSeconds() + 5, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof OpException op2) {
                throw op2;
            }
            if (e.getCause() instanceof TimeoutException) {
                throw timedOut(op.name());
            }
            if (e.getCause() instanceof ConnectionClosedException) {
                // A transport class name is no answer: the server went away (#708).
                throw new ToolException("the server's agent connection closed during " + op.name()
                        + ": the server stopped or restarted");
            }
            throw new ToolException("agent call " + op.name() + " failed: " + e.getCause());
        } catch (TimeoutException e) {
            throw timedOut(op.name());
        }
    }

    /**
     * Why no agent can come now, or null while one may: the one answer for {@link #require} and for a tool that does
     * not wait (player_test). Asked without the hub lock: the answer may need another lock (ServerManager's monitor),
     * whose holder may be waiting for this one. After a {@code server.stopping} the answer is {@link #notRunning}'s,
     * as msc_status says it: "stopping" while this session's server still exits, else why none runs (#711). The
     * answer is about one run: a server launched while it was asked is a new run, whose agent may be waited for (#715).
     */
    public String refusal() {
        Object run = lastRun.get();
        boolean stopping;
        lock.lock();
        try {
            if (closed) {
                throw ToolException.workspaceClosed("no server agent can connect any more");
            }
            stopping = ending && endingRun == run;
        } finally {
            lock.unlock();
        }
        String never = noAgentReason.get();
        if (never != null) {
            return never;
        }
        String why = stopping || !agentExpected.getAsBoolean() ? notRunning.get() : null;
        return why != null && lastRun.get() != run ? null : why;
    }

    private static ToolException timedOut(String op) {
        return new ToolException("the server did not answer " + op + " within " + CALL_TIMEOUT.toSeconds()
                + "s. Try a smaller region, or check server_logs: the server may be overloaded or stopped.");
    }

    /** Close the agent session and stop listening. Wakes a caller waiting for an agent, which then fails at once. */
    @Override
    public void close() throws IOException {
        update(() -> closed = true);
        CoreServer.AgentSession s = current();
        if (s != null) {
            s.connection().close();
        }
        server.close();
    }
}
