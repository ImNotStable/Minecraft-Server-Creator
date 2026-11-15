package io.github.imnotstable.msc.core.api;

import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * A searchable index of a platform API, built straight from its jars. Answers "what is it called and
 * what does it take" in a few lines, so the AI does not read Javadoc pages or decompile.
 */
public final class ApiIndex {
    /** How one agent family's API marks an event. */
    @FunctionalInterface
    public interface EventRule {
        boolean test(ApiIndex index, ClassInfo c);

        /** A subtype, direct or not, of one of {@code roots}. */
        static EventRule subtypeOf(String... roots) {
            List<String> all = List.of(roots);
            return (index, c) -> all.stream().anyMatch(root -> index.isA(c, root));
        }

        /** A type of one of {@code kinds} under package {@code prefix} whose simple name ends in one of {@code suffixes}. */
        static EventRule named(String prefix, Set<String> kinds, String... suffixes) {
            List<String> all = List.of(suffixes);
            return (index, c) -> c.name().startsWith(prefix) && kinds.contains(c.kind())
                    && all.stream().anyMatch(c.simpleName()::endsWith);
        }
    }

    /**
     * The event rule of each agent family {@link #findJars} handles (a {@link
     * io.github.imnotstable.msc.core.server.Platform#agent()} value), kept only here (#114, #606). Fabric API declares
     * its events as fields of {@code ...Events} and {@code ...Callback} types. Velocity's events share no supertype,
     * so they are the classes named {@code ...Event} in its event package; its {@code ResultedEvent} interface is not one.
     */
    public static final Map<String, EventRule> EVENT_RULES = Map.of(
            "paper", EventRule.subtypeOf("org.bukkit.event.Event"),
            "fabric", EventRule.named("net.fabricmc.fabric.api.", Set.of("class", "interface"), "Events", "Callback"),
            "neoforge", EventRule.subtypeOf("net.neoforged.bus.api.Event"),
            // Forge's EventBus 7 events implement one of three.
            "forge", EventRule.subtypeOf("net.minecraftforge.eventbus.api.event.RecordEvent",
                    "net.minecraftforge.eventbus.api.event.MutableEvent",
                    "net.minecraftforge.eventbus.api.event.InheritableEvent"),
            "minestom", EventRule.subtypeOf("net.minestom.server.event.Event"),
            "velocity", EventRule.named("com.velocitypowered.api.event.", Set.of("class", "record"), "Event"));

    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final List<Path> jars;

    private ApiIndex(List<Path> jars) {
        this.jars = jars;
    }

