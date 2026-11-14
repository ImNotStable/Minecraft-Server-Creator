package io.github.imnotstable.msc.core.upstream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares the versions the repository pins with the newest stable ones upstream (ADR 0039): Mojang's release, Paper,
 * Fabric, NeoForge, Forge, Quilt and Minestom. Every lookup fails soft: an unreachable or odd answer is reported as
 * "could not check", never thrown.
 */
final class UpstreamCheck {
    static final String MOJANG = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    static final String PAPER = "https://fill.papermc.io/v3/projects/paper";
    static final String FABRIC_LOADER = "https://meta.fabricmc.net/v2/versions/loader";
    static final String FABRIC_API = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml";
    static final String NEOFORGE = "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    static final String FORGE = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    static final String QUILT_LOADER = "https://meta.quiltmc.org/v3/versions/loader";
    static final String MINESTOM = "https://repo1.maven.org/maven2/net/minestom/minestom/maven-metadata.xml";
    /** Newer Paper versions asked for a stable build at most; Paper never has more than a few in flight. */
    private static final int PAPER_CANDIDATES = 3;

    private static final Pattern CATALOG_LINE = Pattern.compile("^\\s*([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>([^<]+)</version>");

    /** A GET of a small text answer; any non-200 answer is an {@link IOException}. */
    @FunctionalInterface
    interface Fetcher {
        String get(String url) throws IOException, InterruptedException;
    }

    /** Finds a component's newest stable version upstream. */
    @FunctionalInterface
    interface Lookup {
        String newest(Fetcher fetcher) throws IOException, InterruptedException;
    }

    /** A pinned component: its name, the version pinned, and how to look up the newest. */
    record Component(String name, String pinned, Lookup lookup) {
    }

    /** One component's outcome: {@code newest} when the lookup worked, otherwise a short {@code failure}. */
    record Result(Component component, String newest, String failure) {
        boolean newer() {
            return newest != null && VersionOrder.compare(newest, component.pinned()) > 0;
        }
    }

    private UpstreamCheck() {
    }

