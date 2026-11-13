package io.github.imnotstable.msc.protocol.transport;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Ping;
import io.github.imnotstable.msc.protocol.message.Pong;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.message.Welcome;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * An open, handshaken link between the core and one agent. Both sides use the same class: either
 * side can send requests and events, and each answers the other's requests with its handler.
 *
 * <p>Threading: one platform daemon thread per connection only reads and decodes frames. It never
 * writes to the socket and never runs caller code, so it always keeps draining the socket even
 * while a large frame is being written in either direction. Responses complete their futures on
 * virtual threads, events reach the {@link Listener} in order on one virtual thread, pongs and
 * request handlers run on their own virtual threads.
 */
public final class Connection implements AutoCloseable {
    /** Callbacks for what arrives on the connection. */
    public interface Listener {
        default void onEvent(Event event) {
        }

        default void onClose(Throwable cause) {
        }
    }

    private static final Duration FAREWELL_WAIT = Duration.ofSeconds(2);

    private final Socket socket;
    private final FrameReader reader;
    private final FrameWriter writer;
    private final String label;
    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<Long>> pings = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ExecutorService events = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("msc-events").factory());
    private volatile Throwable closeCause;
    private RequestHandler handler = RequestHandler.none();
    private Listener listener = new Listener() {
    };

    Connection(Socket socket, FrameReader reader, FrameWriter writer, String label) {
        this.socket = socket;
        this.reader = reader;
        this.writer = writer;
        this.label = label;
    }

    static Connection over(Socket socket) throws IOException {
        return new Connection(socket,
                new FrameReader(socket.getInputStream(), Limits.MAX_FRAME_BYTES),
                new FrameWriter(socket.getOutputStream(), Limits.MAX_FRAME_BYTES),
                socket.getRemoteSocketAddress().toString());
    }

    /** Start reading. Call once, after the handshake. */
    void start(RequestHandler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
        Thread.ofPlatform().daemon().name("msc-conn-" + label).start(this::readLoop);
    }

    // ---- sending ----------------------------------------------------------------------------

    /** Typed call: the future completes with the decoded result, or fails with {@link OpException}. */
    public <P, R> CompletableFuture<R> call(OpSpec<P, R> op, P params, Duration timeout) {
        return request(op.name(), op.encodeParams(params), timeout).thenApply(response -> {
            if (!response.isOk()) {
                throw new OpException(response.error());
            }
            return op.decodeResult(response.result());
        });
    }

    /** Untyped request. The future fails with {@link java.util.concurrent.TimeoutException} after {@code timeout}. */
    public CompletableFuture<Response> request(String op, JsonObject params, Duration timeout) {
        long id = nextId.getAndIncrement();
        CompletableFuture<Response> future = new CompletableFuture<>();
        pending.put(id, future);
        future.whenComplete((r, e) -> pending.remove(id));
        try {
            send(new Request(id, op, params));
        } catch (IOException e) {
            future.completeExceptionally(e);
            return future;
        }
        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void sendEvent(String name, JsonObject data) throws IOException {
        send(new Event(name, data));
    }

    /** Round-trip time of a ping. */
    public CompletableFuture<Duration> ping(Duration timeout) {
        long nonce = System.nanoTime();
        CompletableFuture<Long> future = new CompletableFuture<>();
        pings.put(nonce, future);
        future.whenComplete((r, e) -> pings.remove(nonce));
        try {
            send(new Ping(nonce));
        } catch (IOException e) {
            future.completeExceptionally(e);
        }
        return future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .thenApply(sent -> Duration.ofNanos(System.nanoTime() - sent));
    }

    /** Read one raw frame during the handshake, before {@link #start} begins the read loop. */
    String readHandshakeFrame() throws IOException {
        String frame = reader.read();
        if (frame == null) {
            throw new ConnectionClosedException("connection " + label + " closed during handshake", null);
        }
        return frame;
    }

    void send(Message message) throws IOException {
        if (closed.get()) {
            throw new ConnectionClosedException("connection " + label + " is closed", closeCause);
        }
        writer.write(MessageCodec.encode(message));
    }

    // ---- receiving --------------------------------------------------------------------------

    private void readLoop() {
        Throwable cause = null;
        try {
            String frame;
            while ((frame = reader.read()) != null) {
                Message message = MessageCodec.decode(frame);
                if (!dispatch(message)) {
                    break;
                }
            }
        } catch (ProtocolException e) {
            cause = e;
            sendBeforeClose(new Goodbye(ErrorCodes.BAD_FRAME, e.getMessage()));
        } catch (IOException e) {
            cause = e;
        }
        shutdown(cause);
    }

    /** Returns false when the connection should stop reading. */
    private boolean dispatch(Message message) throws ProtocolException {
        switch (message) {
            case Request request -> Thread.ofVirtual().name("msc-req-" + request.op()).start(() -> answer(request));
            case Response response -> {
                CompletableFuture<Response> future = pending.get(response.id());
                if (future != null) {
                    // Completing runs the caller's dependent stages; keep them off the read thread.
                    Thread.ofVirtual().name("msc-resp").start(() -> future.complete(response));
                }
            }
            case Event event -> runEvent(() -> listener.onEvent(event));
            case Ping ping -> sendLater(new Pong(ping.nonce()));
            case Pong pong -> {
                CompletableFuture<Long> future = pings.get(pong.nonce());
                if (future != null) {
                    Thread.ofVirtual().name("msc-pong").start(() -> future.complete(pong.nonce()));
                }
            }
            case Goodbye goodbye -> {
                closeCause = new OpException(goodbye.code(), goodbye.reason());
                return false;
            }
            case Hello hello -> throw new ProtocolException("unexpected hello after handshake");
            case Welcome welcome -> throw new ProtocolException("unexpected welcome after handshake");
        }
        return true;
    }

    private void answer(Request request) {
        Response response;
        try {
            JsonElement result = handler.handle(request.op(), request.params());
            response = Response.ok(request.id(), result);
        } catch (OpException e) {
            response = Response.fail(request.id(), e.code(), e.detail());
        } catch (Throwable e) {
            // Errors too (NoSuchMethodError from API drift, StackOverflowError): the caller must not wait out its timeout.
            response = Response.fail(request.id(), ErrorCodes.INTERNAL, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        trySend(response);
    }

    /** Send without blocking the calling thread (used from the read thread). */
    private void sendLater(Message message) {
        Thread.ofVirtual().name("msc-send").start(() -> trySend(message));
    }

    /**
     * Send from the read thread and wait a bounded time for it, so a farewell reaches the socket
     * before {@link #shutdown} closes it. The write runs on its own thread: if the peer stops
     * reading, the read thread still gives up after {@link #FAREWELL_WAIT} and the close unblocks it.
     */
    private void sendBeforeClose(Message message) {
        Thread sender = Thread.ofVirtual().name("msc-send").start(() -> trySend(message));
        try {
            sender.join(FAREWELL_WAIT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void trySend(Message message) {
        try {
            send(message);
        } catch (IOException ignored) {
            // The read loop notices the broken connection and shuts down.
        }
    }

    // ---- closing ----------------------------------------------------------------------------

    public boolean isOpen() {
        return !closed.get();
    }

    /** Say goodbye and close. */
    public void close(String code, String reason) {
        trySend(new Goodbye(code, reason));
        shutdown(null);
    }

    @Override
    public void close() {
        close(ErrorCodes.SHUTDOWN, "closed");
    }

    private void shutdown(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (cause != null && closeCause == null) {
            closeCause = cause;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already closing.
        }
        ConnectionClosedException closedException = new ConnectionClosedException("connection " + label + " closed", closeCause);
        Throwable cause0 = closeCause;
        Thread.ofVirtual().name("msc-close").start(() -> {
            pending.values().forEach(f -> f.completeExceptionally(closedException));
            pings.values().forEach(f -> f.completeExceptionally(closedException));
        });
        // Runs after any events already queued, so a listener sees close last.
        runEvent(() -> listener.onClose(cause0));
        events.shutdown();
    }

    /** Queues listener work; after shutdown the connection is closed and late events are dropped. */
    private void runEvent(Runnable task) {
        try {
            events.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Closed while the read loop was still dispatching.
        }
    }
}
