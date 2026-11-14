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

    Downloads() {
        this(HttpStall.LIMIT);
    }

    /** With another stall limit, for tests. */
    Downloads(Duration stall) {
        this.stall = stall;
    }

    /** A base URL without its trailing slash. */
    static String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** A GET of a small text answer; an unreachable host throws the {@link IOException}, for an offline fallback. */
    HttpResponse<String> text(URI uri) throws IOException, InterruptedException {
        return HttpStall.send(http, request(uri), HttpResponse.BodyHandlers.ofString(), stall, RETRY);
    }

    /**
     * Download {@code uri} to {@code target} through a {@code .part} file beside it ({@link AtomicFile}), so a failed
     * download leaves nothing behind and a replaced file keeps its permissions. A 404 throws {@code notFound}; a
     * non-null {@code checksum} must match the downloaded bytes; a null one skips
     * the check. A download that stalls fails with a one-line error
     * that names the host.
     */
    void fetch(URI uri, Path target, String notFound, Checksum checksum) throws IOException, InterruptedException {
        try {
            AtomicFile.replace(target, tmp -> {
                HttpResponse<Path> got;
                try {
                    got = HttpStall.send(http, request(uri), HttpResponse.BodyHandlers.ofFile(tmp), stall, RETRY);
                } catch (InterruptedException e) {
                    throw new Interrupted(e);
                } catch (HttpStall.StalledException e) {
                    throw new ToolException(e.getMessage());
                } catch (IOException e) {
                    throw new ToolException("cannot reach " + uri + ": " + e.getMessage());
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
