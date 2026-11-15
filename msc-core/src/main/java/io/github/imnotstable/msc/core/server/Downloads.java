package io.github.imnotstable.msc.core.server;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.process.AtomicFile;
import io.github.imnotstable.msc.process.HttpStall;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * The HTTP side of the server downloaders: one client, one User-Agent, and download-to-cache with clean-up. Every
 * request goes through {@link HttpStall}, so a server that goes silent fails it after the stall limit (#454).
 */
final class Downloads {
    private static final String USER_AGENT = "msc-core/0.1 (Minecraft Server Creator dev tooling)";
    private static final String RETRY = "run server_setup again";

    /** A checksum a download must match: a {@link MessageDigest} algorithm and the expected digest in hex. */
    record Checksum(String algorithm, String hex) {
        /**
         * The checksum, without the whitespace around {@code hex}, or null when the source gave none (Purpur's
         * answer may name no md5): the download is then not checked.
         */
        static Checksum of(String algorithm, String hex) {
            return hex == null ? null : new Checksum(algorithm, hex.strip());
        }
    }

    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final Duration stall;
    private final DownloadStore store;

    /** Without the machine's store, for tests. */
    Downloads() {
        this(HttpStall.LIMIT, DownloadStore.NONE);
    }

    Downloads(DownloadStore store) {
        this(HttpStall.LIMIT, store);
    }

    /** With another stall limit, for tests. */
    Downloads(Duration stall) {
        this(stall, DownloadStore.NONE);
    }

    Downloads(Duration stall, DownloadStore store) {
        this.stall = stall;
        this.store = store;
    }

    /** A base URL without its trailing slash. */
    static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * The source failed for a reason that can pass: the host cannot be reached, it stalled, or it answered 408, 429 or
     * a 5xx (the usual answer of a service that is down is its proxy's 502, 503 or 504). The message is one line for
     * the user. Only {@link #orCached} turns it into a fallback or a {@link ToolException}.
     */
    static final class Unavailable extends IOException {
        private static final long serialVersionUID = 1L;

        Unavailable(String message) {
            super(message);
        }
    }

    /** An update from the source: asks it, and downloads what it names. */
    @FunctionalInterface
    interface Update<T> {
        T get() throws IOException, InterruptedException;
    }

    /** The newest usable cached build, told {@code note} to say why it is used; null when nothing is cached. */
    @FunctionalInterface
    interface Fallback<T> {
        T use(String note) throws IOException;
    }

    /** True for an answer that says the source is down for now, not that what was asked for is missing. */
    static boolean passing(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    /**
     * The one update rule of every downloader (#589): run {@code update}; when the source is {@link Unavailable} for
     * any reason that can pass, use the newest cached build from {@code cached}, with a one-line note saying so. Only
     * without one does it fail, with the source's reason. Any other answer (404, a bad checksum) fails as it is.
     */
    static <T> T orCached(Update<T> update, Fallback<T> cached) throws IOException, InterruptedException {
        try {
            return update.get();
        } catch (Unavailable e) {
            T fallback = cached.use(e.getMessage() + "; using the newest cached build");
            if (fallback == null) {
                throw new ToolException(e.getMessage());
            }
            return fallback;
        }
    }

    /** {@link #orCached} for a download with nothing to fall back to: fails with the source's reason. */
    static <T> T fresh(Update<T> update) throws IOException, InterruptedException {
        return orCached(update, note -> null);
    }

    /**
     * A GET of a small text answer from {@code source} (its name in messages). A source that is {@link Unavailable}
     * throws that; any other answer, a 404 included, is the caller's to read.
     */
    HttpResponse<String> text(URI uri, String source) throws IOException, InterruptedException {
        HttpResponse<String> response;
        try {
            response = HttpStall.send(http, request(uri), HttpResponse.BodyHandlers.ofString(), stall, RETRY);
        } catch (HttpStall.StalledException e) {
            throw new Unavailable(e.getMessage());
        } catch (IOException e) {
            throw new Unavailable("cannot reach " + uri + ": " + e.getMessage());
        }
        if (passing(response.statusCode())) {
            throw new Unavailable(source + " answered " + response.statusCode() + " for " + uri);
        }
        return response;
    }

    /**
     * Download {@code uri} to {@code target} through a {@code .part} file beside it ({@link AtomicFile}), so a failed
     * download leaves nothing behind and a replaced file keeps its permissions. A 404 throws {@code notFound}; a
     * non-null {@code checksum} must match the downloaded bytes; a null one skips the check. A host that cannot be
     * reached, stalls or answers a status that can pass throws {@link Unavailable} with a one-line reason. With a checksum, the machine's {@link DownloadStore} is asked first and keeps what was
     * downloaded.
     *
     * @return true when the file came from the machine's store, not the network
     */
    boolean fetch(URI uri, Path target, String notFound, Checksum checksum) throws IOException, InterruptedException {
        Path stored = store.find(checksum);
        if (stored != null) {
            AtomicFile.copy(stored, target);
            return true;
        }
        try {
            AtomicFile.replace(target, tmp -> {
                HttpResponse<Path> got;
                try {
                    got = HttpStall.send(http, request(uri), HttpResponse.BodyHandlers.ofFile(tmp), stall, RETRY);
                } catch (InterruptedException e) {
                    throw new Interrupted(e);
                } catch (HttpStall.StalledException e) {
                    throw new Unavailable(e.getMessage());
                } catch (IOException e) {
                    throw new Unavailable("cannot reach " + uri + ": " + e.getMessage());
                }
                if (passing(got.statusCode())) {
                    throw new Unavailable("download of " + uri + " answered " + got.statusCode());
                }
                if (got.statusCode() != 200) {
                    throw new ToolException(got.statusCode() == 404 ? notFound
                            : "download of " + uri + " answered " + got.statusCode());
                }
                if (checksum != null && !checksum.hex().equalsIgnoreCase(digest(tmp, checksum.algorithm()))) {
                    throw new ToolException("checksum mismatch for " + uri);
                }
            });
        } catch (Interrupted e) {
            throw e.interrupt;
        }
        if (checksum != null) {
            store.keep(target, checksum);
        }
        return false;
    }

    /** Carries an interrupt out of the writer, which may only throw an IOException. */
    private static final class Interrupted extends IOException {
        private static final long serialVersionUID = 1L;
        private final transient InterruptedException interrupt;

        Interrupted(InterruptedException cause) {
            super(cause);
            this.interrupt = cause;
        }
    }

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build();
    }

    static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    static String digest(Path file, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
