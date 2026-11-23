package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0057: one call installs a project by id, for the platform and Minecraft version, with what it requires. */
class AddonSourcesTest {
    private static final byte[] PERMS = "perms jar".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SHOP = "shop jar".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;
    private HttpServer http;
    private final Map<String, String> answers = new ConcurrentHashMap<>();
    private AddonSources sources;
    private String base;

    @BeforeEach
    void serve() throws IOException {
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", ex -> {
            String key = ex.getRequestURI().getPath();
            byte[] body = key.endsWith("perms.jar") ? PERMS : key.endsWith("shop.jar") ? SHOP
                    : answers.containsKey(key) ? answers.get(key).getBytes(StandardCharsets.UTF_8) : null;
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (var out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        http.start();
        base = "http://127.0.0.1:" + http.getAddress().getPort();
        sources = new AddonSources(new AddonSources.Urls(base + "/modrinth", base + "/hangar", base + "/spiget"),
                DownloadStore.NONE);
    }

    @AfterEach
    void stop() {
        http.stop(0);
    }

    @Test
    void installsTheReleaseForThisVersionAndItsRequiredDependency() throws Exception {
        answers.put("/modrinth/project/shop/version", "[" + version("2.0-beta", "beta", "26.3", "shop.jar", SHOP, "")
                + "," + version("1.9", "release", "26.3", "shop.jar", SHOP,
                "{\"project_id\":\"PERMSID\",\"dependency_type\":\"required\"},"
                        + "{\"project_id\":\"VAULT\",\"dependency_type\":\"optional\"}") + "]");
        answers.put("/modrinth/project/PERMSID/version", "[" + version("5.5", "release", "26.3", "perms.jar", PERMS, "") + "]");

        List<AddonSources.Installed> got = sources.install(List.of(AddonSources.Ref.of("shop")), Platform.PAPER, "26.3",
                tmp.resolve("plugins"));

        assertThat(got).extracting(i -> i.ref() + " " + i.version() + " " + i.jar().getFileName() + " " + i.note())
                .containsExactly("modrinth:shop 1.9 shop.jar null", "modrinth:PERMSID 5.5 perms.jar null");
        assertThat(tmp.resolve("plugins/perms.jar")).hasBinaryContent(PERMS);
    }

    @Test
    void aPluginForAnOlderVersionInstallsWithANoteAModDoesNot() throws Exception {
        answers.put("/modrinth/project/shop/version", "[" + version("1.0", "release", "26.1.2", "shop.jar", SHOP, "") + "]");

        assertThat(sources.install(List.of(AddonSources.Ref.of("modrinth:shop")), Platform.PAPER, "26.3",
                tmp.resolve("plugins")).get(0).note()).isEqualTo("made for Minecraft 26.1.2, not 26.3; check the startup log");
        assertThatThrownBy(() -> sources.install(List.of(AddonSources.Ref.of("shop")), Platform.FABRIC, "26.3",
                tmp.resolve("mods"))).isInstanceOf(ToolException.class)
                .hasMessageContaining("has no build for Minecraft 26.3 on Fabric; its newest is for 26.1.2");
    }

    @Test
    void aFileThatDoesNotMatchItsChecksumIsNotKept() throws Exception {
        answers.put("/modrinth/project/shop/version", "[" + version("1.0", "release", "26.3", "shop.jar", PERMS, "") + "]");
        assertThatThrownBy(() -> sources.install(List.of(AddonSources.Ref.of("shop")), Platform.PAPER, "26.3",
                tmp.resolve("plugins"))).hasMessageContaining("checksum mismatch");
        assertThat(tmp.resolve("plugins/shop.jar")).doesNotExist();
    }

    @Test
    void hangarNamesWhatItCannotFetch() throws Exception {
        answers.put("/hangar/projects/Shop/versions", """
                {"result":[{"name":"3.0","channel":{"name":"Release"},
                  "platformDependencies":{"PAPER":["26.3"]},
                  "downloads":{"PAPER":{"fileInfo":{"name":"shop.jar","sha256Hash":"%s"},"downloadUrl":"%s/files/shop.jar"}},
                  "pluginDependencies":{"PAPER":[{"name":"Vault","projectId":null,"required":true,"externalUrl":"https://x/vault"},
                                                 {"name":"Opt","projectId":7,"required":false}]}}]}
                """.formatted(hex("SHA-256", SHOP), base));
        answers.put("/hangar/projects/Ext/versions", """
                {"result":[{"name":"1.0","channel":{"name":"Release"},"platformDependencies":{"PAPER":["26.3"]},
                  "downloads":{"PAPER":{"fileInfo":null,"externalUrl":"https://github.com/x/releases","downloadUrl":null}}}]}
                """);

        AddonSources.Installed shop = sources.install(List.of(AddonSources.Ref.of("hangar:Shop")), Platform.PAPER,
                "26.3", tmp.resolve("plugins")).get(0);
        assertThat(shop.missing()).containsExactly("Vault (hangar:Shop requires it; Hangar links https://x/vault)");
        assertThatThrownBy(() -> sources.install(List.of(AddonSources.Ref.of("hangar:Ext")), Platform.PAPER, "26.3",
                tmp.resolve("plugins"))).hasMessageContaining("is hosted outside Hangar (https://github.com/x/releases)");
        assertThatThrownBy(() -> sources.install(List.of(AddonSources.Ref.of("hangar:Shop")), Platform.FABRIC, "26.3",
                tmp.resolve("mods"))).hasMessageContaining("hangar has Paper, Velocity and Waterfall plugins only");
    }

    @Test
    void searchListsEachSourcesMatchesInItsOwnOrder() throws Exception {
        answers.put("/modrinth/search", "{\"hits\":[{\"slug\":\"perms\",\"title\":\"Perms\",\"description\":\"Permissions.\","
                + "\"downloads\":3100000}]}");
        answers.put("/hangar/projects", "{\"result\":[{\"name\":\"Shop\",\"namespace\":{\"slug\":\"Shop\"},"
                + "\"description\":\"A shop\",\"stats\":{\"downloads\":1200}}]}");
        answers.put("/spiget/search/resources/perm", "[{\"id\":1,\"name\":\"Paid\",\"premium\":true},"
                + "{\"id\":2,\"name\":\"Ext\",\"tag\":\"t\",\"downloads\":5,\"external\":true}]");

        assertThat(sources.search("perm", Platform.PAPER)).containsExactly(
                "modrinth:perms  Perms: Permissions. (3.1M downloads)",
                "hangar:Shop  Shop: A shop (1k downloads)",
                "spigot:2  Ext: t (5 downloads) [hosted elsewhere: not installable]");
        assertThat(sources.search("perm", Platform.FABRIC)).containsExactly(
                "modrinth:perms  Perms: Permissions. (3.1M downloads)");
    }

    @Test
    void refsAreCheckedBeforeAnyRequest() {
        assertThat(AddonSources.Ref.of("luckperms")).hasToString("modrinth:luckperms");
        assertThatThrownBy(() -> AddonSources.Ref.of("curse:x")).hasMessageContaining("modrinth, hangar or spigot");
        assertThatThrownBy(() -> AddonSources.Ref.of("spigot:vault")).hasMessageContaining("resource number");
        assertThatThrownBy(() -> AddonSources.Ref.of("modrinth:../x")).hasMessageContaining("project slug");
        assertThat(AddonSources.fileName("../evil.jar", AddonSources.Ref.of("x"))).isEqualTo("x.jar");
    }

    private String version(String number, String type, String mc, String file, byte[] bytes, String deps) {
        return """
                {"version_number":"%s","version_type":"%s","game_versions":["%s"],
                 "files":[{"url":"%s/files/%s","filename":"%s","primary":true,"hashes":{"sha512":"%s"}}],
                 "dependencies":[%s]}""".formatted(number, type, mc, base, file, file, hex("SHA-512", bytes), deps);
    }

    private static String hex(String algorithm, byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
