package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import io.github.imnotstable.msc.core.ServerPropertiesAccess.Finding;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins that a Minecraft server's {@code server.properties} is opened only by msc-process's
 * {@code ServerPropertiesFile}, which reads and escapes it as the server does (ADR 0033, #391, #392). Hand-rolled
 * readers and writers had drifted on charset and escaping. Runs in msc-core's repoChecks task over every module's
 * production sources.
 * <p>A tripwire for ordinary changes, not a proof against deliberate evasion (ADR 0035). {@link ServerPropertiesAccess}
 * follows the file, not string handling: the tables below are what it must and must not report, and the reach it has.
 */
class ServerPropertiesAccessTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "productionSources";
    private static final String SHARED =
            "msc-process/src/main/java/io/github/imnotstable/msc/process/ServerPropertiesFile.java";

    @Test
    void scansEveryModulesProductionCode() {
        assertThat(RepoFiles.list(SET)).contains(SHARED,
                        "msc-core/src/main/java/io/github/imnotstable/msc/core/server/RconTarget.java",
                        "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/Workspaces.java")
                .noneMatch(f -> f.contains("/build/") || f.contains("/src/test/") || f.contains("/src/main/templates/"));
    }

    @Test
    void onlyServerPropertiesFileOpensServerProperties() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String f : RepoFiles.list(SET)) {
            sources.put(f, Files.readString(RepoFiles.path(SET, f)));
        }
        assertThat(ServerPropertiesAccess.find(sources)).as("server.properties is opened only by msc-process's "
                        + "ServerPropertiesFile (ADR 0033), which matches the server's charset and escaping: use "
                        + "ServerPropertiesFile.read(file) and ServerPropertiesFile.set(file, values). A byte-for-byte "
                        + "snapshot (Files.readAllBytes, Files.write of those bytes, copy, move) is fine. If an entry "
                        + "is not server.properties, the path reaching it was taken for it: follow the line, rename or "
                        + "split the variable or parameter, and add the shape to mustNotFlag in this test (ADR 0035)")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustFlag")
    void flags(String shape, Map<String, String> sources, List<String> calls) {
        assertThat(ServerPropertiesAccess.find(sources)).extracting(Finding::call)
                .containsExactlyInAnyOrderElementsOf(calls.stream().map(c -> c + " on server.properties").toList());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustNotFlag")
    void ignores(String shape, Map<String, String> sources) {
        assertThat(ServerPropertiesAccess.find(sources)).isEmpty();
    }

    /**
     * #409: production files that split at '=' or ':' for their own reasons (ids, block states), each with a text
     * read added, the edit that used to fail the build. A key=value parser of another file is not this test's concern.
     */
    @ParameterizedTest
    @ValueSource(strings = {"msc-core/src/main/java/io/github/imnotstable/msc/core/building/BuildScript.java",
            "msc-core/src/main/java/io/github/imnotstable/msc/core/building/Turn.java",
            "msc-core/src/main/java/io/github/imnotstable/msc/core/tools/TextureTools.java",
            "msc-renderer/src/main/java/io/github/imnotstable/msc/renderer/BlockColors.java",
            "msc-renderer/src/main/java/io/github/imnotstable/msc/renderer/BlockState.java",
            "msc-bot/src/main/java/io/github/imnotstable/msc/bot/BlockShapes.java",
            "msc-agent-minestom/src/main/java/io/github/imnotstable/msc/agent/minestom/BlockStates.java",
            "msc-agent-common/src/main/java/io/github/imnotstable/msc/agent/common/BlockNames.java",
            "msc-core/src/main/java/io/github/imnotstable/msc/core/api/TypeNames.java",
            "msc-textures/src/main/java/io/github/imnotstable/msc/textures/ResourceId.java",
            "msc-textures/src/main/java/io/github/imnotstable/msc/textures/PackLint.java"})
    void aTextReadBesideOtherKeyValueSplittingPasses(String file) throws IOException {
        String text = Files.readString(RepoFiles.path(SET, file));
        String edited = switch (file.substring(file.lastIndexOf('/') + 1)) {
            // The auditor's edits: String.lines() for split("\\R"), and a pack.mcmeta read.
            case "BuildScript.java" -> replaceOnce(text, "text.split(\"\\\\R\")", "text.lines().toArray(String[]::new)");
            case "PackLint.java" -> replaceOnce(text, "int colon = id.indexOf(':');",
                    "String mcmeta = Files.readString(Path.of(\"pack.mcmeta\"));\n        int colon = id.indexOf(':');");
            default -> text;
        };
        int end = edited.lastIndexOf('}');
        edited = edited.substring(0, end) + """
                    static java.util.Map<String, String> keyValues(java.nio.file.Path file) throws java.io.IOException {
                        java.util.Map<String, String> out = new java.util.HashMap<>();
                        for (String line : java.nio.file.Files.readAllLines(file)) {
                            int eq = line.indexOf('=');
                            out.put(line.substring(0, eq), line.substring(eq + 1));
                        }
                        java.nio.file.Files.writeString(file, String.join("\\n", out.keySet()));
                        return out;
                    }
                }
                """;
        assertThat(ServerPropertiesAccess.find(Map.of(file, edited))).isEmpty();
    }

    private static String replaceOnce(String text, String from, String to) {
        assertThat(text).as("the edit's anchor").contains(from);
        return text.replace(from, to);
    }

    /** Each shape: what it is, its sources (path to text), and the calls that must be reported. */
    static Stream<Arguments> mustFlag() throws IOException {
        String oldPack = old("PackProperties.java");
        int load = oldPack.indexOf("private static Properties load(Path file) {");
        int loadEnd = SourceText.pairs(oldPack, '{', '}')[oldPack.indexOf('{', load)];
        String oldPackWriterOnly = oldPack.substring(0, load)
                + "private static Properties load(Path file) { return new Properties(); }" + oldPack.substring(loadEnd + 1);
        return Stream.of(
                // The readers and writers ADR 0033 replaced, as they were at 8f49c2a, with their callers' lines.
                shape("RconTarget.of before #391", Map.of("old/RconTarget.java", old("RconTarget.java")),
                        "Files.newInputStream"),
                shape("PlayerTools.address before #391", Map.of("old/PlayerTools.java", old("PlayerTools.java")),
                        "Files.newBufferedReader"),
                shape("ServerProperties.read, set and modeLine before #392", Map.of(
                        "old/ServerProperties.java", old("ServerProperties.java"),
                        "old/ServerSetup.java", OLD_SERVER_SETUP, "old/ServerRun.java", OLD_SERVER_RUN),
                        "Files.readAllLines", "Files.readAllLines", "Files.writeString"),
                shape("Workspaces.setProperties before #392", Map.of("old/Workspaces.java", old("Workspaces.java")),
                        "Files.readAllLines", "Files.write"),
                shape("PackProperties before #404", Map.of("old/PackProperties.java", oldPack,
                        "old/ServerRun.java", OLD_SERVER_RUN),
                        "Files.newBufferedReader", "Files.readAllLines", "Files.writeString"),
                shape("PackProperties.set before #404, its reader left out (#410)", Map.of(
                        "old/PackProperties.java", oldPackWriterOnly, "old/TextureTools.java", OLD_TEXTURE_TOOLS),
                        "Files.readAllLines", "Files.writeString"),
                // Ordinary ways to open the file by hand.
                shape("a text read of a resolved path", java("""
                        void m(Path dir) throws IOException {
                            for (String line : Files.readAllLines(dir.resolve("server.properties"))) {}
                        }"""), "Files.readAllLines"),
                shape("java.util.Properties loading a local", java("""
                        void m(Path dir) throws IOException {
                            Path file = dir.resolve(ServerPropertiesFile.NAME);
                            Properties p = new Properties();
                            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { p.load(r); }
                        }"""), "Files.newBufferedReader"),
                shape("java.io streams and readers", java("""
                        void m(Path dir, File serverDir) throws IOException {
                            new Properties().load(new FileInputStream(new File(serverDir, "server.properties")));
                            new Properties().store(new java.io.FileWriter(Path.of(dir.toString(), "server.properties")
                                    .toFile()), null);
                            try (Scanner s = new Scanner(dir.resolve("server.properties").toFile())) {}
                        }"""), "new FileInputStream", "new FileWriter", "new Scanner"),
                shape("a write of a path built with Path.of", java("""
                        void m(String dir, String text) throws IOException {
                            Files.writeString(Path.of(dir, "server.properties"), text);
                        }"""), "Files.writeString"),
                shape("a field set in the constructor", java("""
                        private final Path props;
                        X(Path dir) { this.props = dir.resolve("server.properties"); }
                        String read() throws IOException { return Files.readString(props); }"""), "Files.readString"),
                shape("a constant name and a ternary", java("""
                        static final String NAME = "server.properties";
                        Stream<String> m(Path dir, Path custom) throws IOException {
                            Path f = custom != null ? custom : dir.resolve(NAME);
                            return Files.lines(f.toAbsolutePath());
                        }"""), "Files.lines"),
                shape("a method that returns the path", java("""
                        private Path props() { return config.serverDir().resolve("server.properties"); }
                        List<String> m() throws IOException { return Files.readAllLines(props()); }"""),
                        "Files.readAllLines"),
                shape("AtomicFile and OutputFile with a text writer", java("""
                        void m(Path dir, String text) throws IOException {
                            Path file = dir.resolve("server.properties");
                            AtomicFile.write(file, to -> Files.writeString(to, text));
                            OutputFile.replace(file, out -> {
                                try (Writer w = Files.newBufferedWriter(out)) { w.write(text); }
                            });
                            io.github.imnotstable.msc.process.AtomicFile.write(file, this::store);
                        }"""), "Files.writeString", "Files.newBufferedWriter",
                        "AtomicFile.write with a writer this check cannot see into"),
                shape("bytes read and decoded, and lines written", java("""
                        void m(Path dir, List<String> lines) throws IOException {
                            Path file = dir.resolve("server.properties");
                            String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                            Files.write(file, lines);
                        }"""), "Files.readAllBytes", "Files.write"),
                shape("a helper in another class, given the path (#410)", Map.of(
                        "a/Props.java", """
                                final class Props {
                                    static void set(Path file, String key, String value) throws IOException {
                                        List<String> lines = new ArrayList<>(Files.readAllLines(file));
                                        lines.add(key + "=" + value);
                                        Files.write(file, lines);
                                    }
                                }""",
                        "a/Caller.java", """
                                class Caller {
                                    void m(Path dir) throws IOException { Props.set(dir.resolve("server.properties"), "a", "b"); }
                                }"""), "Files.readAllLines", "Files.write"),
                shape("a helper chain in its own class and a constant of another class", Map.of(
                        "a/Names.java", "final class Names { public static final String PROPS = \"server.properties\"; }",
                        "a/Edit.java", """
                                final class Edit {
                                    void m(Path dir) throws IOException { write(dir.resolve(Names.PROPS)); }
                                    static void write(Path f) throws IOException { set(f, "x"); }
                                    private static void set(Path file, String text) throws IOException {
                                        try (BufferedWriter w = Files.newBufferedWriter(file)) { w.write(text); }
                                    }
                                }"""), "Files.newBufferedWriter"));
    }

    /** Each shape: what it is and its sources (path to text). */
    static Stream<Arguments> mustNotFlag() {
        return Stream.of(
                Arguments.of("ServerPropertiesFile's own callers", java("""
                        void m(Path dir, Map<String, String> values) throws IOException {
                            ServerPropertiesFile.set(dir.resolve(ServerPropertiesFile.NAME), values);
                            Map<String, String> p = ServerPropertiesFile.read(dir.resolve("server.properties"));
                        }""")),
                Arguments.of("ServerPropertiesFile itself", Map.of(SHARED, """
                        public final class ServerPropertiesFile {
                            public static final String NAME = "server.properties";
                            static byte[] bytes(Path dir) throws IOException {
                                return Files.newInputStream(dir.resolve(NAME)).readAllBytes();
                            }
                        }""")),
                Arguments.of("snapshots, checks, copies and moves", java("""
                        private Path serverProperties() { return config.serverDir().resolve("server.properties"); }
                        void m(Path backup, Path tmp) throws IOException {
                            Path file = serverProperties();
                            byte[] before = Files.exists(file) && Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
                            OutputFile.check(file, "server.properties");
                            OutputFile.write(file, to -> Files.write(to, before));
                            Files.copy(file, backup);
                            Files.move(tmp, file);
                        }""")),
                Arguments.of("the file named in messages and comments", java("""
                        static final String MODE = "set the game mode in server.properties";
                        // Files.readAllLines(dir.resolve("server.properties"))
                        void m(Path dir) throws IOException {
                            String eula = Files.readString(dir.resolve("eula.txt"));
                            throw new IOException("cannot read server.properties: " + eula);
                        }""")),
                Arguments.of("text reads and key=value splitting of other files (#409)", java("""
                        Map<String, String> m(Path file, String text) throws IOException {
                            for (String line : Files.readString(file).lines().toList()) {
                                int eq = line.indexOf('=');
                                int colon = line.indexOf(':');
                            }
                            String[] parts = text.lines().toArray(String[]::new)[0].split("=");
                            return Map.of();
                        }""")),
                Arguments.of("java.util.Properties of other files", java("""
                        void m(Path project) throws IOException {
                            Properties p = new Properties();
                            try (InputStream in = X.class.getResourceAsStream("/version.properties")) { p.load(in); }
                            try (Reader r = Files.newBufferedReader(project.resolve(".msc/deployed.properties"))) {
                                p.load(r);
                            }
                            p.store(Files.newOutputStream(project.resolve(".msc/deployed.properties")), null);
                        }""")),
                Arguments.of("a value read from the file, passed on", Map.of(
                        "a/Notes.java", "final class Notes { static String of(String url) throws IOException { "
                                + "return Files.readString(Path.of(url)); } }",
                        "a/Caller.java", """
                                class Caller {
                                    void m(Path dir) throws IOException {
                                        Path file = dir.resolve("server.properties");
                                        Notes.of(PackProperties.url(file));
                                    }
                                }""")),
                Arguments.of("a same-named variable or parameter elsewhere", java("""
                        private final Path file;
                        X(Path dir) { this.file = dir.resolve("server.properties"); }
                        void a(Path dir) { Path props = dir.resolve("server.properties"); ServerPropertiesFile.read(props); }
                        void b(Path dir) throws IOException { Path props = dir.resolve("level.dat"); Files.readString(props); }
                        void c(Path file) throws IOException { Files.readString(file); }""")),
                Arguments.of("a sibling of the file, and another file through AtomicFile", java("""
                        void m(Path dir, String s) throws IOException {
                            Files.readString(dir.resolve("server.properties").resolveSibling("ops.json"));
                            AtomicFile.write(dir.resolve("level.properties"), to -> Files.writeString(to, s));
                            Files.writeString(dir.resolve("server.properties.bak"), s);
                        }""")));
    }

    private static Arguments shape(String shape, Map<String, String> sources, String... calls) {
        return Arguments.of(shape, sources, List.of(calls));
    }

    /** {@code members} as the body of class X in a/X.java. */
    private static Map<String, String> java(String members) {
        return Map.of("a/X.java", "class X {\n" + members + "\n}\n");
    }

    /** A source as it was at 8f49c2a, before ADR 0033 (test resource). */
    private static String old(String name) throws IOException {
        try (InputStream in = ServerPropertiesAccessTest.class.getResourceAsStream(
                "/server-properties-before-404/" + name + ".txt")) {
            assertThat(in).as(name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** The callers of the old ServerProperties and PackProperties, their lines as they were at 8f49c2a. */
    private static final String OLD_SERVER_SETUP = """
            final class ServerSetup {
                void mode(Path dir, ServerProperties.Mode mode) {
                    steps.run(MODE, () -> ServerProperties.set(dir.resolve("server.properties"), mode.settings()));
                }
            }""";
    private static final String OLD_SERVER_RUN = """
            final class ServerRun {
                void sync() {
                    boolean offered = pack.ours(PackProperties.url(serverProperties()));
                }
                void offer() {
                    if (hosting && built != null && steps.run(OFFER_PACK, () -> PackProperties.write(serverProperties(),
                            built.url(), built.sha1(), required))) {
                        notes.add("offered");
                    }
                }
                private String modeLine() {
                    try {
                        return ServerProperties.modeLine(serverProperties());
                    } catch (IOException e) {
                        return null;
                    }
                }
                private Path serverProperties() {
                    return config.serverDir().resolve("server.properties");
                }
            }""";
    private static final String OLD_TEXTURE_TOOLS = """
            final class TextureTools {
                String run(Args args) {
                    return pack(args, hub, pack, config, serverDir.resolve("server.properties"));
                }
                private static String pack(Args args, AgentHub hub, ResourcePack pack, CoreConfig config, Path properties) {
                    if (offered && steps.run(OFFER, () -> PackProperties.write(properties, built.url(), built.sha1(), required))) {
                        return "offered";
                    }
                    return "";
                }
            }""";
}
