package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0057: the addon folder check names each jar that will not load, and why, in one line. */
class AddonCheckTest {
    @TempDir
    Path server;

    @Test
    void aGoodFolderHasNoProblems() throws IOException {
        jar("plugins/LuckPerms.jar", Map.of("plugin.yml", "name: LuckPerms\nversion: 5.5\n"));
        jar("plugins/Essentials.jar", Map.of("plugin.yml", "name: Essentials\nsoftdepend: [Vault]\ndepend: [LuckPerms]\n"));
        assertThat(AddonCheck.problems(server, Platform.PAPER, true)).isEmpty();
    }

    @Test
    void namesWrongPlatformMissingDependencyDuplicatesAndNonPlugins() throws IOException {
        jar("plugins/sodium.jar", Map.of("fabric.mod.json", "{\"id\":\"sodium\"}"));
        jar("plugins/Shop.jar", Map.of("plugin.yml", "name: Shop\ndepend:\n  - Vault\n  - 'LuckPerms'\n"));
        jar("plugins/LuckPerms-1.jar", Map.of("plugin.yml", "name: LuckPerms\n"));
        jar("plugins/LuckPerms-2.jar", Map.of("plugin.yml", "name: LuckPerms\n"));
        jar("plugins/lib.jar", Map.of("a.txt", "x"));
        Files.writeString(server.resolve("plugins/broken.jar"), "not a zip");

        assertThat(AddonCheck.problems(server, Platform.PAPER, false)).hasSize(3);
        assertThat(AddonCheck.problems(server, Platform.PAPER, true)).containsExactly(
                "? (plugins/broken.jar) is not a plugin or mod (no plugin.yml, fabric.mod.json, mods.toml, "
                        + "velocity-plugin.json or bungee.yml, or not a jar)",
                "? (plugins/lib.jar) is not a plugin or mod (no plugin.yml, fabric.mod.json, mods.toml, "
                        + "velocity-plugin.json or bungee.yml, or not a jar)",
                "sodium (plugins/sodium.jar) is a Fabric mod; Paper does not load it: delete it",
                "LuckPerms is in 2 jars: LuckPerms (plugins/LuckPerms-1.jar), LuckPerms (plugins/LuckPerms-2.jar); keep one",
                "Shop (plugins/Shop.jar) requires Vault, which is not installed");
    }

    @Test
    void paperPluginDependenciesAreRequiredUnlessTheySayOtherwise() {
        String yml = """
                name: Arena
                dependencies:
                  bootstrap:
                    Boot:
                      required: true
                  server:
                    LuckPerms:
                      load: BEFORE
                    Vault:
                      load: BEFORE
                      required: false
                    'WorldEdit':
                      required: true
                api-version: '26.3'
                """;
        assertThat(AddonCheck.paperRequired(yml)).containsExactly("LuckPerms", "WorldEdit");
    }

    @Test
    void readsModsTomlFabricJarInJarAndVelocityDescriptors() throws IOException {
        String toml = """
                [[mods]]
                modId="arena"
                version="1.0"
                [[dependencies.arena]]
                modId="neoforge"
                type="required"
                [[dependencies.arena]]
                modId="geckolib"
                type="required"
                [[dependencies.arena]]
                modId="jei"
                type="optional"
                """;
        jar("mods/arena.jar", Map.of("META-INF/neoforge.mods.toml", toml));
        assertThat(AddonCheck.problems(server, Platform.NEOFORGE, true))
                .containsExactly("arena 1.0 (mods/arena.jar) requires geckolib, which is not installed");
        Files.delete(server.resolve("mods/arena.jar"));

        byte[] inner = zip(Map.of("fabric.mod.json", "{\"id\":\"fabric-networking-api-v1\"}"));
        Map<String, Object> api = new LinkedHashMap<>();
        api.put("fabric.mod.json", "{\"id\":\"fabric-api\",\"jars\":[{\"file\":\"META-INF/jars/net.jar\"}]}");
        api.put("META-INF/jars/net.jar", inner);
        jar("mods/fabric-api.jar", api);
        jar("mods/mine.jar", Map.of("fabric.mod.json",
                "{\"id\":\"mine\",\"depends\":{\"fabricloader\":\">=0.17\",\"minecraft\":\"26.3\",\"fabric-networking-api-v1\":\"*\"}}"));
        assertThat(AddonCheck.problems(server, Platform.FABRIC, true)).isEmpty();
        assertThat(AddonCheck.problems(server, Platform.QUILT, true)).isEmpty();

        jar("plugins/proxy.jar", Map.of("velocity-plugin.json",
                "{\"id\":\"proxy\",\"dependencies\":[{\"id\":\"luckperms\",\"optional\":false},{\"id\":\"x\",\"optional\":true}]}"));
        assertThat(AddonCheck.problems(server, Platform.VELOCITY, true))
                .containsExactly("proxy (plugins/proxy.jar) requires luckperms, which is not installed");
    }

    @Test
    void minestomHasNoFolderToCheck() throws IOException {
        assertThat(AddonCheck.problems(server, Platform.MINESTOM, true)).isEmpty();
    }

    private void jar(String path, Map<String, ?> entries) throws IOException {
        Path jar = server.resolve(path);
        Files.createDirectories(jar.getParent());
        Files.write(jar, zip(entries));
    }

    static byte[] zip(Map<String, ?> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, ?> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                OutputStream out = zip;
                out.write(e.getValue() instanceof byte[] b ? b : e.getValue().toString().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
