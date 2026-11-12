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
        assertThat(Templates.ids()).containsExactly(
                Templates.FABRIC_MOD, Templates.MINESTOM_SERVER, Templates.PAPER_GAME, Templates.PAPER_PLUGIN, Templates.PAPER_SERVER);
        assertThat(Templates.info(Templates.PAPER_GAME)).containsEntry("platform", "paper");
        assertThat(Templates.info(Templates.FABRIC_MOD)).containsEntry("platform", "fabric");
        assertThat(Templates.info(Templates.MINESTOM_SERVER)).containsEntry("platform", "minestom");
        assertThat(Templates.versions()).containsKeys("fabricLoaderVersion", "fabricApiVersion", "fabricLoomVersion");
        assertThat(Templates.versions().get("fabricApiVersion")).endsWith("+" + Templates.versions().get("mcVersion"));
        assertThat(Templates.info(Templates.PAPER_PLUGIN)).containsEntry("platform", "paper").containsKey("description");
        assertThat(Templates.versions()).containsEntry("javaVersion", "25").containsKeys("paperVersion", "mcVersion", "mockbukkitVersion");
        assertThat(Templates.versions().get("paperVersion")).startsWith(Templates.versions().get("mcVersion") + ".build");
    }

    @Test
    void scaffoldsAFabricMod() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.FABRIC_MOD, dir.resolve("f"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md", "gradle/libs.versions.toml",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar",
                "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java",
                "src/main/java/com/example/spawnlobby/HelloCommand.java",
                "src/test/java/com/example/spawnlobby/HelloCommandTest.java",
                "src/main/resources/fabric.mod.json", "run/server.properties", "run/eula.txt");
        assertThat(relative(r)).noneMatch(p -> p.endsWith(".tmpl") || p.contains("template.properties"));
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/resources/fabric.mod.json")))
                .contains("\"id\": \"spawn-lobby\"", "\"server\": [\"com.example.spawnlobby.SpawnLobbyPlugin\"]",
                        "\"version\": \"${version}\"");
        assertThat(Files.readString(r.root().resolve("gradle/libs.versions.toml")))
                .contains("fabric-api = \"" + Templates.versions().get("fabricApiVersion") + "\"");
        assertThat(Files.readString(r.root().resolve("run/server.properties"))).contains("rcon.password=secret").contains("white-list=false");
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
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
        assertThat(Files.readString(r.root().resolve(".gitignore"))).contains(".msc/");
        assertThat(Files.readString(r.root().resolve("build.gradle.kts"))).doesNotContain("eula.agree");
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
        assertThat(Files.size(r.root().resolve("gradle/wrapper/gradle-wrapper.jar"))).isGreaterThan(10_000);
        assertThat(r.values()).containsEntry("rconPassword", "secret").containsEntry("serverPort", "25565").containsKey("mcVersion");
    }

    @Test
    void scaffoldsAPaperGame() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_GAME, dir.resolve("g"), vars);

        String pkg = "src/main/java/com/example/spawnlobby/";
        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md", "gradlew",
                "gradle/wrapper/gradle-wrapper.jar", "run/server.properties", "run/eula.txt",
                pkg + "SpawnLobbyPlugin.java", pkg + "Game.java", pkg + "GameCommand.java", pkg + "GameConfig.java",
                "src/test/java/com/example/spawnlobby/SpawnLobbyPluginTest.java",
                "src/main/resources/plugin.yml", "src/main/resources/config.yml");
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/resources/plugin.yml")))
                .contains("main: com.example.spawnlobby.SpawnLobbyPlugin", "spawn-lobby.admin:");
        assertThat(Files.readString(r.root().resolve(pkg + "SpawnLobbyPlugin.java")))
                .contains("ADMIN_PERMISSION = \"spawn-lobby.admin\"");
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
    }

    @Test
    void scaffoldsAMinestomServer() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.MINESTOM_SERVER, dir.resolve("m"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md", "gradle/libs.versions.toml",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar", "libs/msc-agent-minestom.jar",
                "src/main/java/com/example/spawnlobby/SpawnLobbyServer.java",
                "src/main/java/com/example/spawnlobby/Console.java",
                "src/test/java/com/example/spawnlobby/SpawnLobbyServerTest.java");
        assertThat(relative(r)).noneMatch(p -> p.endsWith(".tmpl") || p.contains("eula") || p.startsWith("run/"));
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyServer.java")))
                .contains("package com.example.spawnlobby;", "MscAgent.start();", "new AnvilLoader(", "server.start(\"127.0.0.1\", 25565);");
        assertThat(Files.readString(r.root().resolve("build.gradle.kts")))
                .contains("mainClass = \"com.example.spawnlobby.SpawnLobbyServer\"", "libs/msc-agent-minestom.jar");
        assertThat(Files.readString(r.root().resolve("gradle/libs.versions.toml")))
                .contains("minestom = \"" + Templates.versions().get("minestomVersion") + "\"");
        assertThat(Templates.versions().get("minestomVersion")).endsWith("-" + Templates.versions().get("minestomMcVersion"));
        assertThat(Files.size(r.root().resolve("libs/msc-agent-minestom.jar"))).isGreaterThan(10_000);
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
    }

    @Test
    void scaffoldsServerDefaultsOnly() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_SERVER, dir, vars);
        assertThat(relative(r)).containsExactlyInAnyOrder("eula.txt", "server.properties");
        assertThat(Files.readString(dir.resolve("eula.txt"))).containsPattern("(?m)^eula=false$").doesNotContainPattern("(?m)^eula=true$");
        assertThat(Files.readString(dir.resolve("server.properties"))).contains("generator-settings={\"biome\"");
    }

    @Test
    void writesEulaTrueOnlyWhenAccepted() throws IOException {
        Templates.scaffold(Templates.PAPER_SERVER, dir, vars.withAcceptEula(true));
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

    @Test
    void scaffoldsIntoAWorkspaceThatOnlyHasMscData() throws IOException {
        Files.createDirectories(dir.resolve(".msc"));
        Files.writeString(dir.resolve(".msc/tool-calls.jsonl"), "{}");

        Templates.scaffold(Templates.MINESTOM_SERVER, dir, vars);

        assertThat(dir.resolve("build.gradle.kts")).exists();
        assertThat(dir.resolve(".msc/tool-calls.jsonl")).hasContent("{}");
    }
}
