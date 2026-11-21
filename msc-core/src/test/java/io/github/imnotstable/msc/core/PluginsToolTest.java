package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.imnotstable.msc.core.fixture.FakeServerMain;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0057: the plugins tool lists and checks the folder without the network, and server_start says what won't load. */
class PluginsToolTest {
    @TempDir
    Path workspace;
    private CoreHarness h;
    private Path plugins;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace, c -> c.withServerCommand(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), FakeServerMain.class.getName())));
        plugins = Files.createDirectories(h.core.config().serverDir().resolve("plugins"));
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    @Test
    void listsTheJarsAndTheirProblems() throws Exception {
        assertThat(h.ok("plugins")).isEqualTo("no jars in plugins/\ncheck: ok");
        jar("Shop.jar", "plugin.yml", "name: Shop\nversion: 1.0\ndepend: [Vault]\n");
        jar("sodium.jar", "fabric.mod.json", "{\"id\":\"sodium\"}");

        assertThat(h.ok("plugins")).isEqualTo("""
                Shop 1.0 (plugins/Shop.jar)
                sodium (plugins/sodium.jar)
                problems:
                - sodium (plugins/sodium.jar) is a Fabric mod; Paper does not load it: delete it
                - Shop 1.0 (plugins/Shop.jar) requires Vault, which is not installed""");
        assertThat(h.ok("server_start", "timeoutSeconds", 30, "acceptEula", true))
                .contains("addon problem: Shop 1.0 (plugins/Shop.jar) requires Vault, which is not installed\n");
    }

    @Test
    void refusesWhatItCannotDoBeforeAnyRequest() {
        assertThat(h.error("plugins", "search", "perms", "install", List.of("luckperms"))).contains("not both");
        assertThat(h.error("plugins", "install", List.of("curseforge:x"))).contains("modrinth, hangar or spigot");
        assertThat(h.error("plugins", "install", List.of("spigot:vault"))).contains("resource number");
        assertThat(h.error("plugins", "search", " ")).contains("search needs words");
    }

    private void jar(String name, String entry, String text) throws IOException {
        try (OutputStream file = Files.newOutputStream(plugins.resolve(name));
             ZipOutputStream zip = new ZipOutputStream(file)) {
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }
}