    /** The {@code [versions]} table of a Gradle version catalog: key to version, comments dropped. */
    static Map<String, String> catalogVersions(String toml) {
        Map<String, String> versions = new LinkedHashMap<>();
        boolean inVersions = false;
        for (String line : toml.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("[")) {
                inVersions = t.equals("[versions]");
            } else if (inVersions) {
                Matcher m = CATALOG_LINE.matcher(t);
                if (m.find()) {
                    versions.put(m.group(1), m.group(2));
                }
            }
        }
        return versions;
    }

    /** The components to check, from the catalog's pins and the core's default Minecraft version. */
    static List<Component> components(Map<String, String> catalog, String coreMinecraft) {
        String minecraft = catalog.getOrDefault("minecraft", coreMinecraft);
        String paper = catalog.getOrDefault("paper", "?").replace(".build.+", "");
        List<Component> list = new ArrayList<>();
        list.add(new Component("minecraft", minecraft, UpstreamCheck::minecraft));
        if (!coreMinecraft.equals(minecraft)) {
            list.add(new Component("msc-core default", coreMinecraft, UpstreamCheck::minecraft));
        }
        list.add(new Component("paper", paper, f -> paper(f, paper)));
        list.add(new Component("fabric-loader", pin(catalog, "fabric-loader"), UpstreamCheck::fabricLoader));
        list.add(new Component("fabric-api", pin(catalog, "fabric-api"),
                f -> newestOf(mavenVersions(f.get(FABRIC_API)), v -> v.endsWith("+" + minecraft))));
        list.add(new Component("neoforge", pin(catalog, "neoforge"),
                f -> newestOf(mavenVersions(f.get(NEOFORGE)), v -> true)));
        list.add(new Component("forge", pin(catalog, "forge"), UpstreamCheck::forge));
        list.add(new Component("quilt-loader", pin(catalog, "quilt-loader"), UpstreamCheck::quiltLoader));
        list.add(new Component("minestom", pin(catalog, "minestom"),
                f -> newestOf(mavenVersions(f.get(MINESTOM)), v -> true)));
        return list;
    }

    private static String pin(Map<String, String> catalog, String key) {
        return catalog.getOrDefault(key, "?");
    }

    /** Runs every lookup at once, each limited to {@code timeoutSeconds}; failures become results, never throws. */
    static List<Result> check(List<Component> components, Fetcher fetcher, long timeoutSeconds) {
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> futures = new ArrayList<>();
            for (Component c : components) {
                futures.add(pool.submit(() -> c.lookup().newest(fetcher)));
            }
            List<Result> results = new ArrayList<>();
            for (int i = 0; i < components.size(); i++) {
                results.add(result(components.get(i), futures.get(i), timeoutSeconds));
            }
            pool.shutdownNow();
            return results;
        }
    }

    private static Result result(Component c, Future<String> future, long timeoutSeconds) {
        try {
            String newest = future.get(timeoutSeconds, TimeUnit.SECONDS);
            return newest == null ? new Result(c, null, "no stable version found") : new Result(c, newest, null);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException) {
                return new Result(c, null, "unexpected answer");
            }
            String why = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            return new Result(c, null, why.lines().findFirst().orElse(why));
        } catch (TimeoutException e) {
            future.cancel(true);
            return new Result(c, null, "timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(c, null, "interrupted");
        }
    }

    /** One line per component, then a summary line. */
    static List<String> report(List<Result> results) {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("%-17s %-20s %-20s", "component", "pinned", "newest stable").stripTrailing());
        int newer = 0;
        int unchecked = 0;
        for (Result r : results) {
            String state;
            if (r.newest() == null) {
                unchecked++;
                state = "could not check (" + r.failure() + ")";
            } else if (r.newer()) {
                newer++;
                state = "NEWER";
            } else {
                state = "up to date";
            }
            lines.add(String.format("%-17s %-20s %-20s %s", r.component().name(), r.component().pinned(),
                    r.newest() == null ? "-" : r.newest(), state));
        }
        lines.add(newer + " newer, " + unchecked + " could not check"
                + (newer > 0 ? "; pins live in gradle/libs.versions.toml and CoreConfig.DEFAULT_MINECRAFT" : ""));
        return lines;
    }

    static String minecraft(Fetcher f) throws IOException, InterruptedException {
        return json(f.get(MOJANG)).getAsJsonObject().getAsJsonObject("latest").get("release").getAsString();
    }

    /**
     * The newest Paper version with a stable build: Paper lists a version (and its release candidates) before its
     * builds are stable, so each newer version than the pin is asked for its latest build's channel.
     */
    static String paper(Fetcher f, String pinned) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>();
        for (Map.Entry<String, JsonElement> group : json(f.get(PAPER)).getAsJsonObject()
                .getAsJsonObject("versions").entrySet()) {
            group.getValue().getAsJsonArray().forEach(v -> all.add(v.getAsString()));
        }
        List<String> newer = all.stream().filter(VersionOrder::isStable)
                .filter(v -> VersionOrder.compare(v, pinned) > 0)
                .sorted(VersionOrder.NEWEST_LAST.reversed()).limit(PAPER_CANDIDATES).toList();
        for (String v : newer) {
            JsonObject latest = json(f.get(PAPER + "/versions/" + v + "/builds/latest")).getAsJsonObject();
            if (latest.has("channel") && "STABLE".equalsIgnoreCase(latest.get("channel").getAsString())) {
                return v;
            }
        }
        return all.contains(pinned) ? pinned : newestOf(all, v -> true);
    }

    static String fabricLoader(Fetcher f) throws IOException, InterruptedException {
        List<String> stable = new ArrayList<>();
        for (JsonElement e : json(f.get(FABRIC_LOADER)).getAsJsonArray()) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("stable") && o.get("stable").getAsBoolean()) {
                stable.add(o.get("version").getAsString());
            }
        }
        return newestOf(stable, v -> true);
    }

    static String quiltLoader(Fetcher f) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>();
        json(f.get(QUILT_LOADER)).getAsJsonArray().forEach(e -> all.add(e.getAsJsonObject().get("version").getAsString()));
        return newestOf(all, v -> true);
    }

    /** Forge's recommended build for the newest Minecraft version that has one, as {@code <minecraft>-<forge>}. */
    static String forge(Fetcher f) throws IOException, InterruptedException {
        String suffix = "-recommended";
        String best = null;
        for (Map.Entry<String, JsonElement> e : json(f.get(FORGE)).getAsJsonObject()
                .getAsJsonObject("promos").entrySet()) {
            if (e.getKey().endsWith(suffix)) {
                String v = e.getKey().substring(0, e.getKey().length() - suffix.length()) + "-"
                        + e.getValue().getAsString();
                if (VersionOrder.isStable(v) && (best == null || VersionOrder.compare(v, best) > 0)) {
                    best = v;
                }
            }
        }
        return best;
    }

    /** The {@code <version>} entries of a Maven {@code maven-metadata.xml}. */
    static List<String> mavenVersions(String xml) {
        List<String> versions = new ArrayList<>();
        Matcher m = MAVEN_VERSION.matcher(xml);
        while (m.find()) {
            versions.add(m.group(1).strip());
        }
        return versions;
    }

    /** The newest stable version that {@code keep} accepts, or null when there is none. */
    static String newestOf(List<String> versions, Predicate<String> keep) {
        return versions.stream().filter(VersionOrder::isStable).filter(keep)
                .max(VersionOrder.NEWEST_LAST).orElse(null);
    }

    private static JsonElement json(String body) throws IOException {
        try {
            return JsonParser.parseString(body);
        } catch (RuntimeException e) {
            throw new IOException("unreadable answer", e);
        }
    }
}
