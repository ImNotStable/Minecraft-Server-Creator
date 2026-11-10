package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplatesTest {
    @TempDir
    Path dir;

    private final TemplateVariables vars = TemplateVariables.of("Spawn Lobby").withRconPassword("secret");

    private List<String> relative(ScaffoldResult r) {
        return r.files().stream().map(f -> r.root().relativize(f).toString().replace('\\', '/')).toList();
    }

    @Test
    void listsTemplates() {
        assertThat(Templates.ids()).containsExactly(Templates.PAPER_PLUGIN, Templates.PAPER_SERVER);
        assertThat(Templates.info(Templates.PAPER_PLUGIN)).containsEntry("platform", "paper").containsKey("description");
        assertThat(Templates.versions()).containsEntry("javaVersion", "25").containsKeys("paperVersion", "mcVersion", "mockbukkitVersion");
        assertThat(Templates.versions().get("paperVersion")).startsWith(Templates.versions().get("mcVersion") + ".build");
    }

    @Test
    void scaffoldsAPaperPlugin() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("p"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", ".gitattributes", "CLAUDE.md",
                "gradle/libs.versions.toml", "gradlew", "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java",
                "src/test/java/com/example/spawnlobby/SpawnLobbyPluginTest.java",
                "src/main/resources/plugin.yml", "run/server.properties", "run/eula.txt");
        assertThat(relative(r)).noneMatch(p -> p.endsWith(".tmpl") || p.contains("template.properties"));

        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java")))
                .contains("package com.example.spawnlobby;", "public class SpawnLobbyPlugin extends JavaPlugin");
        assertThat(Files.readString(r.root().resolve("src/main/resources/plugin.yml")))
                .contains("name: SpawnLobby", "main: com.example.spawnlobby.SpawnLobbyPlugin", "version: '${version}'");
        assertThat(Files.readString(r.root().resolve("settings.gradle.kts"))).contains("rootProject.name = \"spawn-lobby\"");
        assertThat(Files.readString(r.root().resolve("run/server.properties")))
                .contains("rcon.password=secret", "rcon.port=25575", "online-mode=false", "server-ip=127.0.0.1");
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
        assertThat(Files.size(r.root().resolve("gradle/wrapper/gradle-wrapper.jar"))).isGreaterThan(10_000);
        assertThat(r.values()).containsEntry("rconPassword", "secret").containsEntry("serverPort", "25565").containsKey("mcVersion");
    }

    @Test
    void scaffoldsServerDefaultsOnly() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_SERVER, dir, vars);
        assertThat(relative(r)).containsExactlyInAnyOrder("eula.txt", "server.properties");
        assertThat(Files.readString(dir.resolve("eula.txt"))).contains("eula=true");
    }

    @Test
    void refusesUnknownIdsAndNonEmptyTargets() throws IOException {
        assertThatThrownBy(() -> Templates.scaffold("nope", dir, vars))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paper-plugin");
        Files.writeString(dir.resolve("existing.txt"), "x");
        assertThatThrownBy(() -> Templates.scaffold(Templates.PAPER_SERVER, dir, vars))
                .isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(dir.resolve("existing.txt"))).isEqualTo("x");
    }
}
