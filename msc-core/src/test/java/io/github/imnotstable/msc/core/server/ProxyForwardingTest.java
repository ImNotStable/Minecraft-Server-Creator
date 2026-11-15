package io.github.imnotstable.msc.core.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0045: the machine's forwarding secret, the proxy's config and the backend's {@code paper-global.yml} keys. */
class ProxyForwardingTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    /** The {@code proxies} section as Paper 26.3 writes it, between two other sections. */
    private static final String PAPER_WRITES = """
            _version: 31
            messages:
              kick:
                connection-throttle: Connection throttled! Please wait before reconnecting.
            proxies:
              bungee-cord:
                online-mode: true
              proxy-protocol: false
              velocity:
                enabled: false
                online-mode: true
                secret: ''
            scoreboards:
              save-empty-scoreboard-teams: true
            """;

    @TempDir
    Path dir;

    @Test
    void theSecretIsMadeOnceAndKept() throws Exception {
        Path file = dir.resolve("home/.msc/velocity.secret");
        String made = ProxyForwarding.secret(file);
        assertThat(made).matches("[0-9a-f]{32}");
        assertThat(ProxyForwarding.secret(file)).isEqualTo(made);
        Files.writeString(file, "not a secret\n");
        assertThat(ProxyForwarding.secret(file)).matches("[0-9a-f]{32}").isNotEqualTo(made);
    }

    @Test
    void paperWrittenConfigGetsOnlyTheVelocityKeysChanged() {
        String on = ProxyForwarding.withVelocity(PAPER_WRITES, SECRET);
        assertThat(on).isEqualTo(PAPER_WRITES.replace("""
                    enabled: false
                    online-mode: true
                    secret: ''
                """, """
                    enabled: true
                    online-mode: false
                    secret: '%s'
                """.formatted(SECRET)));
        // bungee-cord's online-mode, at another depth, is not the one changed.
        assertThat(on).contains("  bungee-cord:\n    online-mode: true\n");
        String off = ProxyForwarding.withVelocity(on, null);
        assertThat(off).contains("    enabled: false\n", "    secret: '" + SECRET + "'\n");
        assertThat(ProxyForwarding.withVelocity(off, SECRET)).isEqualTo(on);
    }

    @Test
    void missingSectionsAndKeysAreAdded() {
        assertThat(ProxyForwarding.withVelocity("", SECRET)).isEqualTo("""
                proxies:
                  velocity:
                    enabled: true
                    online-mode: false
                    secret: '%s'
                """.formatted(SECRET));
        assertThat(ProxyForwarding.withVelocity("proxies:\n  proxy-protocol: false\nother: 1\n", SECRET))
                .isEqualTo("proxies:\n  proxy-protocol: false\n  velocity:\n    enabled: true\n    online-mode: false\n"
                        + "    secret: '" + SECRET + "'\nother: 1\n");
        assertThat(ProxyForwarding.withVelocity("other: 1\n", null)).isEqualTo("other: 1\n");
    }

    @Test
    void backendWritesTheFileOnlyWhenItChanges() throws Exception {
        ProxyForwarding.backend(dir, null);
        assertThat(dir.resolve(ProxyForwarding.PAPER_GLOBAL)).doesNotExist();
        ProxyForwarding.backend(dir, SECRET);
        assertThat(dir.resolve(ProxyForwarding.PAPER_GLOBAL)).content().contains("secret: '" + SECRET + "'");
    }

    @Test
    void theProxyGetsItsConfigOnceAndTheSecretEveryTime() throws Exception {
        assertThat(ProxyForwarding.proxy(dir, SECRET)).contains("wrote velocity.toml");
        assertThat(dir.resolve("velocity.toml")).content().contains("bind = \"127.0.0.1:25577\"",
                "online-mode = false", "player-info-forwarding-mode = \"MODERN\"", "lobby = \"127.0.0.1:25565\"",
                "forwarding-secret-file = \"forwarding.secret\"");
        assertThat(dir.resolve("forwarding.secret")).hasContent(SECRET);
        Files.writeString(dir.resolve("velocity.toml"), "# mine\n");
        assertThat(ProxyForwarding.proxy(dir, "ff".repeat(16))).contains("velocity.toml kept as it is");
        assertThat(dir.resolve("velocity.toml")).hasContent("# mine\n");
        assertThat(dir.resolve("forwarding.secret")).hasContent("ff".repeat(16));
    }
}
