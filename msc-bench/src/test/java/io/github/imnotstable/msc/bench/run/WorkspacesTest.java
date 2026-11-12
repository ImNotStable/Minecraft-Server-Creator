package io.github.imnotstable.msc.bench.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacesTest {

    @TempDir
    Path dir;

    @Test
    void pluginJarsLoseTheirVersionSoCoreReplacesThem() {
        assertThat(Workspaces.pluginName(Path.of("msc-agent-paper-0.1.0-SNAPSHOT.jar"))).isEqualTo("msc-agent-paper.jar");
        assertThat(Workspaces.pluginName(Path.of("msc-agent-paper-1.2.jar"))).isEqualTo("msc-agent-paper.jar");
        assertThat(Workspaces.pluginName(Path.of("msc-agent-paper.jar"))).isEqualTo("msc-agent-paper.jar");
    }

    @Test
    void setPropertiesKeepsOtherLinesAndAddsMissingKeys() throws IOException {
        Path file = dir.resolve("server.properties");
        Files.writeString(file, "# comment\nlevel-type=minecraft\\:flat\nrcon.port=1\n");

        Workspaces.setProperties(file, Map.of("rcon.port", "25576", "enable-rcon", "true"));

        assertThat(Files.readAllLines(file))
                .containsExactly("# comment", "level-type=minecraft\\:flat", "rcon.port=25576", "enable-rcon=true");
    }

    @Test
    void picksTheNewestCachedPaperBuildAcrossFolders() throws IOException {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        Files.writeString(a.resolve("paper-26.3-99.jar"), "");
        Files.writeString(b.resolve("paper-26.3-248.jar"), "");
        Files.writeString(b.resolve("paper-26.2-500.jar"), "");
        Files.writeString(b.resolve("paper-26.3-300.jar.part"), "");

        assertThat(PaperJars.newestCached(List.of(dir.resolve("missing"), a, b), "26.3")).isEqualTo(b.resolve("paper-26.3-248.jar"));
        assertThat(PaperJars.newestCached(List.of(a), "27.1")).isNull();
    }

    @Test
    void serverFilesAreSavedOnceAndRestoredIntoNewWorkspaces() throws IOException {
        Path first = Files.createDirectories(dir.resolve("ws1/server/cache"));
        Files.writeString(first.resolve("mojang_26.3.jar"), "jar");
        Files.createDirectories(dir.resolve("ws1/server/libraries/a"));
        Files.writeString(dir.resolve("ws1/server/libraries/a/lib.jar"), "lib");
        Files.writeString(dir.resolve("ws1/server/server.properties"), "not shared");
        Path shared = dir.resolve("shared");
        Path second = Files.createDirectories(dir.resolve("ws2/server"));

        assertThat(ServerFiles.restore(shared, second)).isFalse();
        ServerFiles.save(dir.resolve("ws1/server"), shared);
        Files.writeString(first.resolve("mojang_26.3.jar"), "changed later");
        ServerFiles.save(dir.resolve("ws1/server"), shared);

        assertThat(ServerFiles.restore(shared, second)).isTrue();
        assertThat(second.resolve("cache/mojang_26.3.jar")).hasContent("jar");
        assertThat(second.resolve("libraries/a/lib.jar")).hasContent("lib");
        assertThat(second.resolve("server.properties")).doesNotExist();
    }

    @Test
    void theEulaChoiceComesOnlyFromTheMachinesAcceptance() throws IOException {
        Files.writeString(dir.resolve("bench.json"),
                "{\"server\":{\"acceptEula\":true,\"rconPort\":25576},\"corePort\":25590}");
        Path home = Files.createDirectories(dir.resolve("home"));
        BenchConfig without = BenchConfig.load(dir.resolve("bench.json"), home);

        Files.createDirectories(home.resolve(".msc"));
        Files.writeString(MachineEula.file(home), "{\"accepted\":true,\"eula\":\"https://aka.ms/MinecraftEULA\"}");
        boolean incomplete = MachineEula.accepted(home);
        Files.writeString(MachineEula.file(home), "{\"accepted\":true,\"eula\":\"https://aka.ms/MinecraftEULA\","
                + "\"acceptedBy\":\"owner\",\"acceptedAt\":\"2026-11-12T00:00:00Z\"}");
        BenchConfig with = BenchConfig.load(dir.resolve("bench.json"), home);

        assertThat(without.server().acceptEula()).isFalse();
        assertThat(incomplete).isFalse();
        assertThat(with.server().acceptEula()).isTrue();
        assertThat(with.server().rconPort()).isEqualTo(25576);
        assertThat(with.corePort()).isEqualTo(25590);
    }
}
