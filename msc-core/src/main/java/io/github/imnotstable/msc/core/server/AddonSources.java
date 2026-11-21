package io.github.imnotstable.msc.core.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.upstream.VersionOrder;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Finds and downloads plugins and mods by id from Modrinth, Hangar and SpigotMC (through Spiget), for the workspace's
 * platform and Minecraft version, with their required dependencies (ADR 0057). It searches and resolves only: which
 * plugin to install is the caller's choice, so results come in each source's own order and nothing is suggested.
 */
public final class AddonSources {
    /** Where each source answers. Tests point these at a local server. */
    public record Urls(String modrinth, String hangar, String spiget) {
        public static final Urls DEFAULT = new Urls("https://api.modrinth.com/v2", "https://hangar.papermc.io/api/v1",
                "https://api.spiget.org/v2");

        public Urls {
            modrinth = Downloads.trim(modrinth);
            hangar = Downloads.trim(hangar);
            spiget = Downloads.trim(spiget);
        }
    }

    /** A project to install: {@code source:id}, for example {@code modrinth:luckperms}. A bare id is Modrinth's. */
    public record Ref(String source, String id) {
        public static final Set<String> SOURCES = Set.of("modrinth", "hangar", "spigot");
        private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

        public static Ref of(String text) {
            String t = text.strip();
            int colon = t.indexOf(':');
            String source = colon < 0 ? "modrinth" : t.substring(0, colon).toLowerCase(Locale.ROOT);
            String id = colon < 0 ? t : t.substring(colon + 1);
            if (!SOURCES.contains(source)) {
                throw new ToolException("\"" + t + "\": the source must be modrinth, hangar or spigot, not " + source);
            }
            if (!ID.matcher(id).matches()) {
                throw new ToolException("\"" + t + "\": the id must be a project slug or number, such as "
                        + "modrinth:<slug>, hangar:<slug> or spigot:<resource number>");
            }
            if (source.equals("spigot") && !id.chars().allMatch(Character::isDigit)) {
                throw new ToolException("\"" + t + "\": a spigot id is the resource number, the digits at the end of "
                        + "its spigotmc.org URL");
            }
            return new Ref(source, id);
        }

        @Override
        public String toString() {
            return source + ":" + id;
        }
    }

    /**
     * One installed jar. {@code note} is null or one line the caller must see: the version was made for another
     * Minecraft version, or the file came without a checksum. {@code missing}: required dependencies this source names
     * but cannot install (hosted elsewhere), one line each.
     */
    public record Installed(Ref ref, String name, String version, Path jar, String note, List<String> missing) {
    }

