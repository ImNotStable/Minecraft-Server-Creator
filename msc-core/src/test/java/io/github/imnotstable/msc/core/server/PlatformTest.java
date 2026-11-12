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
                .hasMessageContaining("paper, purpur, fabric, quilt, neoforge, forge, minestom");
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
            assertThat(p.needsEula()).as(p.id()).isEqualTo(p != Platform.MINESTOM);
        }
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

    private static Path jar(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        return Files.writeString(path, "jar");
    }
}
