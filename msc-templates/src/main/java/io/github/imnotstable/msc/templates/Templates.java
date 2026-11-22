package io.github.imnotstable.msc.templates;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Starter projects packaged as classpath resources under {@code msc/templates/}.
 *
 * <pre>{@code
 * TemplateVariables vars = TemplateVariables.of("Spawn Lobby");
 * ScaffoldResult result = Templates.scaffold(Templates.PAPER_PLUGIN, dir, vars);
 * }</pre>
 */
public final class Templates {
    /** A Paper plugin project with tests, conventions and a dev server in {@code run/}. */
    public static final String PAPER_PLUGIN = "paper-plugin";
    /**
     * A Paper minigame project: {@link #PAPER_PLUGIN} plus a game base (kit, adventure mode, keep inventory,
     * stage respawns, no natural spawns, fixed time, saved progress) and a {@code /game} admin command.
     */
    public static final String PAPER_GAME = "paper-game";
    /** A Fabric server mod project (Loom) with tests, conventions and a dev server in {@code run/}. */
    public static final String FABRIC_MOD = "fabric-mod";
    /** A NeoForge server mod (ModDevGradle). See ADR 0027. */
    public static final String NEOFORGE_MOD = "neoforge-mod";
    /** A Minecraft Forge server mod (ForgeGradle 7). See ADR 0027. */
    public static final String FORGE_MOD = "forge-mod";
    /** Only the dev server config ({@code server.properties}, {@code eula.txt}), for a server directory. */
    public static final String PAPER_SERVER = "paper-server";
    /** A Minestom server project with the msc agent built in, tests and conventions. No EULA step. */
    public static final String MINESTOM_SERVER = "minestom-server";
    /**
     * A Minestom minigame server: {@link #MINESTOM_SERVER} plus a game base (kit, adventure mode, stage respawns,
     * frozen time, saved progress) and a {@code /game} admin command. No EULA step.
     */
    public static final String MINESTOM_GAME = "minestom-game";
    /**
     * A Fabric minigame mod: {@link #FABRIC_MOD} plus a game base (kit, adventure mode, keep inventory, stage
     * respawns, no natural spawns, fixed time, saved progress) and a {@code /game} admin command.
     */
    public static final String FABRIC_GAME = "fabric-game";
    /** A Velocity proxy plugin with tests and conventions; no Minecraft server files, no EULA step. ADR 0045. */
    public static final String VELOCITY_PLUGIN = "velocity-plugin";

    private static final String ROOT = "msc/templates/";
    private static final String TEMPLATE_SUFFIX = ".tmpl";
    private static final Set<String> BINARY_EXTENSIONS = Set.of("jar", "png", "zip", "nbt", "schem", "dat");
    /**
     * Ids each loader owns, by template platform: a mod or plugin with one of them is refused at startup or
     * shadows the other. Fabric templates also run on Quilt, whose loader is {@code quilt_loader} and provides the
     * Fabric ids. Each entry also names the id of MSC's own agent, which server_setup installs next to the project:
     * {@code msc-agent} (Fabric, Quilt), {@code msc_agent} (NeoForge, Forge) and plugin {@code MscAgent} (Paper,
     * Purpur). Paper refuses plugins named Bukkit, Minecraft or Mojang and compares plugin names ignoring case.
     * The map's keys are also the agent families, so server_setup installs the agent as
     * {@code msc-agent-<key>.jar} in the folder dev_cycle deploys the project's {@code <projectId>.jar} to.
     */
    private static final Map<String, LoaderIds> LOADER_IDS = Map.of(
            "fabric", new LoaderIds("projectId", "mod id", Set.of(
                    "minecraft", "java", "fabricloader", "mixinextras", "fabric-api", "fabric", "quilt_loader"), "msc-agent"),
            "neoforge", new LoaderIds("modId", "mod id", Set.of("minecraft", "neoforge"), "msc_agent"),
            "forge", new LoaderIds("modId", "mod id", Set.of("minecraft", "forge"), "msc_agent"),
            "paper", new LoaderIds("name", "plugin name", Set.of("bukkit", "minecraft", "mojang"), "mscagent"),
            // Velocity's own built-in plugin is id velocity; a proxy runs no MSC agent, so no id is MSC's.
            "velocity", new LoaderIds("projectId", "plugin id", Set.of("velocity"), ""));
    /** Paper refuses a plugin whose main class is in this package or below it. */
    private static final String BUKKIT_PACKAGE = "org.bukkit";