    private record Pick(String name, String version, URI url, String fileName, Downloads.Checksum checksum,
                        String note, List<Ref> needs, List<String> missing) {
    }

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+ -]{0,127}\\.jar");
    /** Per source: three sources at five lines each keep a search result near 15 lines. */
    private static final int SEARCH_LIMIT = 5;

    private final Urls urls;
    private final Downloads downloads;

    public AddonSources(Urls urls, DownloadStore store) {
        this.urls = urls;
        this.downloads = new Downloads(store);
    }

    /** Modrinth loader names whose builds {@code platform} loads, best first. */
    static List<String> loaders(Platform platform) {
        return switch (platform) {
            case PAPER -> List.of("paper", "spigot", "bukkit");
            case PURPUR -> List.of("purpur", "paper", "spigot", "bukkit");
            case FOLIA -> List.of("folia");
            case FABRIC -> List.of("fabric");
            case QUILT -> List.of("quilt", "fabric");
            case NEOFORGE -> List.of("neoforge");
            case FORGE -> List.of("forge");
            case VELOCITY -> List.of("velocity");
            case MINESTOM -> throw new ToolException("minestom has no plugins folder: add a library to the project's "
                    + "build.gradle.kts instead");
        };
    }

    /** True where a plugin built for an older Minecraft version usually still loads (Bukkit's API stays compatible). */
    static boolean plugins(Platform platform) {
        return platform.agent().equals("paper") || platform == Platform.VELOCITY;
    }

    private static String hangarPlatform(Platform platform) {
        return switch (platform) {
            case PAPER, PURPUR, FOLIA -> "PAPER";
            case VELOCITY -> "VELOCITY";
            default -> throw new ToolException("hangar has Paper and Velocity plugins only, not " + platform.displayName()
                    + " mods: use modrinth");
        };
    }

    /** One line per match, {@code source:id  Name: description (downloads)}, from every source the platform has. */
    public List<String> search(String query, Platform platform) throws IOException, InterruptedException {
        List<String> sources = plugins(platform) && platform != Platform.VELOCITY
                ? List.of("modrinth", "hangar", "spigot") : plugins(platform) ? List.of("modrinth", "hangar")
                : List.of("modrinth");
        List<String> out = new ArrayList<>();
        for (String s : sources) {
            try {
                out.addAll(switch (s) {
                    case "modrinth" -> modrinthSearch(query, platform);
                    case "hangar" -> hangarSearch(query, platform);
                    case "spigot" -> spigetSearch(query, platform);
                    default -> throw new IllegalStateException(s);
                });
            } catch (Downloads.Unavailable e) {
                out.add(s + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Installs {@code refs} and their required dependencies into {@code dir}, each once. A jar that has the same name
     * as one already there replaces it.
     */
    public List<Installed> install(List<Ref> refs, Platform platform, String minecraft, Path dir)
            throws IOException, InterruptedException {
        List<Installed> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Ref> queue = new ArrayList<>(refs);
        while (!queue.isEmpty()) {
            Ref ref = queue.remove(0);
            if (!seen.add(ref.toString().toLowerCase(Locale.ROOT))) {
                continue;
            }
            Pick pick = Downloads.fresh(() -> switch (ref.source()) {
                case "hangar" -> hangar(ref, platform, minecraft);
                case "spigot" -> spiget(ref, platform);
                default -> modrinth(ref, platform, minecraft);
            });
            Files.createDirectories(dir);
            Path jar = dir.resolve(pick.fileName());
            downloads.fetch(pick.url(), jar, ref + ": " + pick.url() + " answered 404", pick.checksum());
            out.add(new Installed(ref, pick.name(), pick.version(), jar, pick.note(), pick.missing()));
            queue.addAll(pick.needs());
        }
        return out;
    }

    // ---- Modrinth (https://docs.modrinth.com/api/) ----

    private List<String> modrinthSearch(String query, Platform platform) throws IOException, InterruptedException {
        StringBuilder facets = new StringBuilder("[[");
        for (String l : loaders(platform)) {
            facets.append(facets.length() > 2 ? "," : "").append("\"categories:").append(l).append('"');
        }
        // Server-side projects only, and no modpacks, resource packs or shaders.
        facets.append("],[\"server_side:required\",\"server_side:optional\"],[\"project_type:mod\",\"project_type:plugin\"]]");
        JsonObject o = object(get(urls.modrinth() + "/search?limit=" + SEARCH_LIMIT + "&query=" + enc(query)
                + "&facets=" + enc(facets.toString()), "Modrinth"), "Modrinth's search");
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, "hits")) {
            JsonObject h = e.getAsJsonObject();
            out.add(line("modrinth:" + str(h, "slug"), str(h, "title"), str(h, "description"), num(h, "downloads")));
        }
        return out;
    }

    private Pick modrinth(Ref ref, Platform platform, String minecraft) throws IOException, InterruptedException {
        JsonArray versions = arrayOf(get(urls.modrinth() + "/project/" + enc(ref.id()) + "/version?loaders="
                + enc(jsonList(loaders(platform))), "Modrinth"), ref.toString());
        if (versions.isEmpty()) {
            throw new ToolException(ref + " has no " + platform.displayName() + " build on Modrinth (it may not exist: "
                    + "plugins search=<name> lists ids)");
        }
        JsonObject chosen = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            if (has(v, "game_versions", minecraft) && (chosen == null || !release(chosen) && release(v))) {
                chosen = v;
                if (release(v)) {
                    break;
                }
            }
        }
        String note = null;
        if (chosen == null) {
            JsonObject newest = versions.get(0).getAsJsonObject();
            String made = last(newest, "game_versions");
            if (!plugins(platform)) {
                throw new ToolException(ref + " has no build for Minecraft " + minecraft + " on "
                        + platform.displayName() + "; its newest is for " + made + ". Mods load only on their own "
                        + "Minecraft version: pick another mod or set up the server with version=" + made);
            }
            chosen = newest;
            note = "made for Minecraft " + made + ", not " + minecraft + "; check the startup log";
        }
        JsonObject file = null;
        for (JsonElement f : array(chosen, "files")) {
            if (file == null || f.getAsJsonObject().has("primary") && f.getAsJsonObject().get("primary").getAsBoolean()) {
                file = f.getAsJsonObject();
            }
        }
        if (file == null) {
            throw new ToolException(ref + " " + str(chosen, "version_number") + " has no file on Modrinth");
        }
        List<Ref> needs = new ArrayList<>();
        for (JsonElement d : array(chosen, "dependencies")) {
            JsonObject dep = d.getAsJsonObject();
            if ("required".equals(str(dep, "dependency_type")) && str(dep, "project_id") != null) {
                needs.add(new Ref("modrinth", str(dep, "project_id")));
            }
        }
        JsonObject hashes = file.has("hashes") ? file.getAsJsonObject("hashes") : new JsonObject();
        return new Pick(ref.id(), str(chosen, "version_number"), URI.create(str(file, "url")),
                fileName(str(file, "filename"), ref), Downloads.Checksum.of("SHA-512", str(hashes, "sha512")),
                note, needs, List.of());
    }

    // ---- Hangar (https://hangar.papermc.io/api-docs) ----

    private List<String> hangarSearch(String query, Platform platform) throws IOException, InterruptedException {
        JsonObject o = object(get(urls.hangar() + "/projects?limit=" + SEARCH_LIMIT + "&q=" + enc(query)
                + "&platform=" + hangarPlatform(platform), "Hangar"), "Hangar's search");
        List<String> out = new ArrayList<>();
        for (JsonElement e : array(o, "result")) {
            JsonObject p = e.getAsJsonObject();
            JsonObject stats = p.has("stats") ? p.getAsJsonObject("stats") : new JsonObject();
            String slug = p.has("namespace") ? str(p.getAsJsonObject("namespace"), "slug") : str(p, "name");
            out.add(line("hangar:" + slug, str(p, "name"), str(p, "description"), num(stats, "downloads")));
        }
        return out;
    }

    private Pick hangar(Ref ref, Platform platform, String minecraft) throws IOException, InterruptedException {
        String hp = hangarPlatform(platform);
        HttpResponse<String> r = downloads.text(URI.create(urls.hangar() + "/projects/" + enc(ref.id())
                + "/versions?limit=25&platform=" + hp), "Hangar");
        if (r.statusCode() == 404) {
            throw new ToolException(ref + " is not a Hangar project (plugins search=<name> source=hangar lists ids)");
        }
        JsonArray versions = array(object(body(r, "Hangar"), ref.toString()), "result");
        JsonObject chosen = null;
        JsonObject newest = null;
        for (JsonElement e : versions) {
            JsonObject v = e.getAsJsonObject();
            JsonObject deps = v.has("platformDependencies") ? v.getAsJsonObject("platformDependencies") : null;
            if (deps == null || !deps.has(hp)) {
                continue;
            }
            // Newest first; a Release-channel build beats a newer beta or snapshot.
            boolean rel = hangarRelease(v);
            if (newest == null || rel && !hangarRelease(newest)) {
                newest = v;
            }
            if (has(deps, hp, minecraft) && (chosen == null || rel && !hangarRelease(chosen))) {
                chosen = v;
            }
        }
        if (newest == null) {
            throw new ToolException(ref + " has no " + platform.displayName() + " build on Hangar");
        }
        String note = null;
        if (chosen == null) {
            chosen = newest;
            note = "made for Minecraft " + last(newest.getAsJsonObject("platformDependencies"), hp) + ", not "
                    + minecraft + "; check the startup log";
        }
        String version = str(chosen, "name");
        JsonObject dl = chosen.getAsJsonObject("downloads").getAsJsonObject(hp);
        if (str(dl, "downloadUrl") == null) {
            throw new ToolException(ref + " " + version + " is hosted outside Hangar (" + str(dl, "externalUrl")
                    + "), so it cannot be checked or fetched here; the same plugin may be on another source");
        }
        JsonObject info = dl.has("fileInfo") && dl.get("fileInfo").isJsonObject() ? dl.getAsJsonObject("fileInfo")
                : new JsonObject();
        List<Ref> needs = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        JsonObject pd = chosen.has("pluginDependencies") ? chosen.getAsJsonObject("pluginDependencies") : new JsonObject();
        for (JsonElement d : array(pd, hp)) {
            JsonObject dep = d.getAsJsonObject();
            if (!dep.has("required") || !dep.get("required").getAsBoolean()) {
                continue;
            }
            JsonElement id = dep.get("projectId");
            if (id != null && !id.isJsonNull()) {
                needs.add(new Ref("hangar", id.getAsString()));
            } else {
                missing.add(str(dep, "name") + " (" + ref + " requires it; Hangar links " + str(dep, "externalUrl") + ")");
            }
        }
        return new Pick(ref.id(), version, URI.create(str(dl, "downloadUrl")), fileName(str(info, "name"), ref),
                Downloads.Checksum.of("SHA-256", str(info, "sha256Hash")), note, needs, missing);
    }

    private static boolean hangarRelease(JsonObject v) {
        return v.has("channel") && "Release".equalsIgnoreCase(str(v.getAsJsonObject("channel"), "name"));
    }

    // ---- SpigotMC through Spiget (https://spiget.org/documentation/) ----

    private List<String> spigetSearch(String query, Platform platform) throws IOException, InterruptedException {
        hangarPlatform(platform);
        HttpResponse<String> r = downloads.text(URI.create(urls.spiget() + "/search/resources/" + enc(query)
                + "?size=" + SEARCH_LIMIT + "&sort=-downloads&fields=id,name,tag,downloads,premium,external"), "Spiget");
        if (r.statusCode() == 404) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement e : arrayOf(body(r, "Spiget"), "Spiget's search")) {
            JsonObject p = e.getAsJsonObject();
            if (bool(p, "premium")) {
                continue;
            }
            out.add(line("spigot:" + p.get("id").getAsString(), str(p, "name"), str(p, "tag"), num(p, "downloads"))
                    + (bool(p, "external") ? " [hosted elsewhere: not installable]" : ""));
        }
        return out;
    }

    private Pick spiget(Ref ref, Platform platform) throws IOException, InterruptedException {
        if (!platform.agent().equals("paper")) {
            throw new ToolException("spigot has Bukkit plugins only, not for " + platform.displayName());
        }
        HttpResponse<String> r = downloads.text(URI.create(urls.spiget() + "/resources/" + ref.id()), "Spiget");
        if (r.statusCode() == 404) {
            throw new ToolException(ref + " is not a SpigotMC resource");
        }
        JsonObject p = object(body(r, "Spiget"), ref.toString());
        if (bool(p, "premium")) {
            throw new ToolException(ref + " (" + str(p, "name") + ") is a paid resource; it cannot be downloaded here");
        }
        if (bool(p, "external")) {
            throw new ToolException(ref + " (" + str(p, "name") + ") is hosted outside SpigotMC, so it cannot be "
                    + "fetched here; the same plugin may be on another source");
        }
        String tested = last(p, "testedVersions");
        // Spiget names no checksum: the download is checked only by AddonCheck reading it as a plugin.
        return new Pick(str(p, "name"), null, URI.create(urls.spiget() + "/resources/" + ref.id() + "/download"),
                fileName(str(p, "name") + ".jar", ref), null,
                "SpigotMC gives no checksum" + (tested == null ? "" : "; tested up to Minecraft " + tested),
                List.of(), List.of());
    }

    // ---- helpers ----

    private String get(String url, String source) throws IOException, InterruptedException {
        HttpResponse<String> r = downloads.text(URI.create(url), source);
        if (r.statusCode() == 404) {
            throw new ToolException(source + " has no such project (plugins search=<name> lists ids)");
        }
        return body(r, source);
    }

    private static String body(HttpResponse<String> r, String source) {
        if (r.statusCode() != 200) {
            throw new ToolException(source + " answered " + r.statusCode() + " for " + r.uri());
        }
        return r.body();
    }

    /** A jar name from the source, or {@code <id>.jar} when it names none fit for a file name. */
    static String fileName(String name, Ref ref) {
        String n = name == null ? null : name.strip();
        return n != null && FILE.matcher(n).matches() ? n.replace(' ', '-') : ref.id() + ".jar";
    }

    static String line(String ref, String name, String description, long downloads) {
        String d = description == null ? "" : description.strip().replaceAll("\\s+", " ");
        if (d.length() > 90) {
            d = d.substring(0, 87) + "...";
        }
        return ref + "  " + name + (d.isEmpty() ? "" : ": " + d) + " (" + count(downloads) + " downloads)";
    }

    static String count(long n) {
        return n >= 1_000_000 ? String.format(Locale.ROOT, "%.1fM", n / 1e6)
                : n >= 1_000 ? String.format(Locale.ROOT, "%.0fk", n / 1e3) : Long.toString(n);
    }

    private static boolean release(JsonObject v) {
        return "release".equals(str(v, "version_type"));
    }

    private static boolean has(JsonObject o, String key, String value) {
        for (JsonElement e : array(o, key)) {
            if (e.isJsonPrimitive() && e.getAsString().equals(value)) {
                return true;
            }
        }
        return false;
    }

    private static String last(JsonObject o, String key) {
        JsonArray a = array(o, key);
        String newest = null;
        for (JsonElement e : a) {
            String v = e.getAsString();
            if (newest == null || VersionOrder.compare(v, newest) > 0) {
                newest = v;
            }
        }
        return newest;
    }

    private static JsonArray array(JsonObject o, String key) {
        return o != null && o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static JsonObject object(String json, String what) {
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ToolException(what + " is not the expected JSON");
        }
    }

    private static JsonArray arrayOf(String json, String what) {
        try {
            return JsonParser.parseString(json).getAsJsonArray();
        } catch (JsonParseException | IllegalStateException e) {
            throw new ToolException(what + " is not the expected JSON");
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsLong() : 0;
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() && e.getAsBoolean();
    }

    private static String jsonList(List<String> values) {
        return "[\"" + String.join("\",\"", values) + "\"]";
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
