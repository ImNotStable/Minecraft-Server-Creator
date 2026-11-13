package io.github.imnotstable.msc.bench.run;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Finds the Paper jar for a run without the user naming one: first the jar caches (msc-core keeps
 * {@code paper-<version>-<build>.jar} in {@code <workspace>/.msc/cache}), then a download of the
 * newest stable build from PaperMC's downloads service into the first cache folder, checked against the
 * build's sha256 as msc-core's {@code PaperDownloader} does (a copy by design: ADR 0004).
 */
public final class PaperJars {

    private static final String USER_AGENT = "msc-bench/0.1 (Minecraft Server Creator dev tooling)";

    private PaperJars() {
    }

    /** The newest cached {@code paper-<version>-<build>.jar} in the given folders, or null. */
    public static Path newestCached(List<Path> cacheDirs, String version) throws IOException {
        Pattern name = Pattern.compile("paper-" + Pattern.quote(version) + "-(\\d+)\\.jar");
        Path best = null;
        int bestBuild = -1;
        for (Path dir : cacheDirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Matcher m = name.matcher(file.getFileName().toString());
                    if (m.matches() && Integer.parseInt(m.group(1)) > bestBuild) {
                        bestBuild = Integer.parseInt(m.group(1));
                        best = file;
                    }
                }
            }
        }
        return best;
    }

    /** Uses a cached jar or downloads the newest build (stable preferred) into {@code cacheDirs.get(0)}. */
    public static Path resolve(List<Path> cacheDirs, String version, String downloadsUrl) throws IOException {
        Path cached = newestCached(cacheDirs, version);
        if (cached != null) {
            return cached;
        }
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15)).build();
        try {
            URI builds = URI.create(downloadsUrl + "/v3/projects/paper/versions/" + version + "/builds");
            HttpResponse<String> list = http.send(request(builds), HttpResponse.BodyHandlers.ofString());
            if (list.statusCode() != 200) {
                throw new IOException("PaperMC answered " + list.statusCode() + " for " + builds);
            }
            JsonObject best = null;
            for (JsonElement e : JsonParser.parseString(list.body()).getAsJsonArray()) {
                JsonObject b = e.getAsJsonObject();
                if (best == null || rank(b) > rank(best)) {
                    best = b;
                }
            }
            if (best == null) {
                throw new IOException("PaperMC has no builds for " + version);
            }
            JsonObject file = best.getAsJsonObject("downloads").getAsJsonObject("server:default");
            String url = file.get("url").getAsString();
            String sha256 = file.has("checksums") ? file.getAsJsonObject("checksums").get("sha256").getAsString() : null;
            Path dir = cacheDirs.getFirst();
            Files.createDirectories(dir);
            Path jar = dir.resolve("paper-" + version + "-" + best.get("id").getAsInt() + ".jar");
            Path part = dir.resolve(jar.getFileName() + ".part");
            HttpResponse<Path> got = http.send(request(URI.create(url)), HttpResponse.BodyHandlers.ofFile(part));
            if (got.statusCode() != 200) {
                Files.deleteIfExists(part);
                throw new IOException("download of " + url + " answered " + got.statusCode());
            }
            // As msc-core's PaperDownloader: a jar that does not match PaperMC's checksum is never kept.
            if (sha256 != null && !sha256.equalsIgnoreCase(sha256(part))) {
                Files.deleteIfExists(part);
                throw new IOException("checksum mismatch for " + url);
            }
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING);
            return jar;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while downloading Paper", e);
        } catch (RuntimeException e) {
            throw new IOException("unexpected answer from PaperMC: " + e.getMessage(), e);
        }
    }

    private static long rank(JsonObject build) {
        boolean stable = build.has("channel") && "STABLE".equalsIgnoreCase(build.get("channel").getAsString());
        return (stable ? 1_000_000_000L : 0) + build.get("id").getAsInt();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
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

    private static HttpRequest request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", USER_AGENT).timeout(Duration.ofMinutes(5)).build();
    }
}
