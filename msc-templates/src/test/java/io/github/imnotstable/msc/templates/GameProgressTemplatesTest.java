package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The game templates' {@code Progress} classes: what they teach (cheap changes on the game thread, serialization in
 * {@code flush()}) and that the three stay alike. Their behaviour is tested by each template's own ProgressTest,
 * which {@code verifyTemplates} runs.
 */
class GameProgressTemplatesTest {
    private static final String PROGRESS = "src/main/java/com/example/spawnlobby/Progress.java";
    private static final String PROGRESS_TEST = "src/test/java/com/example/spawnlobby/ProgressTest.java";

    @TempDir
    Path dir;

    private String file(String template, String path) throws IOException {
        Path root = dir.resolve(template);
        if (!Files.exists(root)) {
            Templates.scaffold(template, root, TemplateVariables.of("Spawn Lobby"));
        }
        return Files.readString(root.resolve(path));
    }

    private static String method(String source, String signature) {
        Matcher m = Pattern.compile(Pattern.quote(signature) + " \\{\\n(.*?)\\n    }", Pattern.DOTALL).matcher(source);
        assertThat(m.find()).as(signature).isTrue();
        return m.group(1);
    }

    /** The code without comments, so wording that differs per platform does not count. */
    private static String code(String source) {
        return source.lines().filter(l -> !l.strip().startsWith("*") && !l.strip().startsWith("/**"))
                .collect(Collectors.joining("\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {Templates.PAPER_GAME, Templates.FABRIC_GAME, Templates.MINESTOM_GAME})
    void changesOnlyTouchMemoryAndFlushSerializes(String template) throws IOException {
        String progress = file(template, PROGRESS);

        for (String change : List.of("public void setStage(UUID player, String stage)", "public void clear(UUID player)")) {
            assertThat(method(progress, change)).as(template + " " + change)
                    .contains("dirty.set(true);")
                    .doesNotContain("serialize", "toYaml", "store", "pending");
        }
        assertThat(method(progress, "public void flush() throws IOException"))
                .contains("dirty.getAndSet(false)", "serialize(Map.copyOf(stages))", "dirty.set(true);");
        assertThat(method(progress, "private UUID playerId(String key) throws IOException"))
                .contains("not a player UUID: ");
    }

    @Test
    void fabricAndMinestomShareOneProgressAndOneTest() throws IOException {
        assertThat(code(file(Templates.MINESTOM_GAME, PROGRESS))).isEqualTo(code(file(Templates.FABRIC_GAME, PROGRESS)));
        assertThat(file(Templates.MINESTOM_GAME, PROGRESS_TEST)).isEqualTo(file(Templates.FABRIC_GAME, PROGRESS_TEST));
    }

    @Test
    void fabricProgressNamesItsRealFile() throws IOException {
        assertThat(file(Templates.FABRIC_GAME, PROGRESS)).contains("{@code spawn-lobby-progress.properties}");
        assertThat(file(Templates.FABRIC_GAME, "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java"))
                .contains("\"spawn-lobby-progress.properties\"");
    }

    @Test
    void paperGameTurnsABadProgressFileIntoOneClearError() throws IOException {
        assertThat(file(Templates.PAPER_GAME, "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java"))
                .contains("fail(\"could not read progress.yml: \" + e.getMessage(), e);");
    }
}
