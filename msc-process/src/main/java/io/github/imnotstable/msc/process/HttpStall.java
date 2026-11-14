package io.github.imnotstable.msc.process;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one rule for every HTTP read in the product (#454): a request may take as long as it needs while bytes keep
 * coming, and fails once none have come for the stall limit. {@link HttpRequest#timeout} cannot be that rule: the JDK
 * applies it only until the response headers arrive, so a server that goes silent in the middle of the body would
 * keep {@link HttpClient#send} waiting forever. msc-core, msc-bench and msc-bot send their requests through here, so
 * it lives in msc-process, which they all depend on.
 */
public final class HttpStall {
    /** The stall limit of downloads: no byte for this long and the download is given up. */
    public static final Duration LIMIT = Duration.ofSeconds(30);
    /** How long to wait, after giving a request up, for its body handler to let go of what it holds (a file). */
    private static final Duration RELEASE_WAIT = Duration.ofSeconds(5);

    private HttpStall() {
    }

    /** A request given up because no byte came for the stall limit; its message is one line for the user. */
    public static final class StalledException extends IOException {
        private static final long serialVersionUID = 1L;

        StalledException(URI uri, Duration limit, String retry) {
            super("download from " + uri.getHost() + " stalled (no data for " + seconds(limit)
                    + "); check the network connection and " + retry);
        }

        private static String seconds(Duration limit) {
            return limit.toMillis() % 1000 == 0 ? limit.toSeconds() + " s" : limit.toMillis() + " ms";
        }
    }

    /**
     * Like {@code http.send(request, handler)}, but gives up with a {@link StalledException} (whose message ends with
     * {@code retry}, such as "run server_setup again") once no byte, header or body, has arrived for {@code limit}.
     * The request is then cancelled and its body handler closed, so a file it wrote can be removed. An interrupt
     * cancels it as well.
     */
    public static <T> HttpResponse<T> send(HttpClient http, HttpRequest request, HttpResponse.BodyHandler<T> handler,
                                           Duration limit, String retry) throws IOException, InterruptedException {
        long limitNanos = limit.toNanos();
        AtomicLong last = new AtomicLong(System.nanoTime());
        AtomicReference<Watched<T>> body = new AtomicReference<>();
        CompletableFuture<HttpResponse<T>> future = http.sendAsync(request, info -> {
            last.set(System.nanoTime());
            Watched<T> watched = new Watched<>(handler.apply(info), last);
            body.set(watched);
            return watched;
        });
        try {
            while (true) {
                long idle = System.nanoTime() - last.get();
                if (idle >= limitNanos) {
                    giveUp(future, body.get());
                    throw new StalledException(request.uri(), limit, retry);
                }
                try {
                    return future.get(limitNanos - idle, TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // Bytes may have come meanwhile: the loop measures the silence again.
                }
            }
        } catch (InterruptedException e) {
            giveUp(future, body.get());
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException(cause);
        }
    }

    /** Cancels the exchange and waits a moment for the body handler to be told, so it closes what it holds. */
    private static void giveUp(CompletableFuture<?> future, Watched<?> body) {
        future.cancel(true);
        if (body != null) {
            try {
                body.ended.get(RELEASE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException e) {
                // Best effort: the file is removed anyway where the platform allows it.
            }
        }
    }

    /** Passes everything to the real body subscriber and notes the time of every piece of the body. */
    private static final class Watched<T> implements HttpResponse.BodySubscriber<T> {
        private final HttpResponse.BodySubscriber<T> delegate;
        private final AtomicLong last;
        private final CompletableFuture<Void> ended = new CompletableFuture<>();

        Watched(HttpResponse.BodySubscriber<T> delegate, AtomicLong last) {
            this.delegate = delegate;
            this.last = last;
        }

        @Override
        public CompletionStage<T> getBody() {
            return delegate.getBody();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            last.set(System.nanoTime());
            delegate.onNext(item);
        }

        @Override
        public void onError(Throwable throwable) {
            try {
                delegate.onError(throwable);
            } finally {
                ended.complete(null);
            }
        }

        @Override
        public void onComplete() {
            try {
                delegate.onComplete();
            } finally {
                ended.complete(null);
            }
        }
    }
}
