package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.CoreConfig;
import io.github.imnotstable.msc.core.api.ApiIndex;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        Path events = jar(files.resolve("net.fabricmc.fabric-api/fabric-lifecycle-events-v1/2.0/abc/fabric-lifecycle-events-v1-2.0.jar"));
        Path loader = jar(files.resolve("net.fabricmc/fabric-loader/0.20.1/abc/fabric-loader-0.20.1.jar"));
        Path minecraft = jar(dir.resolve("caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-server-deobf/26.3/minecraft-server-deobf-26.3.jar"));
        assertThat(ApiIndex.findJars(dir, "paper")).containsExactly(paper, adventure);
        assertThat(ApiIndex.findJars(dir, "minestom")).containsExactly(minestom, adventure);
        assertThat(ApiIndex.findJars(dir, "fabric")).containsExactly(minecraft, events, loader);
        assertThat(ApiIndex.findJars(dir.resolve("empty"), "fabric")).isEmpty();
    }

    /**
     * #593: what {@link Platform#detect} makes of a project's files ({@code src/main/resources/<file>}, or a build file
     * that names a word). A resource file the server loads beats any word in a build file, and a game server's
     * plugin file beats Velocity's, so a plugin built for Paper and Velocity is Paper.
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
            "META-INF/neoforge.mods.toml plugin.yml build:velocity-api, NEOFORGE"})
    void detectionReadsTheFilesTheServerLoadsBeforeTheBuildFiles(String files, Platform expected) throws Exception {
        StringBuilder build = new StringBuilder("dependencies {\n");
        for (String file : files == null ? new String[0] : files.split(" ")) {
            if (file.startsWith("build:")) {
                build.append("    compileOnly(\"com.example:").append(file.substring(6)).append(":1.0\")\n");
            } else {
                Path resource = dir.resolve("src/main/resources").resolve(file);
                Files.createDirectories(resource.getParent());
                Files.writeString(resource, "");
            }
        }
        Files.writeString(dir.resolve("build.gradle.kts"), build.append("}\n"));
        assertThat(Platform.detect(dir)).isEqualTo(expected);
    }

    private static Path jar(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "jar");
    }
}
