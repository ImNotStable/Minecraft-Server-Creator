package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.SourceVersion;
import org.bukkit.plugin.PluginDescriptionFile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every project template with odd project names and packages (#293): each either refuses cleanly, naming the
 * input or the template, and writes nothing, or renders a project with no placeholder left, metadata that parses,
 * ids the loader accepts and a main class where its package says. Paper templates also serve Purpur and Fabric
 * templates also serve Quilt, so the platforms are paper, fabric, neoforge, forge and minestom.
 */
class TemplateNameCasesTest {
    private static final String ALL = "*";
    private static final Pattern FABRIC_ID = Pattern.compile("[a-z][a-z0-9-_]{1,63}");
    private static final Pattern FORGE_ID = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Pattern JAVA_PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    private static final Pattern GRADLE_MAIN = Pattern.compile("mainClass = \"([\\w.]+)\"");
    private static final Set<String> PAPER_RESTRICTED = Set.of("bukkit", "minecraft", "mojang");
    /** Where the in-memory projects are rendered; nothing is written there. */
    private static final Path ROOT = Path.of("project").toAbsolutePath().normalize();
    private static final Map<String, String> PLATFORMS = Templates.ids().stream()
            .collect(Collectors.toMap(t -> t, t -> Templates.info(t).get("platform")));

    /** One input, and the platforms that must refuse it ({@link #ALL} for every one). */
    record Case(String input, Supplier<TemplateVariables> vars, Set<String> refusedOn) {
        @Override
        public String toString() {
            return "'" + input + "'";
        }

        boolean refused(String platform) {
            return refusedOn.contains(ALL) || refusedOn.contains(platform);
        }
    }

    static Case name(String name, String... refusedOn) {
        return new Case(name, () -> TemplateVariables.of(name), Set.of(refusedOn));
    }

    static Case pkg(String pkg, String... refusedOn) {
        return new Case(pkg, () -> TemplateVariables.of("Lobby").withPackageName(pkg), Set.of(refusedOn));
    }

    static List<Case> cases() {
        return List.of(
                name("Lobby"), name("spawn lobby"), name("SPAWN LOBBY"), name("HTTPServer"), name("Parkour 3d"),
                name("spawn-lobby"), name("spawn_lobby"), name("my_cool-mod"), name("-lobby"), name("lobby-"),
                name("a--b"), name("Mc 2"), name("L2b"), name("l2B"), name("aB"), name("Ab"), name("x y"),
                name("True Story"), name("Void Run"), name("Record"), name("Var"), name("Yield"), name("Sealed"),
                name("Module"), name("Yes"), name("No"), name("On"), name("Off"), name("Plugin"), name("Server"),
                name("String"), name("Object"), name("System"), name("Greeter"), name("Hello Command"), name("Game"),
                name("Box"), name("Progress"), name("Logger"), name("Instance"), name("Minestom"), name("Paper"),
                name("Quilt Loader"), name("Neo Forge"), name("Minecraft Tools"), name("a".repeat(64)),
                name("Café Lobby"), name("Über Spiel"), name("Ñandú"),
                // Compatibility forms (#321): full-width, ligature, superscript and roman-numeral letters and digits.
                name("Ｌｏｂｂｙ"), name("ﬁsh"), name("Mc²"), name("Ⅳ Lobby"),
                // Refused by every platform: unusable names.
                name("", ALL), name("  ", ALL), name("123", ALL), name("3d Parkour", ALL), name("A", ALL),
                name("Void", ALL), name("class", ALL), name("new", ALL), name("Int", ALL), name("True", ALL),
                name("Null", ALL), name("a".repeat(65), ALL), name("Abcdefghij ".repeat(7), ALL),
                name("Ølstue", ALL), name("日本", ALL), name("Straße", ALL),
                // Ids a loader, Paper or MSC's agent keeps; Minestom refuses MinecraftServer, Paper JavaPlugin.
                name("Minecraft", ALL), name("Java", "fabric", "paper"), name("Fabric", "fabric"),
                name("Fabric Api", "fabric"), name("Fabricloader", "fabric"), name("Mixinextras", "fabric"),
                name("Forge", "forge"), name("Neoforge", "neoforge"), name("Bukkit", "paper"), name("Mojang", "paper"),
                name("Msc Agent", "fabric", "neoforge", "forge", "paper"),
                name("msc-agent", "fabric", "neoforge", "forge", "paper"),
                name("MSC_agent", "fabric", "neoforge", "forge", "paper"), name("Mscagent", "paper"),
                // Packages.
                pkg("a"), pkg("com.example.lobby_2"), pkg("javax.lobby"), pkg("org.bukkitlobby"), pkg("dev.record.var"),
                pkg("org.bukkit", "paper"), pkg("org.bukkit.lobby", "paper"), pkg("java.lobby", ALL), pkg("java", ALL),
                pkg("com.example.int", ALL), pkg("Com.Example", ALL), pkg("com..example", ALL), pkg("com.2d", ALL));
    }

    static Stream<Arguments> matrix() {
        return Templates.ids().stream().filter(t -> !t.equals(Templates.PAPER_SERVER))
                .flatMap(t -> cases().stream().map(c -> Arguments.of(t, c)));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("matrix")
    void rendersOrRefusesCleanly(String template, Case c) throws Exception {
        String platform = PLATFORMS.get(template);
        TemplateVariables vars;
        try {
            vars = c.vars().get();
        } catch (IllegalArgumentException e) {
            assertThat(c.refused(platform)).as("refused while building variables: %s", e.getMessage()).isTrue();
            assertThat(e.getMessage()).contains(c.input().strip());
            return;
        }
        Templates.Rendered rendered;
        try {
            // In memory: writing and deleting ~50 files per case took most of this class's time. TemplatesTest
            // covers that a refused scaffold writes nothing to disk.
            rendered = Templates.render(template, ROOT, vars);
        } catch (IllegalArgumentException e) {
            assertThat(c.refused(platform)).as("refused: %s", e.getMessage()).isTrue();
            assertThat(e.getMessage()).contains("Template '" + template + "'").contains("choose another");
            return;
        }
        assertThat(c.refused(platform)).as("rendered, but %s should refuse it", platform).isFalse();
        checkRendered(project(rendered), vars, platform);
    }

    /** The rendered files by path relative to the project root, with {@code /} separators. */
    private static Map<String, byte[]> project(Templates.Rendered rendered) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        rendered.files().forEach((path, bytes) -> {
            assertThat(path.startsWith(ROOT)).as("%s under the root", path).isTrue();
            files.put(ROOT.relativize(path).toString().replace('\\', '/'), bytes);
        });
        return files;
    }

    private static void checkRendered(Map<String, byte[]> files, TemplateVariables vars, String platform) throws Exception {
        for (Map.Entry<String, byte[]> f : files.entrySet()) {
            String rel = f.getKey();
            assertThat(rel).as("path").doesNotContain("{{").doesNotContain("}}");
            if (rel.endsWith(".jar")) {
                continue;
            }
            String text = new String(f.getValue(), StandardCharsets.UTF_8);
            assertThat(text).as(rel).doesNotContain("{{");
            assertThatCode(() -> parse(rel, text)).as(rel).doesNotThrowAnyException();
            if (rel.endsWith(".java")) {
                checkJava(rel, text);
            }
        }
        String mainClass = switch (platform) {
            case "fabric" -> fabricMain(files, vars);
            case "neoforge" -> forgeMain(files, "src/main/resources/META-INF/neoforge.mods.toml", vars, "neoforge");
            case "forge" -> forgeMain(files, "src/main/resources/META-INF/mods.toml", vars, "forge");
            case "paper" -> paperMain(files, vars);
            case "minestom" -> gradleMain(files);
            default -> throw new AssertionError("unknown platform " + platform);
        };
        assertThat(mainClass).startsWith(vars.packageName() + ".");
        assertThat(files).as("main class").containsKey("src/main/java/" + mainClass.replace('.', '/') + ".java");
    }

    private static String text(Map<String, byte[]> files, String rel) {
        assertThat(files).containsKey(rel);
        return new String(files.get(rel), StandardCharsets.UTF_8);
    }

    private static void parse(String rel, String text) throws Exception {
        if (rel.endsWith(".json") || rel.endsWith(".mcmeta")) {
            json(text);
        } else if (rel.endsWith(".toml")) {
            TomlSubset.parse(text);
        } else if (rel.endsWith(".yml") || rel.endsWith(".yaml")) {
            new org.yaml.snakeyaml.Yaml().load(text);
        }
    }

    private static JsonElement json(String text) throws IOException {
        JsonReader reader = new JsonReader(new StringReader(text));
        reader.setStrictness(Strictness.STRICT);
        JsonElement e = JsonParser.parseReader(reader);
        assertThat(reader.peek()).isEqualTo(JsonToken.END_DOCUMENT);
        return e;
    }

    /** The file's package matches its folder and has no keyword, and the file declares its own type. */
    private static void checkJava(String rel, String text) {
        Matcher m = JAVA_PACKAGE.matcher(text);
        assertThat(m.find()).as("package in %s", rel).isTrue();
        String pkg = m.group(1);
        assertThat(SourceVersion.isName(pkg)).as("package %s", pkg).isTrue();
        String dir = rel.substring(0, rel.lastIndexOf('/'));
        assertThat(dir).as(rel).matches("src/(main|test)/java/.*").endsWith("/java/" + pkg.replace('.', '/'));
        String type = rel.substring(rel.lastIndexOf('/') + 1, rel.length() - ".java".length());
        assertThat(SourceVersion.isIdentifier(type) && !SourceVersion.isKeyword(type)).as(type).isTrue();
        assertThat(text).as(rel).containsPattern("\\b(class|record|interface|enum)\\s+" + type + "\\b");
    }

    private static String fabricMain(Map<String, byte[]> files, TemplateVariables vars) throws IOException {
        JsonObject mod = json(text(files, "src/main/resources/fabric.mod.json")).getAsJsonObject();
        String id = mod.get("id").getAsString();
        assertThat(id).matches(FABRIC_ID).isEqualTo(vars.projectId())
                .isNotIn("minecraft", "java", "fabricloader", "fabric-api", "fabric", "mixinextras", "msc-agent");
        assertThat(mod.get("name").getAsString()).isEqualTo(vars.name());
        return mod.getAsJsonObject("entrypoints").getAsJsonArray("server").get(0).getAsString();
    }

    @SuppressWarnings("unchecked")
    private static String forgeMain(Map<String, byte[]> files, String toml, TemplateVariables vars, String loader) {
        Map<String, Object> doc = TomlSubset.parse(text(files, toml));
        String modId = (String) ((List<Map<String, Object>>) doc.get("mods")).getFirst().get("modId");
        assertThat(modId).matches(FORGE_ID).isEqualTo(vars.projectId().replace('-', '_'))
                .isNotIn("minecraft", loader, "msc_agent");
        assertThat((Map<String, Object>) doc.get("dependencies")).containsOnlyKeys(modId);
        String main = vars.packageName() + "." + vars.mainClass();
        assertThat(text(files, "src/main/java/" + main.replace('.', '/') + ".java")).contains("@Mod(").contains("MOD_ID = \"" + modId + "\"");
        return main;
    }

    private static String paperMain(Map<String, byte[]> files, TemplateVariables vars) throws Exception {
        PluginDescriptionFile plugin = new PluginDescriptionFile(new StringReader(text(files, "src/main/resources/plugin.yml")));
        // Read as Paper reads it: a bare Yes or On would come back as true.
        assertThat(plugin.getName()).isEqualTo(vars.name());
        assertThat(plugin.getName().toLowerCase(java.util.Locale.ROOT)).isNotIn(PAPER_RESTRICTED).isNotEqualTo("mscagent");
        return plugin.getMain();
    }

    private static String gradleMain(Map<String, byte[]> files) {
        Matcher m = GRADLE_MAIN.matcher(text(files, "build.gradle.kts"));
        assertThat(m.find()).as("mainClass in build.gradle.kts").isTrue();
        return m.group(1);
    }
}