    public static ApiIndex load(List<Path> jars) throws IOException {
        ApiIndex index = new ApiIndex(List.copyOf(jars));
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                var entries = file.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (!name.endsWith(".class") || name.startsWith("META-INF/")) {
                        continue;
                    }
                    try (InputStream in = file.getInputStream(entry)) {
                        ClassInfo info = ClassFileReader.read(in);
                        if (info != null) {
                            index.classes.putIfAbsent(info.name(), info);
                        }
                    } catch (IOException | RuntimeException e) {
                        // One unreadable class must not cost the whole index.
                    }
                }
            }
        }
        return index;
    }

    /**
     * Find the platform's API jars that Gradle already downloaded: Paper API and Adventure for {@code paper},
     * the Minecraft server, Fabric API and loader for {@code fabric}, Minestom and Adventure for {@code minestom}, the
     * NeoForge or Forge API jars for {@code neoforge} and {@code forge}, Velocity API and Adventure for {@code velocity}.
     *
     * @param gradleHome the Gradle user home ({@link io.github.imnotstable.msc.core.server.GradleUserHome})
     * @param platform   an agent family ({@link io.github.imnotstable.msc.core.server.Platform#agent()}): paper,
     *                   fabric, neoforge, forge, minestom or velocity
     */
    public static List<Path> findJars(Path gradleHome, String platform) throws IOException {
        Path files = gradleHome.resolve("caches").resolve("modules-2").resolve("files-2.1");
        List<Path> out = new ArrayList<>();
        switch (platform) {
            case "fabric" -> {
                // Loom keeps the (unobfuscated since 26.x) Minecraft jars in its own local Maven layout.
                addNewest(out, gradleHome.resolve("caches").resolve("fabric-loom").resolve("minecraftMaven")
                        .resolve("net").resolve("minecraft"));
                Path api = files.resolve("net.fabricmc.fabric-api");
                if (Files.isDirectory(api)) {
                    try (Stream<Path> modules = Files.list(api)) {
                        for (Path module : modules.sorted().toList()) {
                            addNewest(out, module);
                        }
                    }
                }
                addNewest(out, files.resolve("net.fabricmc").resolve("fabric-loader"));
            }
            case "neoforge" -> {
                addNewest(out, files.resolve("net.neoforged").resolve("neoforge"));
                addNewest(out, files.resolve("net.neoforged").resolve("bus"));
            }
            case "forge" -> {
                addNewest(out, files.resolve("net.minecraftforge").resolve("forge"));
                addNewest(out, files.resolve("net.minecraftforge").resolve("eventbus"));
                addNewest(out, files.resolve("net.minecraftforge").resolve("fmlcore"));
            }
            case "velocity" -> {
                addNewest(out, files.resolve("com.velocitypowered").resolve("velocity-api"));
                addAdventure(out, files);
            }
            case "minestom" -> {
                addNewest(out, files.resolve("net.minestom").resolve("minestom"));
                addAdventure(out, files);
            }
            case "paper" -> {
                addNewest(out, files.resolve("io.papermc.paper").resolve("paper-api"));
                addAdventure(out, files);
            }
            default -> throw new IllegalArgumentException("no API jars known for agent family " + platform);
        }
        return out;
    }

    private static void addAdventure(List<Path> out, Path files) throws IOException {
        for (String module : List.of("adventure-api", "adventure-key", "adventure-text-minimessage")) {
            addNewest(out, files.resolve("net.kyori").resolve(module));
        }
    }

    private static void addNewest(List<Path> out, Path moduleDir) throws IOException {
        if (!Files.isDirectory(moduleDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(moduleDir, 3)) {
            walk.filter(p -> {
                String n = p.getFileName().toString();
                return n.endsWith(".jar") && !n.endsWith("-sources.jar") && !n.endsWith("-javadoc.jar");
            }).max(Comparator.comparingLong(p -> p.toFile().lastModified())).ifPresent(out::add);
        }
    }

    public int size() {
        return classes.size();
    }

    public List<Path> jars() {
        return jars;
    }

    /** A class by full name, or by simple name when that is unambiguous. */
    public ClassInfo find(String name) {
        ClassInfo exact = classes.get(name);
        if (exact != null) {
            return exact;
        }
        List<ClassInfo> matches = classes.values().stream()
                .filter(c -> c.simpleName().equalsIgnoreCase(name) || c.name().endsWith("." + name)).toList();
        if (matches.isEmpty()) {
            throw new ToolException("no class named " + name + ". Try api_search.");
        }
        if (matches.size() > 1) {
            List<ClassInfo> exactCase = matches.stream().filter(c -> c.simpleName().equals(name)).toList();
            if (exactCase.size() == 1) {
                return exactCase.get(0);
            }
            throw new ToolException(name + " is ambiguous: "
                    + String.join(", ", matches.stream().map(ClassInfo::name).limit(8).toList()));
        }
        return matches.get(0);
    }

    /** True when the class extends or implements {@code ancestor} (a full name), directly or not. */
    public boolean isA(ClassInfo info, String ancestor) {
        Set<String> seen = new HashSet<>();
        List<String> todo = new ArrayList<>(info.supertypes());
        while (!todo.isEmpty()) {
            String s = todo.remove(todo.size() - 1);
            if (s.equals(ancestor)) {
                return true;
            }
            if (seen.add(s) && classes.containsKey(s)) {
                todo.addAll(classes.get(s).supertypes());
            }
        }
        return false;
    }

    /** True for an event of any agent family's API ({@link #EVENT_RULES}), so {@code MSC_API_JARS} of any mix works. */
    public boolean isEvent(ClassInfo c) {
        return EVENT_RULES.values().stream().anyMatch(rule -> rule.test(this, c));
    }

    private record Hit(int score, String text) {
    }

    /**
     * Search class and member names. Every word must match; classes rank above members.
     *
     * @param kind     null, or one of class, interface, enum, record, annotation, event
     * @param maxLimit the largest limit a caller may ask for; at it, the cut line no longer says to raise the limit
     */
    public String search(String query, String kind, int limit, int maxLimit) {
        String[] words = query.toLowerCase(Locale.ROOT).trim().split("[\\s.#(),]+");
        List<Hit> hits = new ArrayList<>();
        for (ClassInfo c : classes.values()) {
            if (kind != null) {
                boolean match = kind.equals("event") ? isEvent(c) : c.kind().equals(kind);
                if (!match) {
                    continue;
                }
            }
            String simple = c.simpleName().toLowerCase(Locale.ROOT);
            String full = c.name().toLowerCase(Locale.ROOT);
            int classScore = score(words, simple, full);
            if (classScore > 0) {
                hits.add(new Hit(classScore + 1000 - Math.min(simple.length(), 200) - (c.deprecated() ? 300 : 0),
                        c.kind() + " " + c.name() + (c.deprecated() ? " (deprecated)" : "")));
            }
            Set<String> seen = new HashSet<>();
            for (ClassInfo.Member m : c.members()) {
                int s = score(words, m.name().toLowerCase(Locale.ROOT), simple + " " + m.name().toLowerCase(Locale.ROOT));
                if (s > 0 && seen.add(m.text())) {
                    hits.add(new Hit(s - Math.min(m.name().length(), 200) - (m.deprecated() || c.deprecated() ? 300 : 0),
                            c.simpleName() + "#" + m.text() + (m.deprecated() ? " (deprecated)" : "")));
                }
            }
            for (String constant : c.constants()) {
                String lower = constant.toLowerCase(Locale.ROOT);
                int s = score(words, lower, simple + " " + lower);
                if (s > 0) {
                    hits.add(new Hit(s - Math.min(lower.length(), 200), c.simpleName() + "." + constant));
                }
            }
        }
        if (hits.isEmpty()) {
            return "no matches for \"" + query + "\"";
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed().thenComparing(Hit::text));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            out.append(hits.get(i).text()).append('\n');
        }
        if (hits.size() > limit) {
            out.append("[").append(hits.size() - limit).append(" more; add words")
                    .append(limit < maxLimit ? " or raise limit (at most " + maxLimit + ")" : " to narrow the search")
                    .append(']');
        }
        return out.toString().stripTrailing();
    }

    /** 0 when a word is missing; higher for exact and prefix matches on the primary name. */
    private static int score(String[] words, String primary, String context) {
        int total = 0;
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (primary.equals(w)) {
                total += 400;
            } else if (primary.startsWith(w)) {
                total += 250;
            } else if (primary.contains(w)) {
                total += 150;
            } else if (context.contains(w)) {
                total += 50;
            } else {
                return 0;
            }
        }
        return total;
    }

    /**
     * One class: its header, enum constants and members, optionally filtered and with inherited members.
     *
     * @param maxLimit the largest limit a caller may ask for; at it, the cut line no longer says to raise the limit
     */
    public String describe(String name, String filter, boolean inherited, int limit, int maxLimit) {
        ClassInfo c = find(name);
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder();
        out.append(c.kind()).append(' ').append(c.name());
        if (c.deprecated()) {
            out.append(" (deprecated)");
        }
        if (!c.supertypes().isEmpty()) {
            out.append(" : ").append(String.join(", ", c.supertypes().stream().map(ApiIndex::shortName).toList()));
        }
        out.append('\n');
        List<String> constants = c.constants().stream()
                .filter(x -> f == null || x.toLowerCase(Locale.ROOT).contains(f)).toList();
        if (!constants.isEmpty()) {
            out.append("constants: ").append(String.join(" ", constants.stream().limit(limit).toList()));
            if (constants.size() > limit) {
                out.append(" [").append(constants.size() - limit).append(" more; use filter]");
            }
            out.append('\n');
        }
        List<String> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        collect(c, f, lines, seen, null);
        // Constants are reached through the class name (MinecraftServer.PROTOCOL_VERSION), so show the ones
        // inherited from interfaces and superclasses even without inherited=true.
        for (ClassInfo parent : ancestors(c)) {
            for (ClassInfo.Member m : parent.members()) {
                if (!m.method() && m.text().startsWith("static final ")
                        && (f == null || m.name().toLowerCase(Locale.ROOT).contains(f)) && seen.add(m.text())) {
                    lines.add(m.text() + (m.deprecated() ? " (deprecated)" : "") + "  <- " + parent.simpleName());
                }
            }
        }
        if (inherited) {
            for (ClassInfo parent : ancestors(c)) {
                collect(parent, f, lines, seen, parent.simpleName());
            }
        }
        for (int i = 0; i < Math.min(limit, lines.size()); i++) {
            out.append(lines.get(i)).append('\n');
        }
        if (lines.size() > limit) {
            out.append('[').append(lines.size() - limit).append(" more members; use filter")
                    .append(limit < maxLimit ? " or raise limit (at most " + maxLimit + ")" : " to narrow the list")
                    .append(']');
        } else if (lines.isEmpty() && constants.isEmpty()) {
            out.append(f == null ? "[no public members]" : "[no members match \"" + filter + "\""
                    + (inherited ? "" : "; try inherited=true") + "]");
        }
        return out.toString().stripTrailing();
    }

    /** Every indexed supertype of {@code c}, nearest first, each once. */
    private List<ClassInfo> ancestors(ClassInfo c) {
        List<ClassInfo> out = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        List<String> todo = new ArrayList<>(c.supertypes());
        while (!todo.isEmpty()) {
            String s = todo.remove(0);
            ClassInfo parent = classes.get(s);
            if (parent != null && visited.add(s)) {
                out.add(parent);
                todo.addAll(parent.supertypes());
            }
        }
        return out;
    }

    private static void collect(ClassInfo c, String filter, List<String> lines, Set<String> seen, String from) {
        for (ClassInfo.Member m : c.members()) {
            if (filter != null && !m.name().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            if (seen.add(m.text())) {
                lines.add(m.text() + (m.deprecated() ? " (deprecated)" : "") + (from == null ? "" : "  <- " + from));
            }
        }
    }

    /** {@code java.util.Map.Entry} to {@code Map.Entry}: drop the lowercase package segments. */
    private static String shortName(String full) {
        String[] parts = full.split("\\.");
        int first = 0;
        while (first < parts.length - 1 && !parts[first].isEmpty() && Character.isLowerCase(parts[first].charAt(0))) {
            first++;
        }
        return String.join(".", java.util.Arrays.copyOfRange(parts, first, parts.length));
    }
}
