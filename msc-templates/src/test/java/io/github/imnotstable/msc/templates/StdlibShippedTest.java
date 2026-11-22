package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What a project gets of msc-stdlib (ADR 0066): the Maven repository its build resolves, one API index, the sources by
 * line, and a graphify graph built from exactly those sources. A graph older than the sources fails here: run
 * {@code scripts/stdlib-graph.sh}.
 */
class StdlibShippedTest {
    private static final Map<String, String> PLATFORM = Map.of(
            Templates.PAPER_PLUGIN, "paper", Templates.PAPER_GAME, "paper",
            Templates.MINESTOM_SERVER, "minestom", Templates.MINESTOM_GAME, "minestom",
            Templates.FABRIC_MOD, "fabric", Templates.FABRIC_GAME, "fabric");

    @TempDir
    Path dir;

    @ParameterizedTest
    @ValueSource(strings = {"paper-plugin", "paper-game", "minestom-server", "minestom-game", "fabric-mod", "fabric-game"})
    void aProjectGetsTheLibraryItsIndexSourcesAndAFreshGraph(String id) throws Exception {
        Path root = Templates.scaffold(id, dir.resolve(id), TemplateVariables.of("Verify Me")).root();
        String adapter = "msc-stdlib-" + PLATFORM.get(id);
        String version = Templates.versions().get("mscStdlibVersion");
        Path repo = root.resolve("libs/repo/io/github/imnotstable/msc");
        assertThat(repo.resolve("msc-stdlib/" + version + "/msc-stdlib-" + version + ".jar")).isRegularFile();
        assertThat(repo.resolve(adapter + "/" + version + "/" + adapter + "-" + version + ".pom")).isRegularFile();
        try (Stream<Path> modules = Files.list(repo)) {
            assertThat(modules.map(p -> p.getFileName().toString()).toList()).containsExactlyInAnyOrder("msc-stdlib", adapter);
        }

        String api = Files.readString(root.resolve("libs/stdlib/API.md"));
        assertThat(api).contains("## db.Repository<T extends Record>", "- static <T extends Record> Repository<T> of(",
                "# " + adapter + " " + version);

        Path src = root.resolve("libs/stdlib/src");
        assertThat(src.resolve("msc-stdlib/io/github/imnotstable/msc/stdlib/db/Repository.java")).isRegularFile();
        Path graph = root.resolve("libs/stdlib/graphify-out");
        assertThat(JsonParser.parseString(Files.readString(graph.resolve("graph.json"))).getAsJsonObject()
                .getAsJsonArray("nodes")).isNotEmpty();
        assertThat(sourcesHash(src)).as("libs/stdlib/graphify-out is older than the msc-stdlib sources: run "
                + "scripts/stdlib-graph.sh").isEqualTo(Files.readString(graph.resolve("sources.sha256")).strip());
    }

    /** The hash scripts/stdlib-graph.sh writes: each .java path (sorted, relative, /) then its bytes. */
    static String sourcesHash(Path root) throws IOException, NoSuchAlgorithmException {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        List<String> rel = files.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        for (String r : rel) {
            sha.update((r + "\n").getBytes(StandardCharsets.UTF_8));
            sha.update(Files.readAllBytes(root.resolve(r)));
        }
        return HexFormat.of().formatHex(sha.digest());
    }
}
