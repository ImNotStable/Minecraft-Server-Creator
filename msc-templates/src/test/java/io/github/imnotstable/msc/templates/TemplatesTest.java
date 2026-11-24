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
                Templates.FABRIC_MOD, Templates.FORGE_MOD, Templates.MINESTOM_SERVER, Templates.NEOFORGE_MOD,
                Templates.PAPER_PLUGIN, Templates.PAPER_SERVER, Templates.VELOCITY_PLUGIN, Templates.WATERFALL_PLUGIN);
        assertThat(Templates.info(Templates.WATERFALL_PLUGIN)).containsEntry("platform", "waterfall");
        assertThat(Templates.versions().get("waterfallVersion")).matches("\\d+\\.\\d+");
        assertThat(Templates.info(Templates.VELOCITY_PLUGIN)).containsEntry("platform", "velocity");
        assertThat(Templates.versions().get("velocityVersion")).matches("\\d+\\.\\d+\\.\\d+");
        assertThat(Templates.info(Templates.NEOFORGE_MOD)).containsEntry("platform", "neoforge");
        assertThat(Templates.info(Templates.FORGE_MOD)).containsEntry("platform", "forge");
        assertThat(Templates.versions().get("neoforgeVersion")).startsWith(Templates.versions().get("mcVersion") + ".");
        assertThat(Templates.versions().get("forgeVersion")).startsWith(Templates.versions().get("mcVersion") + "-");
        assertThat(Templates.versions()).containsKeys("quiltLoaderVersion", "quiltInstallerVersion");
        assertThat(Templates.info(Templates.FABRIC_MOD)).containsEntry("platform", "fabric");
        assertThat(Templates.info(Templates.MINESTOM_SERVER)).containsEntry("platform", "minestom");
        assertThat(Templates.versions()).containsKeys("fabricLoaderVersion", "fabricApiVersion", "fabricLoomVersion");
        assertThat(Templates.versions().get("fabricApiVersion")).endsWith("+" + Templates.versions().get("mcVersion"));
        assertThat(Templates.info(Templates.PAPER_PLUGIN)).containsEntry("platform", "paper").containsKey("description");
        assertThat(Templates.versions()).containsEntry("javaVersion", "25").containsKeys("paperVersion", "mcVersion", "mockbukkitVersion");
        assertThat(Templates.versions().get("paperVersion")).startsWith(Templates.versions().get("mcVersion") + ".build");
        // MockBukkit tests run on their own paper-api build of the same Minecraft version (ADR 0052).
        assertThat(Templates.versions().get("paperMockbukkitVersion")).startsWith(Templates.versions().get("mcVersion") + ".build");
    }

    /** ADR 0045: a proxy plugin with its own wrapper and no Minecraft server files (no server.properties, no EULA). */
    @Test
    void scaffoldsAVelocityPluginWithNoServerFiles() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.VELOCITY_PLUGIN, dir.resolve("v"), vars);

        assertThat(relative(r)).contains("build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md",
                        "gradle/libs.versions.toml", "gradlew", "gradle/wrapper/gradle-wrapper.jar",
                        "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java")
                .noneMatch(p -> p.startsWith("run/") || p.contains("eula"));
        assertThat(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java"))
                .content().contains("id = \"spawn-lobby\"");
        assertThat(r.root().resolve("gradle/libs.versions.toml")).content()
                .contains("velocity = \"" + Templates.versions().get("velocityVersion") + "\"");
    }

    @Test
    void scaffoldsAFabricMod() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.FABRIC_MOD, dir.resolve("f"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md", "gradle/libs.versions.toml",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar",
                "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java",
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
    void scaffoldsANeoForgeModWithAModIdWithoutHyphens() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.NEOFORGE_MOD, dir.resolve("n"), vars);

        assertThat(relative(r)).contains("build.gradle.kts", "CLAUDE.md", "gradlew", "run/eula.txt",
                "src/main/resources/META-INF/neoforge.mods.toml");
        assertNoPlaceholders(r);
        assertThat(Files.readString(r.root().resolve("src/main/resources/META-INF/neoforge.mods.toml")))
                .contains("modId = \"spawn_lobby\"", "[[dependencies.spawn_lobby]]", "version = \"${version}\"");
        assertThat(Files.readString(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java")))
                .contains("MOD_ID = \"spawn_lobby\"");
        assertThat(Files.readString(r.root().resolve("build.gradle.kts"))).contains("create(\"spawn_lobby\")");
    }

    @Test
    void scaffoldsAForgeMod() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.FORGE_MOD, dir.resolve("g"), vars);

        assertThat(relative(r)).contains("build.gradle.kts", "settings.gradle.kts", "CLAUDE.md", "gradlew",
                "src/main/resources/META-INF/mods.toml", "src/main/resources/pack.mcmeta");
        assertNoPlaceholders(r);
        assertThat(Files.readString(r.root().resolve("src/main/resources/META-INF/mods.toml")))
                .contains("modId = \"spawn_lobby\"", "loaderVersion = \"[${forgeMajor},)\"");
        // Forge reads no pack from a mod jar without pack.mcmeta and warns on every start (#254).
        assertThat(Files.readString(r.root().resolve("src/main/resources/pack.mcmeta")))
                .contains("\"description\": \"spawn_lobby resources\"", "\"min_format\": 82",
                        "\"max_format\": 2147483647");
        assertThat(Files.readString(r.root().resolve("gradle/libs.versions.toml")))
                .contains("forge = \"" + Templates.versions().get("forgeVersion") + "\"");
    }

    private static void assertNoPlaceholders(ScaffoldResult r) throws IOException {
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
    }

    @Test
    void scaffoldsAPaperPlugin() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("p"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", ".gitattributes", "CLAUDE.md",
                "gradle/libs.versions.toml", "gradlew", "gradlew.bat",
                "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java",
                "run/server.properties", "run/eula.txt");
        assertThat(relative(r)).noneMatch(p -> p.endsWith(".tmpl") || p.contains("template.properties"));

        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyPlugin.java")))
                .contains("package com.example.spawnlobby;", "public class SpawnLobbyPlugin extends JavaPlugin");
        // plugin.yml is written at build time from build.gradle.kts (resource-factory, ADR 0060).
        assertThat(r.root().resolve("src/main/resources/plugin.yml")).doesNotExist();
        assertThat(Files.readString(r.root().resolve("build.gradle.kts")))
                .contains("name = \"SpawnLobby\"", "main = \"com.example.spawnlobby.SpawnLobbyPlugin\"");
        assertThat(Files.readString(r.root().resolve("settings.gradle.kts"))).contains("rootProject.name = \"spawn-lobby\"");
        assertThat(Files.readString(r.root().resolve("run/server.properties")))
                .contains("rcon.password=secret", "rcon.port=25575", "online-mode=false", "server-ip=127.0.0.1");
        assertThat(Files.readString(r.root().resolve(".gitignore"))).contains(".msc/*\n!.msc/pack/\n");
        assertThat(Files.readString(r.root().resolve("build.gradle.kts"))).doesNotContain("eula.agree");
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
        assertThat(Files.size(r.root().resolve("gradle/wrapper/gradle-wrapper.jar"))).isGreaterThan(10_000);
        assertThat(r.values()).containsEntry("rconPassword", "secret").containsEntry("serverPort", "25565").containsKey("mcVersion");
    }

    private static void assertNoPlaceholdersLeft(ScaffoldResult r) throws IOException {
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
    }

    @Test
    void scaffoldsAMinestomServer() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.MINESTOM_SERVER, dir.resolve("m"), vars);

        assertThat(relative(r)).contains(
                "build.gradle.kts", "settings.gradle.kts", ".gitignore", "CLAUDE.md", "gradle/libs.versions.toml",
                "gradlew", "gradle/wrapper/gradle-wrapper.jar", "libs/msc-agent-minestom.jar",
                "src/main/java/com/example/spawnlobby/SpawnLobbyServer.java");
        assertThat(relative(r)).noneMatch(p -> p.endsWith(".tmpl") || p.contains("eula") || p.startsWith("run/"));
        for (Path f : r.files()) {
            if (!f.toString().endsWith(".jar")) {
                assertThat(Files.readString(f)).as(f.toString()).doesNotContain("{{");
            }
        }
        assertThat(Files.readString(r.root().resolve("src/main/java/com/example/spawnlobby/SpawnLobbyServer.java")))
                .contains("package com.example.spawnlobby;", "MscAgent.start();", "getOrDefault(\"SERVER_IP\", \"127.0.0.1\")",
                        "getOrDefault(\"SERVER_PORT\", \"25565\")", "Minestom listening on ");
        assertThat(Files.readString(r.root().resolve("build.gradle.kts")))
                .contains("mainClass = \"com.example.spawnlobby.SpawnLobbyServer\"", "libs/msc-agent-minestom.jar");
        assertThat(Files.readString(r.root().resolve("gradle/libs.versions.toml")))
                .contains("minestom = \"" + Templates.versions().get("minestomVersion") + "\"");
        assertThat(Templates.versions().get("minestomVersion")).endsWith("-" + Templates.versions().get("minestomMcVersion"));
        assertThat(Files.size(r.root().resolve("libs/msc-agent-minestom.jar"))).isGreaterThan(10_000);
        assertThat(Files.isExecutable(r.root().resolve("gradlew"))).isTrue();
    }

    /** ADR 0073: a project template ships its entry point as its only Java code, and no tests. */
    @Test
    void aProjectHasOnlyItsEntryPointAsCode() throws IOException {
        for (String id : Templates.ids()) {
            if (id.equals(Templates.PAPER_SERVER)) {
                continue;
            }
            ScaffoldResult r = Templates.scaffold(id, dir.resolve("only-" + id), vars);
            assertThat(relative(r)).as(id).filteredOn(p -> p.startsWith("src/") && p.endsWith(".java"))
                    .containsExactly("src/main/java/com/example/spawnlobby/SpawnLobby"
                            + (id.equals(Templates.MINESTOM_SERVER) ? "Server" : "Plugin") + ".java");
            assertThat(relative(r)).as(id).noneMatch(p -> p.startsWith("src/test/"));
        }
    }

    @Test
    void claudeMdBulletsAreWhole() throws IOException {
        for (String id : Templates.ids()) {
            if (id.equals(Templates.PAPER_SERVER)) {
                continue;
            }
            Path root = Templates.scaffold(id, dir.resolve(id), vars).root();
            // A bullet starting with punctuation or a sentence glued after a closing `...`) means two bullets got spliced.
            assertThat(Files.readString(root.resolve("CLAUDE.md"))).as(id)
                    .doesNotContainPattern("(?m)^- +[^\\w`*\\[(\"]")
                    .doesNotContainPattern("`\\)[A-Z]");
        }
    }

    @Test
    void refusesNamesWhoseClassClashesWithAnImport() {
        assertThatThrownBy(() -> Templates.scaffold(Templates.MINESTOM_SERVER, dir.resolve("m"), TemplateVariables.of("Minecraft")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MinecraftServer").hasMessageContaining("Minecraft");
        assertThatThrownBy(() -> Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("p"), TemplateVariables.of("Java")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JavaPlugin");
        assertThatThrownBy(() -> Templates.scaffold(Templates.FABRIC_MOD, dir.resolve("f"), vars.withMainClass("DedicatedServerModInitializer")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("net.fabricmc.api.DedicatedServerModInitializer");
        assertThat(dir.resolve("m")).doesNotExist();
        assertThat(dir.resolve("p")).doesNotExist();
        assertThat(dir.resolve("f")).doesNotExist();
    }

    @Test
    void refusesModIdsAndPackagesTheLoaderOwns() throws IOException {
        record Case(String template, String name) {}
        for (Case c : List.of(new Case(Templates.FABRIC_MOD, "Minecraft"), new Case(Templates.FABRIC_MOD, "Fabric Api"),
                new Case(Templates.FABRIC_MOD, "Java"), new Case(Templates.NEOFORGE_MOD, "Minecraft"),
                new Case(Templates.NEOFORGE_MOD, "Neoforge"), new Case(Templates.FORGE_MOD, "Forge"),
                // MSC's own agent runs next to the project on the dev server (#293).
                new Case(Templates.FABRIC_MOD, "Msc Agent"), new Case(Templates.FABRIC_MOD, "msc-agent"),
                new Case(Templates.NEOFORGE_MOD, "MSC agent"), new Case(Templates.FORGE_MOD, "msc_agent"),
                new Case(Templates.PAPER_PLUGIN, "Msc Agent"), new Case(Templates.PAPER_PLUGIN, "MscAgent"),
                new Case(Templates.PAPER_PLUGIN, "Minecraft"), new Case(Templates.PAPER_PLUGIN, "Bukkit"),
                // ...and its jar, msc-agent-<platform>.jar, sits where dev_cycle deploys <projectId>.jar (#311).
                new Case(Templates.PAPER_PLUGIN, "Msc Agent Paper"), new Case(Templates.PAPER_PLUGIN, "msc-agent-paper"),
                new Case(Templates.FABRIC_MOD, "msc-agent-fabric"), new Case(Templates.FABRIC_MOD, "MSC Agent Fabric"),
                new Case(Templates.NEOFORGE_MOD, "msc-agent-neoforge"), new Case(Templates.FORGE_MOD, "Msc Agent Forge"))) {
            Path target = dir.resolve(c.template() + "-" + c.name());
            assertThatThrownBy(() -> Templates.scaffold(c.template(), target, TemplateVariables.of(c.name())))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("keeps for itself");
            assertThat(target).doesNotExist();
        }
        assertThatThrownBy(() -> Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("b"), vars.withPackageName("org.bukkit.lobby")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("org.bukkit");
        // Only the exact ids are the loader's; another platform's id is fine.
        ScaffoldResult fabric = Templates.scaffold(Templates.FABRIC_MOD, dir.resolve("ok1"), TemplateVariables.of("Minecraft Tools"));
        assertThat(fabric.root().resolve("src/main/resources/fabric.mod.json")).content().contains("\"id\": \"minecraft-tools\"");
        ScaffoldResult forge = Templates.scaffold(Templates.FORGE_MOD, dir.resolve("ok2"), TemplateVariables.of("Neoforge"));
        assertThat(forge.root().resolve("src/main/resources/META-INF/mods.toml")).content().contains("modId = \"neoforge\"");
        Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("ok3"), vars.withPackageName("org.bukkitlobby"));
        // Another platform's agent jar is not in this project's addon folder.
        Templates.scaffold(Templates.PAPER_PLUGIN, dir.resolve("ok4"), TemplateVariables.of("Msc Agent Fabric"));
        Templates.scaffold(Templates.FABRIC_MOD, dir.resolve("ok5"), TemplateVariables.of("Msc Agent Paper Tools"));
    }

    @Test
    void scaffoldsServerDefaultsOnly() throws IOException {
        ScaffoldResult r = Templates.scaffold(Templates.PAPER_SERVER, dir, vars);
        assertThat(relative(r)).containsExactlyInAnyOrder("eula.txt", "server.properties");
        assertThat(Files.readString(dir.resolve("eula.txt"))).containsPattern("(?m)^eula=false$").doesNotContainPattern("(?m)^eula=true$");
        assertThat(Files.readString(dir.resolve("server.properties"))).contains("generator-settings={\"biome\"");
    }

    @Test
    void serverDefaultsTakeAnyName() throws IOException {
        // paper-server is config only, so plugin-name rules do not apply to it.
        Templates.scaffold(Templates.PAPER_SERVER, dir, TemplateVariables.of("Msc Agent"));
        assertThat(dir.resolve("server.properties")).content().contains("motd=MscAgent dev server");
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