    /**
     * The placeholder holding a template's mod id or plugin name, the lower-case ids its loader keeps, and the
     * lower-case id of MSC's agent on that platform.
     */
    private record LoaderIds(String placeholder, String kind, Set<String> reserved, String agent) {}

    private Templates() {}

    /** Ids of every packaged template, sorted. */
    public static List<String> ids() {
        return lines(ROOT + "index.txt");
    }

    /** The {@code template.properties} of a template: {@code platform} and {@code description}. */
    public static Map<String, String> info(String id) {
        requireKnown(id);
        Properties p = new Properties();
        try (InputStream in = open(ROOT + id + "/template.properties")) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        p.stringPropertyNames().stream().sorted().forEach(k -> out.put(k, p.getProperty(k)));
        return out;
    }

    /** Versions pinned into scaffolded projects, from this repo's version catalog. */
    public static Map<String, String> versions() {
        Properties p = new Properties();
        try (InputStream in = open(ROOT + "versions.properties")) {
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, String> out = new LinkedHashMap<>();
        p.stringPropertyNames().stream().sorted().forEach(k -> out.put(k, p.getProperty(k)));
        return out;
    }

    /**
     * Writes template {@code id} into {@code target}, rendering {@code {{placeholders}}} in file
     * contents and paths.
     *
     * @throws IllegalArgumentException if the id is unknown, a placeholder has no value, or the variables give a
     *                                  class that clashes with an import or two files with one path
     * @throws IllegalStateException    if {@code target} exists and is not an empty directory; a directory
     *                                  holding only MSC's own {@code .msc} folder counts as empty
     * @throws IOException              if writing fails
     */
    public static ScaffoldResult scaffold(String id, Path target, TemplateVariables vars) throws IOException {
        requireKnown(id);
        requireEmpty(target);
        Path root = target.toAbsolutePath().normalize();
        Rendered rendered = render(id, root, vars);
        // Written only once every file rendered and passed the checks, so a refused scaffold leaves nothing behind.
        List<Path> written = new ArrayList<>();
        for (Map.Entry<Path, byte[]> f : rendered.files().entrySet()) {
            Path out = f.getKey();
            Files.createDirectories(out.getParent());
            Files.write(out, f.getValue());
            if (out.getFileName().toString().equals("gradlew")) {
                out.toFile().setExecutable(true, false);
            }
            written.add(out);
        }
        return new ScaffoldResult(root, written, rendered.values());
    }

    /** A template rendered in memory: each file's absolute path under the root and its bytes, and the values used. */
    record Rendered(Map<Path, byte[]> files, Map<String, String> values) {}

    /**
     * Renders template {@code id} in memory for a project at {@code root} (absolute and normalized), with every
     * check {@link #scaffold} makes on the variables, and writes nothing.
     */
    static Rendered render(String id, Path root, TemplateVariables vars) throws IOException {
        requireKnown(id);
        Map<String, String> values = new LinkedHashMap<>(versions());
        values.putAll(vars.asMap());
        requireLoaderAccepts(id, values);

        Map<Path, byte[]> files = new LinkedHashMap<>();
        for (String source : lines(ROOT + id + "/files.txt")) {
            String rel = TemplateRenderer.render(source, values);
            if (rel.endsWith(TEMPLATE_SUFFIX)) {
                rel = rel.substring(0, rel.length() - TEMPLATE_SUFFIX.length());
            }
            Path out = root.resolve(rel).normalize();
            if (!out.startsWith(root)) {
                throw new IllegalArgumentException("Template path escapes the target: " + rel);
            }
            byte[] bytes;
            try (InputStream in = open(ROOT + id + "/files/" + source)) {
                bytes = in.readAllBytes();
            }
            if (!isBinary(source)) {
                String text = TemplateRenderer.render(new String(bytes, StandardCharsets.UTF_8), values);
                requireNoImportClash(id, rel, text);
                bytes = text.getBytes(StandardCharsets.UTF_8);
            }
            if (files.put(out, bytes) != null) {
                throw new IllegalArgumentException("These variables make template '" + id + "' write " + rel
                        + " twice; choose another name or main class");
            }
        }
        return new Rendered(files, values);
    }

    /**
     * Refuses variables the template's loader would refuse at startup: a mod id or plugin name the loader or MSC's
     * agent owns (project name {@code Minecraft} gives mod id {@code minecraft}, {@code Msc Agent} the agent's
     * {@code msc-agent}), a jar name the agent's jar has ({@code Msc Agent Paper} gives {@code msc-agent-paper}), or
     * on Paper a main class inside {@code org.bukkit}.
     */
    private static void requireLoaderAccepts(String id, Map<String, String> values) {
        if (id.equals(PAPER_SERVER)) {
            return; // server config only: no plugin, so no plugin name or main class
        }
        String platform = info(id).get("platform");
        LoaderIds ids = LOADER_IDS.get(platform);
        if (ids != null) {
            String value = values.get(ids.placeholder());
            String key = value.toLowerCase(Locale.ROOT);
            if (ids.reserved().contains(key) || ids.agent().equals(key)) {
                throw new IllegalArgumentException("Template '" + id + "' would give " + ids.kind() + " '" + value
                        + "', which the " + (ids.agent().equals(key) ? "MSC agent on the dev server" : platform + " loader")
                        + " keeps for itself; choose another project name");
            }
            // dev_cycle deploys the jar as <projectId>.jar next to the agent's msc-agent-<platform>.jar (#311);
            // ignoring case, since Windows and macOS file names do.
            String agentJar = "msc-agent-" + platform;
            if (!ids.agent().isEmpty() && values.get("projectId").equalsIgnoreCase(agentJar)) {
                throw new IllegalArgumentException("Template '" + id + "' would give jar name '" + values.get("projectId")
                        + "', which the MSC agent on the dev server keeps for itself (" + agentJar
                        + ".jar); choose another project name");
            }
        }
        String pkg = values.get("packageName");
        if (platform.equals("paper") && (pkg.equals(BUKKIT_PACKAGE) || pkg.startsWith(BUKKIT_PACKAGE + "."))) {
            throw new IllegalArgumentException("Template '" + id + "' would put the main class in " + pkg
                    + ", and Paper refuses a main class within org.bukkit; choose another package");
        }
    }

    /**
     * A Java file whose class is named like one of its imports does not compile, for example project name
     * {@code Minecraft} giving {@code MinecraftServer} next to {@code import net.minestom.server.MinecraftServer}.
     */
    private static void requireNoImportClash(String id, String rel, String text) {
        if (!rel.endsWith(".java")) {
            return;
        }
        String file = rel.substring(rel.lastIndexOf('/') + 1);
        String className = file.substring(0, file.length() - ".java".length());
        Matcher m = Pattern.compile("(?m)^import\\s+([\\w.]+\\.)" + Pattern.quote(className) + "\\s*;").matcher(text);
        if (m.find()) {
            throw new IllegalArgumentException("Template '" + id + "' would name a class " + className
                    + ", which clashes with import " + m.group(1) + className + "; choose another project name or main class");
        }
    }

    private static boolean isBinary(String path) {
        if (path.startsWith("libs/")) {
            return true; // shipped libraries (msc-stdlib's repo, sources and graph) are copied as they are
        }
        int dot = path.lastIndexOf('.');
        return dot >= 0 && BINARY_EXTENSIONS.contains(path.substring(dot + 1));
    }

    private static void requireKnown(String id) {
        if (!ids().contains(id)) {
            throw new IllegalArgumentException("Unknown template '" + id + "'. Known: " + ids());
        }
    }

    private static void requireEmpty(Path target) throws IOException {
        if (!Files.exists(target)) {
            return;
        }
        if (!Files.isDirectory(target)) {
            throw new IllegalStateException("Target is a file: " + target);
        }
        try (Stream<Path> entries = Files.list(target)) {
            // .msc is MSC's own data (tool log, platform); msc_workspace or server_setup may have made it first.
            if (entries.anyMatch(e -> !e.getFileName().toString().equals(".msc"))) {
                throw new IllegalStateException("Target directory is not empty: " + target);
            }
        }
    }

    private static List<String> lines(String resource) {
        try (InputStream in = open(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream open(String resource) throws IOException {
        InputStream in = Templates.class.getClassLoader().getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("Missing template resource " + resource + " (is msc-templates built?)");
        }
        return in;
    }
}
