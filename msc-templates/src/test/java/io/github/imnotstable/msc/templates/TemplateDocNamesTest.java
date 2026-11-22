package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every Java name a scaffolded project's CLAUDE.md or README.md writes in backticks (a class, method, field, event or
 * annotation) is one the template's builds compile (#480: neoforge-mod named {@code BlockEvent.BreakEvent}, which its
 * NeoForge does not have). A name counts when it is in the project's own code or in a {@code java} block of its docs;
 * {@code verifyTemplates} builds the project. Paths, files and console lines are not names.
 */
class TemplateDocNamesTest {
    /** Placeholders the docs use for "any": {@code ChatFormatting.X}, {@code addListener(EventClass.class, ...)}. */
    private static final Set<String> PLACEHOLDERS = Set.of("X", "EventClass");
    /** Names the docs tell you not to use, and examples that are not code, per template. */
    private static final Map<String, Set<String>> NOT_CODE = Map.of(
            Templates.PAPER_PLUGIN, Set.of("ChatColor", "Hi"),
            Templates.FABRIC_MOD, Set.of("ServerPlayerEntity", "Text", "World"),
            Templates.NEOFORGE_MOD, Set.of("COMMON", "SERVER"),
            Templates.FORGE_MOD, Set.of("Missing"),
            Templates.VELOCITY_PLUGIN, Set.of("Hi"));

    @TempDir
    Path dir;

    static Stream<String> projects() {
        return TemplateDocsTest.projects();
    }

    @ParameterizedTest
    @MethodSource("projects")
    void everyNameInTheDocsIsInCodeThatBuilds(String id) throws IOException {
        Path project = Templates.scaffold(id, dir.resolve(id), TemplateVariables.of("Verify Me")).root();

        assertThat(unproven(project, id)).as(id + ": names in CLAUDE.md or README.md that no built code has").isEmpty();
    }

    @Test
    void aNameTheTemplateDoesNotHaveIsReported() throws IOException {
        Path project = Templates.scaffold(Templates.NEOFORGE_MOD, dir.resolve("old"), TemplateVariables.of("Verify Me")).root();
        Files.delete(project.resolve("CLAUDE.md"));
        Files.writeString(project.resolve("README.md"),
                "Listen for `BlockEvent.BreakEvent` with `NeoForge.EVENT_BUS.addListener(...)`, see `run/eula.txt`.\n");

        assertThat(unproven(project, Templates.NEOFORGE_MOD)).contains("BreakEvent")
                .doesNotContain("NeoForge", "EVENT_BUS", "addListener", "run", "eula");
    }

    @Test
    void namesAreTheCodePartsOfASpan() {
        assertThat(DocCode.names("source.sendSuccess(() -> component, false)")).containsExactly("sendSuccess");
        assertThat(DocCode.names("BlockEvent.BreakEvent.BUS")).containsExactly("BlockEvent", "BreakEvent", "BUS");
        assertThat(DocCode.names("Block.OAK_STAIRS.withProperty(\"facing\", \"north\")"))
                .containsExactly("Block", "OAK_STAIRS", "withProperty");
        assertThat(DocCode.names("flush()")).containsExactly("flush");
        assertThat(DocCode.names("ok(23999L), no(24000L)")).containsExactly("ok", "no");
        for (String none : new String[] {"run/eula.txt", "./gradlew test --tests '*GreeterTest'", "fabric.mod.json",
                "keep-inventory", "tick sprint 600", "server_setup mode=game", "rcon.password", "eula=true"}) {
            assertThat(DocCode.names(none)).as(none).isEmpty();
        }
    }

    private static Set<String> unproven(Path project, String id) throws IOException {
        Set<String> known = new LinkedHashSet<>(DocCode.projectWords(project));
        for (DocCode.Block b : DocCode.javaBlocks(project)) {
            known.addAll(DocCode.words(b.code()));
        }
        known.addAll(PLACEHOLDERS);
        known.addAll(NOT_CODE.getOrDefault(id, Set.of()));
        Set<String> missing = new TreeSet<>();
        for (String doc : TemplateDocs.DOCS) {
            Path file = project.resolve(doc);
            if (Files.exists(file)) {
                for (String span : DocCode.spans(Files.readString(file))) {
                    DocCode.names(span).stream().filter(n -> !known.contains(n)).forEach(missing::add);
                }
            }
        }
        return missing;
    }
}
