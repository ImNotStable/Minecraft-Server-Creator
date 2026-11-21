package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.templates.TemplateVariables;
import io.github.imnotstable.msc.templates.Templates;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlatformTest {
    @TempDir
    Path dir;

    @Test
    void launchCommandsDifferPerPlatform() {
        assertThat(Platform.PAPER.launchCommand("java")).containsExactly("java", "-Xmx2G", "-jar", "paper.jar", "--nogui");
        assertThat(Platform.FABRIC.launchCommand("java"))
                .containsExactly("java", "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui");
        assertThat(Platform.MINESTOM.launchCommand("java")).containsExactly("java", "-Xmx1G", "-jar", "server.jar");
        assertThat(Platform.MINESTOM.needsEula()).isFalse();
        assertThat(Platform.MINESTOM.addonDir()).isNull();
        assertThat(Platform.of(" Fabric\n")).isEqualTo(Platform.FABRIC);
        assertThatThrownBy(() -> Platform.of("spigot")).isInstanceOf(ToolException.class)
                .hasMessageContaining("paper, purpur, folia, fabric, quilt, neoforge, forge, minestom, velocity");
    }

    @Test
    void namesListPlatformsAsProse() {
        assertThat(Platform.names(p -> p == Platform.PAPER, "or")).isEqualTo("Paper");
        assertThat(Platform.names(p -> p.agent().equals("paper"), "or")).isEqualTo("Paper, Purpur or Folia");
        assertThat(Platform.names(Platform::needsEula, "and")).isEqualTo("Paper, Purpur, Folia, Fabric, Quilt, NeoForge and Forge");
    }

    @Test
    void forksAndCompatibleLoadersShareTheirUpstreamsAgent() {
        assertThat(Platform.PURPUR.agent()).isEqualTo("paper");
        assertThat(Platform.PURPUR.addonDir()).isEqualTo("plugins");
        assertThat(Platform.PURPUR.launchCommand("java")).containsExactly("java", "-Xmx2G", "-jar", "purpur.jar", "--nogui");
        assertThat(Platform.QUILT.agent()).isEqualTo("fabric");
        assertThat(Platform.QUILT.launchCommand("java"))
                .containsExactly("java", "-Xmx2G", "-jar", "quilt-server-launch.jar", "nogui");
        assertThat(Platform.NEOFORGE.agent()).isEqualTo("neoforge");
        assertThat(Platform.NEOFORGE.launchCommand("java")).containsExactly("java", "-Xmx2G", "-jar", "server.jar", "nogui");
        assertThat(Platform.FORGE.agent()).isEqualTo("forge");
        assertThat(Platform.FORGE.launchCommand("java")).containsExactly("java", "-Xmx2G", "-jar", "forge-server.jar", "nogui");
        for (Platform p : Platform.values()) {
            assertThat(p.needsEula()).as(p.id()).isEqualTo(p != Platform.MINESTOM && p != Platform.VELOCITY);
            assertThat(p.proxy()).as(p.id()).isEqualTo(p == Platform.VELOCITY);
            assertThat(p.noWorldTools() != null).as(p.id()).isEqualTo(p.proxy());
        }
    }

    /** ADR 0045: Folia runs Paper's agent like Paper; Velocity is a proxy with its own jar, plugins and no agent. */
    @Test
    void foliaIsAPaperForkAndVelocityAProxy() throws Exception {
        assertThat(Platform.FOLIA.agent()).isEqualTo("paper");
        assertThat(Platform.FOLIA.agentJar()).isEqualTo("msc-agent-paper.jar");
        assertThat(Platform.FOLIA.launchCommand("java")).containsExactly("java", "-Xmx2G", "-jar", "folia.jar", "--nogui");
        assertThat(Platform.VELOCITY.addonDir()).isEqualTo("plugins");
        assertThat(Platform.VELOCITY.launchCommand("java")).containsExactly("java", "-Xmx512M", "-jar", "velocity.jar");
        assertThat(Platform.VELOCITY.noWorldTools()).startsWith("Velocity is a proxy").doesNotContain("\n");
        Files.writeString(dir.resolve("build.gradle.kts"), "dependencies { compileOnly(libs.velocity.api) }");
        Files.createDirectories(dir.resolve("gradle"));
        Files.writeString(dir.resolve("gradle/libs.versions.toml"),
                "velocity-api = { module = \"com.velocitypowered:velocity-api\", version.ref = \"velocity\" }\n");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.VELOCITY);
    }

    @Test
    void modMetadataNamesTheLoader() throws Exception {
        Path resources = Files.createDirectories(dir.resolve("src/main/resources/META-INF"));
        Files.writeString(resources.resolve("mods.toml"), "");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.FORGE);
        Files.writeString(resources.resolve("neoforge.mods.toml"), "");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.NEOFORGE);
        Files.writeString(dir.resolve("src/main/resources/fabric.mod.json"), "{}");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.FABRIC);
        Files.writeString(dir.resolve("src/main/resources/quilt.mod.json"), "{}");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.QUILT);
    }

    /** #636: only a remembered platform of another agent family is one whose server does not run the project. */
    @Test
    void otherFamilyIsARememberedPlatformThatDoesNotRunTheProject() throws Exception {
        CoreConfig config = CoreConfig.forWorkspace(dir);
        assertThat(Platform.otherFamily(config, List.of(Platform.FABRIC))).isNull();
        Platform.remember(config, Platform.QUILT);
        assertThat(Platform.otherFamily(config, List.of(Platform.FABRIC))).isNull();
        assertThat(Platform.otherFamily(config, List.of(Platform.PAPER))).isEqualTo(Platform.QUILT);
        Platform.remember(config, Platform.PURPUR);
        assertThat(Platform.otherFamily(config, List.of(Platform.PAPER))).isNull();
        assertThat(Platform.otherFamily(config, List.of(Platform.MINESTOM))).isEqualTo(Platform.PURPUR);
    }

    /** #642: a jar that is a Paper and a Velocity plugin runs on both, so either workspace serves it. */
    @Test
    void aProjectForSeveralPlatformsIsServedByAnyOfThem() throws Exception {
        Path resources = Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(resources.resolve("plugin.yml"), "name: demo\n");
        assertThat(Platform.runsOn(dir)).containsExactly(Platform.PAPER);
        Files.writeString(resources.resolve("velocity-plugin.json"), "{}");
        List<Platform> both = Platform.runsOn(dir);
        assertThat(both).containsExactly(Platform.PAPER, Platform.VELOCITY);
        assertThat(Platform.detect(dir)).isEqualTo(Platform.PAPER);
        CoreConfig config = CoreConfig.forWorkspace(dir);
        assertThat(Platform.serving(config, both)).isEqualTo(Platform.PAPER);
        for (Platform workspace : List.of(Platform.VELOCITY, Platform.FOLIA, Platform.PAPER)) {
            Platform.remember(config, workspace);
            assertThat(Platform.serving(config, both)).isEqualTo(workspace);
            assertThat(Platform.otherFamily(config, both)).isNull();
        }
        Platform.remember(config, Platform.FABRIC);
        assertThat(Platform.serving(config, both)).isEqualTo(Platform.PAPER);
        assertThat(Platform.otherFamily(config, both)).isEqualTo(Platform.FABRIC);
        // The project jar of a Minestom server is no plugin as well: a build that only mentions it does not add it.
        Files.writeString(dir.resolve("build.gradle.kts"), "dependencies { compileOnly(\"net.minestom:minestom\") }");
        assertThat(Platform.runsOn(dir)).containsExactly(Platform.PAPER, Platform.VELOCITY);
    }

    /** #648: ModDevGradle's legacyforge plugin id contains its NeoForge one; a Forge mod built with it is Forge only. */
    @Test
    void aLegacyForgeModRunsOnForgeOnly() throws Exception {
        write(dir.resolve("src/main/resources/META-INF/mods.toml"));
        Files.writeString(dir.resolve("build.gradle"), "plugins {\n    id 'net.neoforged.moddev.legacyforge' version '2.0.91'\n}\n");
        assertThat(Platform.runsOn(dir)).containsExactly(Platform.FORGE);
        Files.delete(dir.resolve("src/main/resources/META-INF/mods.toml"));
        assertThat(Platform.runsOn(dir)).containsExactly(Platform.FORGE);
        CoreConfig config = CoreConfig.forWorkspace(dir);
        Platform.remember(config, Platform.NEOFORGE);
        assertThat(Platform.otherFamily(config, Platform.runsOn(dir))).isEqualTo(Platform.NEOFORGE);
        // NeoForge's own plugin beside it, in another line, still counts.
        Files.writeString(dir.resolve("build.gradle.kts"), "plugins { id(\"net.neoforged.moddev\") }\n");
        assertThat(Platform.runsOn(dir)).containsExactly(Platform.FORGE, Platform.NEOFORGE);
    }

    /**
     * #648: a sign's text, written in a build file as a plugin id, a dependency, a version-catalog entry and a line of
     * its own, makes the project run on the sign's platform and no other; a descriptor's name there makes it run on
     * nothing, and the descriptor file on its platform only. So no sign's text is another platform's sign as well.
     */
    @Test
    void everySignsTextShowsItsOwnPlatformOnly() throws Exception {
        int n = 0;
        for (Platform.Sign sign : Platform.SIGNS) {
            for (String text : sign.texts()) {
                Path project = Files.createDirectories(dir.resolve("p" + n++));
                Files.writeString(project.resolve("build.gradle.kts"), "plugins {\n    id(\"" + text + "\") version \"1.0\"\n}\n"
                        + "dependencies {\n    compileOnly(\"com.example:" + text + ":1.0\")\n}\n" + text + "\n}\n");
                Files.createDirectories(project.resolve("gradle"));
                Files.writeString(project.resolve("gradle/libs.versions.toml"),
                        "[plugins]\nx = { id = \"" + text + "\", version = \"1.0\" }\n");
                List<Platform> expected = sign.file() == null ? List.of(sign.platform()) : List.of();
                assertThat(Platform.runsOn(project)).as(text).isEqualTo(expected);
                if (sign.file() != null) {
                    Files.delete(project.resolve("build.gradle.kts"));
                    Files.delete(project.resolve("gradle/libs.versions.toml"));
                    write(project.resolve("src/main/resources").resolve(sign.file()));
                    assertThat(Platform.runsOn(project)).as(text).containsExactly(sign.platform());
                }
            }
        }
    }

    @Test
    void theRememberedPlatformWinsThenTheProjectFilesThenPaper() throws Exception {
        CoreConfig config = CoreConfig.forWorkspace(dir);
        assertThat(Platform.current(config)).isEqualTo(Platform.PAPER);
        Files.writeString(dir.resolve("build.gradle.kts"), "dependencies { implementation(libs.minestom) }");
        assertThat(Platform.detect(dir)).isEqualTo(Platform.MINESTOM);
        assertThat(Platform.current(config)).isEqualTo(Platform.MINESTOM);
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("src/main/resources/fabric.mod.json"), "{}");
        assertThat(Platform.current(config)).isEqualTo(Platform.FABRIC);
        Platform.remember(config, Platform.PAPER);
        assertThat(Platform.current(config)).isEqualTo(Platform.PAPER);
        Files.writeString(Platform.file(config), "nonsense");
        assertThat(Platform.current(config)).isEqualTo(Platform.FABRIC);
    }

    @Test
    void apiJarsAreFoundWhereEachPlatformsBuildLeavesThem() throws Exception {
        Path files = dir.resolve("caches/modules-2/files-2.1");
        Path paper = jar(files.resolve("io.papermc.paper/paper-api/26.3/abc/paper-api-26.3.jar"));
        jar(files.resolve("io.papermc.paper/paper-api/26.3/abd/paper-api-26.3-sources.jar"));
        Path adventure = jar(files.resolve("net.kyori/adventure-api/5.0/abc/adventure-api-5.0.jar"));
        Path minestom = jar(files.resolve("net.minestom/minestom/2026/abc/minestom-2026.jar"));
        Path testing = jar(files.resolve("net.minestom/testing/2026/abc/testing-2026.jar"));
        Path events = jar(files.resolve("net.fabricmc.fabric-api/fabric-lifecycle-events-v1/2.0/abc/fabric-lifecycle-events-v1-2.0.jar"));
        Path loader = jar(files.resolve("net.fabricmc/fabric-loader/0.20.1/abc/fabric-loader-0.20.1.jar"));
        Path minecraft = jar(dir.resolve("caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-server-deobf/26.3/minecraft-server-deobf-26.3.jar"));
        assertThat(ApiIndex.findJars(dir, "paper")).containsExactly(paper, adventure);
        assertThat(ApiIndex.findJars(dir, "minestom")).containsExactly(minestom, testing, adventure);
        assertThat(ApiIndex.findJars(dir, "fabric")).containsExactly(minecraft, events, loader);
        assertThat(ApiIndex.findJars(dir.resolve("empty"), "fabric")).isEmpty();
    }

    /**
     * #593, #599: what {@link Platform#detect} makes of a project's files. A token is a descriptor file under
     * {@code src/main/resources} ({@code templates:} under {@code src/main/templates}), a Gradle plugin id
     * ({@code plugin:}), a top-level DSL block ({@code block:}) or a dependency ({@code build:}). The descriptor the server
     * loads, or what writes it, beats the platform's own Gradle plugin, which beats a dependency word; within a tier a
     * game server beats Velocity, so a plugin built for Paper and Velocity is Paper. The rows after "upstream" are the
     * layouts of public templates: NeoForge's MDK-1.21.1-ModDevGradle and MDK-1.21.1-NeoGradle, Forge's 1.21.1 MDK,
     * fabric-example-mod, quilt-template-mod and paperweight-test-plugin.
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(nullValues = "none", value = {
            "plugin.yml, PAPER",
            "paper-plugin.yml, PAPER",
            "velocity-plugin.json, VELOCITY",
            "build:velocity-api, VELOCITY",
            "build:minestom, MINESTOM",
            "build:paper-api, none",
            "none, none",
            "plugin.yml build:paper-api build:velocity-api, PAPER",
            "paper-plugin.yml build:velocity-api, PAPER",
            "plugin.yml velocity-plugin.json build:velocity-api, PAPER",
            "velocity-plugin.json build:paper-api, VELOCITY",
            "plugin.yml build:minestom, PAPER",
            "fabric.mod.json build:velocity-api, FABRIC",
            "quilt.mod.json fabric.mod.json, QUILT",
            "META-INF/mods.toml, FORGE",
            "META-INF/neoforge.mods.toml META-INF/mods.toml, NEOFORGE",
            "META-INF/neoforge.mods.toml plugin.yml build:velocity-api, NEOFORGE",
            // #599: the descriptor in a template folder the build expands, or written by a Gradle plugin.
            "templates:META-INF/neoforge.mods.toml plugin:net.neoforged.moddev, NEOFORGE",
            "templates:META-INF/neoforge.mods.toml, NEOFORGE",
            "templates:META-INF/mods.toml, FORGE",
            "templates:plugin.yml build:velocity-api, PAPER",
            "plugin:de.eldoria.plugin-yml.paper build:paper-api build:velocity-api block:paper, PAPER",
            "plugin:net.minecrell.plugin-yml.bukkit build:velocity-api, PAPER",
            "plugin:net.minecrell.plugin-yml.paper build:velocity-api, PAPER",
            "block:bukkit build:velocity-api, PAPER",
            "plugin:xyz.jpenilla.resource-factory-paper-convention block:paperPluginYaml build:velocity-api, PAPER",
            "plugin:xyz.jpenilla.resource-factory-velocity-convention build:paper-api, VELOCITY",
            "plugin:xyz.jpenilla.resource-factory-fabric-convention, FABRIC",
            "plugin:xyz.jpenilla.resource-factory-neoforge-convention, NEOFORGE",
            "plugin:de.eldoria.plugin-yml.bungee build:velocity-api, VELOCITY",
            "block:paperweight build:velocity-api, VELOCITY",
            "block:paper plugin:fabric-loom, PAPER",
            // The platform's own Gradle plugin, with no descriptor in the project.
            "plugin:fabric-loom build:velocity-api, FABRIC",
            "plugin:org.quiltmc.loom plugin:fabric-loom, QUILT",
            "plugin:net.neoforged.moddev.legacyforge, FORGE",
            "plugin:net.neoforged.gradle.userdev build:velocity-api, NEOFORGE",
            "plugin:net.minecraftforge.gradle, FORGE",
            "plugin:io.papermc.paperweight.userdev build:velocity-api, PAPER",
            "plugin:xyz.jpenilla.run-paper build:velocity-api, PAPER",
            "plugin:xyz.jpenilla.run-velocity build:velocity-api, VELOCITY",
            "plugin:xyz.jpenilla.run-velocity plugin:xyz.jpenilla.run-paper, PAPER",
            "plugin:xyz.jpenilla.run-velocity build:minestom, VELOCITY",
            "plugin:com.gradleup.shadow build:minestom, MINESTOM",
            // upstream
            "templates:META-INF/neoforge.mods.toml plugin:java-library plugin:net.neoforged.moddev, NEOFORGE",
            "META-INF/neoforge.mods.toml plugin:net.neoforged.gradle.userdev, NEOFORGE",
            "META-INF/mods.toml plugin:net.minecraftforge.gradle, FORGE",
            "fabric.mod.json plugin:net.fabricmc.fabric-loom, FABRIC",
            "quilt.mod.json plugin:org.quiltmc.loom, QUILT",
            "plugin:io.papermc.paperweight.userdev plugin:xyz.jpenilla.run-paper "
                    + "plugin:xyz.jpenilla.resource-factory-bukkit-convention block:bukkitPluginYaml, PAPER"})
    void detectionReadsWhatTheServerLoadsThenTheBuildPluginsThenTheDependencies(String tokens, Platform expected)
            throws Exception {
        StringBuilder plugins = new StringBuilder("plugins {\n");
        StringBuilder dependencies = new StringBuilder("dependencies {\n");
        StringBuilder blocks = new StringBuilder();
        for (String token : tokens == null ? new String[0] : tokens.split(" ")) {
            String value = token.substring(token.indexOf(':') + 1);
            switch (token.substring(0, Math.max(token.indexOf(':'), 0))) {
                case "plugin" -> plugins.append("    id(\"").append(value).append("\")\n");
                case "build" -> dependencies.append("    compileOnly(\"com.example:").append(value).append(":1.0\")\n");
                case "block" -> blocks.append(value).append(" {\n    main = \"x.Main\"\n}\n");
                case "templates" -> write(dir.resolve("src/main/templates").resolve(value));
                default -> write(dir.resolve("src/main/resources").resolve(value));
            }
        }
        Files.createDirectories(dir.resolve("src/main/resources"));
        Files.writeString(dir.resolve("build.gradle.kts"), plugins + "}\n\n" + dependencies + "}\n\n" + blocks);
        assertThat(Platform.detect(dir)).isEqualTo(expected);
    }

    /**
     * #599: every msc-templates template, as scaffolded, is detected as the platform it is made for, and runs on that
     * one only: project_scaffold judges a template by its platform, dev_cycle the project by its files (#642).
     */
    @Test
    void everyTemplateIsDetectedAsItsOwnPlatform() throws Exception {
        for (String id : Templates.ids()) {
            Path project = dir.resolve(id);
            Templates.scaffold(id, project, TemplateVariables.of("Detect Me"));
            Platform expected = Files.exists(project.resolve("build.gradle.kts"))
                    ? Platform.of(Templates.info(id).get("platform")) : null;
            assertThat(Platform.detect(project)).as(id).isEqualTo(expected);
            assertThat(Platform.runsOn(project)).as(id).isEqualTo(expected == null ? List.of() : List.of(expected));
        }
    }

    private static void write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }

    private static Path jar(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "jar");
    }
}
