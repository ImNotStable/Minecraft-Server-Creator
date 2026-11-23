package io.github.imnotstable.msc.protocol.transport;

import com.google.gson.JsonParseException;
import com.google.gson.JsonObject;
import io.github.imnotstable.msc.protocol.Limits;
import io.github.imnotstable.msc.protocol.ProtocolException;
import io.github.imnotstable.msc.protocol.message.Challenge;
import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.message.Event;
import io.github.imnotstable.msc.protocol.message.Goodbye;
import io.github.imnotstable.msc.protocol.message.Hello;
import io.github.imnotstable.msc.protocol.message.Message;
import io.github.imnotstable.msc.protocol.message.MessageCodec;
import io.github.imnotstable.msc.protocol.message.Ping;
import io.github.imnotstable.msc.protocol.message.Pong;
import io.github.imnotstable.msc.protocol.message.Proof;
import io.github.imnotstable.msc.protocol.message.RawJson;
import io.github.imnotstable.msc.protocol.message.Request;
import io.github.imnotstable.msc.protocol.message.Response;
import io.github.imnotstable.msc.protocol.message.Welcome;
import io.github.imnotstable.msc.protocol.op.OpSpec;
import io.github.imnotstable.msc.protocol.op.PartialEdit;
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
    /** Seals and opens every frame once the handshake is done (ADR 0092); null before. */
    private volatile FrameSeal seal;
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

    /** From now on every frame either way is sealed: set once, after the welcome, before {@link #start}. */
    void seal(FrameSeal seal) {
        this.seal = seal;
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
            try {
                return op.decodeResult(response.result());
            } catch (JsonParseException e) {
                // The read loop skips over a result without parsing it; a malformed one fails only this call.
                throw new OpException(ErrorCodes.BAD_FRAME, "malformed " + op.name() + " result: " + e.getMessage());
            }
        });
    }

    /**
     * Untyped request. The future fails with {@link java.util.concurrent.TimeoutException} after {@code timeout}, and
     * at once with {@link FrameTooLargeException} or another {@link ProtocolException} when the request cannot be sent.
     */
    public CompletableFuture<Response> request(String op, JsonObject params, Duration timeout) {
        return request(op, params == null ? null : RawJson.of(params), timeout);
    }

    /** Untyped request with params already as JSON text; see {@link #request(String, JsonObject, Duration)}. */
    public CompletableFuture<Response> request(String op, RawJson params, Duration timeout) {
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

    /**
     * Send an event. Throws {@link FrameTooLargeException} or another {@link ProtocolException} when it cannot be
     * sent while the connection is fine, and another {@link IOException} when the connection is broken.
     */
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

    /**
     * Read one raw frame during the handshake, before {@link #start} begins the read loop. At most
     * {@link Limits#MAX_HANDSHAKE_FRAME_BYTES}: before the peer has proved itself it gets no 16 MiB buffer.
     */
    String readHandshakeFrame() throws IOException {
        String frame = reader.read(Limits.MAX_HANDSHAKE_FRAME_BYTES);
        if (frame == null) {
            throw new ConnectionClosedException("connection " + label + " closed during handshake", null);
        }
        return frame;
    }

    /**
     * Encode and write one message. A {@link ProtocolException} means nothing was written and the connection is
     * fine: the message could not be encoded, or its frame is over the limit ({@link FrameTooLargeException}, naming
     * the message). Any other {@link IOException} means the connection is closed or broken.
     */
    void send(Message message) throws IOException {
        if (closed.get()) {
            throw new ConnectionClosedException("connection " + label + " is closed", closeCause);
        }
        String frame;
        try {
            frame = MessageCodec.encode(message);
        } catch (RuntimeException e) {
            throw new ProtocolException("could not encode " + describe(message) + ": " + e.getMessage(), e);
        }
        try {
            writer.write(frame, seal);
        } catch (FrameTooLargeException e) {
            throw new FrameTooLargeException(describe(message), e.bytes(), e.limit());
        }
    }

    private static String describe(Message message) {
        return switch (message) {
            case Request r -> "request " + r.op();
            case Response r -> "answer to request " + r.id();
            case Event e -> "event " + e.name();
            default -> message.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        };
    }

    // ---- receiving --------------------------------------------------------------------------

    private void readLoop() {
        Throwable cause = null;
        try {
            String frame;
            while ((frame = reader.read()) != null) {
                FrameSeal s = seal;
                Message message = MessageCodec.decode(s == null ? frame : s.open(frame));
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
                // Removed here, on the read thread, so the close sweep in shutdown() (which this thread
                // runs after dispatching) cannot fail a future whose response has already arrived.
                CompletableFuture<Response> future = pending.remove(response.id());
                if (future != null) {
                    // Completing runs the caller's dependent stages; keep them off the read thread.
                    Thread.ofVirtual().name("msc-resp").start(() -> future.complete(response));
                }
            }
            case Event event -> runEvent(() -> listener.onEvent(event));
            case Ping ping -> sendLater(new Pong(ping.nonce()));
            case Pong pong -> {
                CompletableFuture<Long> future = pings.remove(pong.nonce());
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
            case Challenge challenge -> throw new ProtocolException("unexpected challenge after handshake");
            case Proof proof -> throw new ProtocolException("unexpected proof after handshake");
        }
        return true;
    }

    private void answer(Request request) {
        Response response;
        try {
            RawJson result = handler.handle(request.op(), request.params());
            response = Response.ok(request.id(), result);
        } catch (OpException e) {
            response = Response.fail(request.id(), e.code(), e.detail());
        } catch (Throwable e) {
            // Errors too (NoSuchMethodError from API drift, StackOverflowError): the caller must not wait out its timeout.
            response = Response.fail(request.id(), ErrorCodes.INTERNAL, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            send(response);
        } catch (ProtocolException e) {
            // Nothing was written and the connection is fine: say why instead, or the caller waits out its timeout.
            trySend(unsendable(request.op(), response, e));
        } catch (IOException ignored) {
            // The read loop notices the broken connection and shuts down.
        }
    }

    /**
     * The small error that stands in for an answer that could not be sent. An error answer keeps its code, the
     * start of its message and its {@link PartialEdit} undo id, so a failed write's changed blocks stay undoable.
     */
    private static Response unsendable(String op, Response response, ProtocolException e) {
        if (!response.isOk()) {
            String message = response.error().message();
            String cut = Limits.cut(message, Limits.MAX_TEXT_CHARS);
            String kept = PartialEdit.editId(message).map(id -> PartialEdit.note(cut, id)).orElse(cut);
            return Response.fail(response.id(), response.error().code(), kept);
        }
        if (e instanceof FrameTooLargeException tooLarge) {
            return Response.fail(response.id(), ErrorCodes.TOO_LARGE, "the " + op + " result is " + tooLarge.bytes()
                    + " bytes, over the " + tooLarge.limit() + "-byte frame limit; ask for less");
        }
        return Response.fail(response.id(), ErrorCodes.INTERNAL,
                Limits.cut("the " + op + " result could not be sent: " + e.getMessage(), Limits.MAX_TEXT_CHARS));
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
