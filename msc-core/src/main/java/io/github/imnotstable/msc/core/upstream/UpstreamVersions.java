package io.github.imnotstable.msc.core.upstream;

import io.github.imnotstable.msc.core.CoreConfig;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * {@code ./gradlew checkUpstreamVersions} (ADR 0039): prints, one line per component, the version the repository
 * pins and the newest stable one upstream. Always exits 0: offline, every line says "could not check".
 */
public final class UpstreamVersions {
    private static final long TIMEOUT_SECONDS = 20;

    private UpstreamVersions() {
    }

    /** {@code args[0]}: the version catalog, {@code gradle/libs.versions.toml}. */
    public static void main(String[] args) throws IOException {
        Path catalog = Path.of(args[0]);
        // The Gradle wrapper's version is checked with the catalog's pins (ADR 0057).
        Path wrapper = catalog.resolveSibling("wrapper/gradle-wrapper.properties");
        String gradle = Files.exists(wrapper) ? Files.readString(wrapper).replaceAll("(?s).*gradle-([0-9.]+)-(bin|all)\\.zip.*", "$1")
                : null;
        run(Files.readString(catalog) + (gradle == null ? "" : "\n[versions]\ngradle = \"" + gradle + "\"\n"),
                new Http(Duration.ofSeconds(15)), System.out);
    }

    static void run(String catalog, UpstreamCheck.Fetcher fetcher, PrintStream out) {
        var components = UpstreamCheck.components(UpstreamCheck.catalogVersions(catalog), CoreConfig.DEFAULT_MINECRAFT);
        UpstreamCheck.report(UpstreamCheck.check(components, fetcher, TIMEOUT_SECONDS)).forEach(out::println);
    }

    /** The real fetcher: a plain GET with timeouts; an answer other than 200 is an {@link IOException}. */
    record Http(Duration timeout) implements UpstreamCheck.Fetcher {
        private static final HttpClient CLIENT = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

        @Override
        public String get(String url) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("User-Agent", "msc-core upstream version check").build();
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode() + " from " + URI.create(url).getHost());
            }
            return response.body();
        }
    }
}
