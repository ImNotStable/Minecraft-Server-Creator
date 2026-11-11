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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
    /** A Fabric server mod project (Loom) with tests, conventions and a dev server in {@code run/}. */
    public static final String FABRIC_MOD = "fabric-mod";
    /** Only the dev server config ({@code server.properties}, {@code eula.txt}), for a server directory. */
    public static final String PAPER_SERVER = "paper-server";
    /** A Minestom server project with the msc agent built in, tests and conventions. No EULA step. */
    public static final String MINESTOM_SERVER = "minestom-server";

    private static final String ROOT = "msc/templates/";
    private static final String TEMPLATE_SUFFIX = ".tmpl";
    private static final Set<String> BINARY_EXTENSIONS = Set.of("jar", "png", "zip", "nbt", "schem", "dat");

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
     * @throws IllegalArgumentException if the id is unknown or a placeholder has no value
     * @throws IllegalStateException    if {@code target} exists and is not an empty directory
     * @throws IOException              if writing fails
     */
    public static ScaffoldResult scaffold(String id, Path target, TemplateVariables vars) throws IOException {
        requireKnown(id);
        requireEmpty(target);
        Map<String, String> values = new LinkedHashMap<>(versions());
        values.putAll(vars.asMap());

        Path root = target.toAbsolutePath().normalize();
        List<Path> written = new ArrayList<>();
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
                bytes = TemplateRenderer.render(new String(bytes, StandardCharsets.UTF_8), values)
                        .getBytes(StandardCharsets.UTF_8);
            }
            Files.createDirectories(out.getParent());
            Files.write(out, bytes);
            if (out.getFileName().toString().equals("gradlew")) {
                out.toFile().setExecutable(true, false);
            }
            written.add(out);
        }
        return new ScaffoldResult(root, written, values);
    }

    private static boolean isBinary(String path) {
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
            if (entries.findAny().isPresent()) {
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
