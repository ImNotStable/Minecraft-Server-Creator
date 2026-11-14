package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.buildlogic.RepoFiles;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins that a Minecraft server's {@code server.properties} is named, and so opened, only by msc-process's
 * {@code ServerPropertiesFile}, which reads and escapes it as the server does (ADR 0033, #391, #392). Hand-rolled
 * readers and writers had drifted on charset and escaping. Runs in msc-core's repoChecks task over every module's
 * production sources.
 * <p>A plain literal rule, with no data-flow tracking (ADR 0036): the file name appears inside a string literal in
 * production code (comments left out) only in {@code ServerPropertiesFile}. Everything else names the file as
 * {@code ServerPropertiesFile.NAME}, messages included, so a new hand-rolled reader has nowhere to get the path from
 * but ServerPropertiesFile, and a reviewer sees the constant. Template sources ({@code msc-templates/src/main/templates})
 * are scaffolded projects, not MSC code, and stay outside the scanned set (msc-core/build.gradle.kts).
 */
class ServerPropertiesAccessTest {
    /** Declared in msc-core/build.gradle.kts, which makes these files the repoChecks task's inputs too. */
    private static final String SET = "productionSources";
    private static final String SHARED =
            "msc-process/src/main/java/io/github/imnotstable/msc/process/ServerPropertiesFile.java";
    /** The files that may hold the name in a string literal, each with its reason. */
    private static final Map<String, String> ALLOWED = Map.of(
            SHARED, "the one reader and writer; it declares NAME and says the name in its own errors");
    private static final Pattern NAME = Pattern.compile("server\\.properties");

    @Test
    void scansEveryModulesProductionCode() {
        assertThat(RepoFiles.list(SET)).contains(SHARED,
                        "msc-core/src/main/java/io/github/imnotstable/msc/core/server/RconTarget.java",
                        "msc-bench/src/main/java/io/github/imnotstable/msc/bench/run/Workspaces.java")
                .noneMatch(f -> f.contains("/build/") || f.contains("/src/test/") || f.contains("/src/main/templates/"));
    }

    @Test
    void onlyServerPropertiesFileNamesServerProperties() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        for (String f : RepoFiles.list(SET)) {
            sources.put(f, Files.readString(RepoFiles.path(SET, f)));
        }
        assertThat(find(sources)).as("a string literal names server.properties outside msc-process's "
                        + "ServerPropertiesFile (ADR 0033, ADR 0036). Read and write the file only with "
                        + "ServerPropertiesFile.read(file) and ServerPropertiesFile.set(file, values), and name it as "
                        + "ServerPropertiesFile.NAME, in paths and in messages. A module that cannot depend on "
                        + "msc-process goes into ALLOWED in this test, with its reason")
                .isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustFlag")
    void flags(String shape, Map<String, String> sources, List<String> files) {
        assertThat(find(sources)).extracting(f -> f.substring(0, f.indexOf(':')))
                .containsExactlyInAnyOrderElementsOf(files);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mustNotFlag")
    void ignores(String shape, Map<String, String> sources) {
        assertThat(find(sources)).isEmpty();
    }

    /** {@code file:line} for each string literal naming the file, outside the allowed files. */
    static List<String> find(Map<String, String> sources) {
        List<String> out = new ArrayList<>();
        new TreeMap<>(sources).forEach((file, text) -> {
            if (ALLOWED.containsKey(file)) {
                return;
            }
            String code = SourceText.mask(text, false);
            String bare = SourceText.mask(text, true);
            for (MatchResult m : NAME.matcher(code).results().toList()) {
                // Blanked in bare: inside a string literal, not code such as server.properties().
                if (bare.charAt(m.start()) == ' ') {
                    out.add(file + ":" + SourceText.line(text, m.start()));
                }
            }
        });
        return out;
    }

    /** Each shape: what it is, its sources (path to text), and the files that must be reported (once per literal). */
    static Stream<Arguments> mustFlag() throws IOException {
        return Stream.of(
                // The readers and writers ADR 0033 replaced, as they were at 8f49c2a, with their callers' lines:
                // the name is reported where the path is built.
                shape("RconTarget.of before #391", Map.of("old/RconTarget.java", old("RconTarget.java")),
                        "old/RconTarget.java"),
                shape("PlayerTools.address before #391", Map.of("old/PlayerTools.java", old("PlayerTools.java")),
                        "old/PlayerTools.java", "old/PlayerTools.java"),
                shape("ServerProperties.read, set and modeLine before #392", Map.of(
                        "old/ServerProperties.java", old("ServerProperties.java"),
                        "old/ServerSetup.java", OLD_SERVER_SETUP, "old/ServerRun.java", OLD_SERVER_RUN),
                        "old/ServerSetup.java", "old/ServerRun.java"),
                shape("Workspaces.setProperties before #392", Map.of("old/Workspaces.java", old("Workspaces.java")),
                        "old/Workspaces.java"),
                shape("PackProperties before #404", Map.of("old/PackProperties.java", old("PackProperties.java"),
                        "old/ServerRun.java", OLD_SERVER_RUN, "old/TextureTools.java", OLD_TEXTURE_TOOLS),
                        "old/ServerRun.java", "old/TextureTools.java"),
                // Ordinary ways to name the file by hand, in Java and Kotlin, and in a message.
                shape("paths", java("""
                        void m(Path dir, File serverDir, String d) throws IOException {
                            Files.readAllLines(dir.resolve("server.properties"));
                            new FileInputStream(new File(serverDir, "server.properties"));
                            Files.writeString(java.nio.file.Path.of(d, "run/server.properties"), "");
                        }"""), "a/X.java", "a/X.java", "a/X.java"),
                shape("a text block and a Kotlin script", Map.of("a/X.java", "class X { String s = \"\"\"\n"
                        + "  server.properties\n  \"\"\"; }", "a/b.gradle.kts",
                        "File(dir, \"server.properties\").readText()"), "a/X.java", "a/b.gradle.kts"),
                shape("a message", java("""
                        static final String MODE = "set the game mode in server.properties";"""), "a/X.java"));
    }

    /** Each shape: what it is and its sources (path to text). */
    static Stream<Arguments> mustNotFlag() {
        return Stream.of(
                Arguments.of("ServerPropertiesFile's own callers", java("""
                        void m(Path dir, Map<String, String> values) throws IOException {
                            ServerPropertiesFile.set(dir.resolve(ServerPropertiesFile.NAME), values);
                            throw new IOException("cannot read " + ServerPropertiesFile.NAME);
                        }""")),
                Arguments.of("ServerPropertiesFile itself", Map.of(SHARED, """
                        public final class ServerPropertiesFile {
                            public static final String NAME = "server.properties";
                        }""")),
                Arguments.of("comments, code and other files", java("""
                        /** Reads {@code server.properties}. */
                        // Files.readAllLines(dir.resolve("server.properties"))
                        void m(Server server, Path dir) throws IOException {
                            var p = server.properties();
                            Files.readString(dir.resolve("eula.txt"));
                            Files.readString(dir.resolve("level.properties"));
                        }""")),
                // #424: the shapes the flow trackers took for server.properties. No name, nothing to report.
                Arguments.of("a second writer lambda and a sibling-block local (#424)", java("""
                        void takeBack(Path properties, byte[] offeredBefore) throws IOException {
                            OutputFile.write(properties, to -> Files.write(to, offeredBefore));
                            OutputFile.write(pack.root().resolve("taken-back.txt"), to -> Files.writeString(to, "x"));
                        }
                        String sync() throws IOException {
                            if (config.serverDir() == null) {
                                Path file = config.dataDir().resolve("pack-sync.log");
                                return Files.exists(file) ? Files.readString(file) : null;
                            }
                            Path file = serverProperties();
                            return null;
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
