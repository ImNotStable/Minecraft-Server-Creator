package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What the test player answers in each connection state, on each protocol version it speaks: what a server needs
 * from a client to keep treating it as a live player (#274). The same table holds for both versions.
 */
class ProtocolTableTest {
    static final String EXPECTED = """
            | state | server sends | bot answers |
            |---|---|---|
            | login | CustomQuery | CustomQueryAnswer |
            | login | CookieRequest | CookieResponse |
            | login | (21 ticks) | - |
            | configuration | CookieRequest | CookieResponse |
            | configuration | KeepAlive | KeepAlive |
            | configuration | Ping | Pong |
            | configuration | CodeOfConduct | AcceptCodeOfConduct |
            | configuration | SelectKnownPacks | SelectKnownPacks |
            | configuration | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | configuration | FinishConfiguration | FinishConfiguration |
            | configuration | (21 ticks) | - |
            | play, before the first position | CookieRequest | CookieResponse |
            | play, before the first position | KeepAlive | KeepAlive |
            | play, before the first position | Ping | Pong |
            | play, before the first position | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play, before the first position | PlayerPosition | AcceptTeleportation, PlayerLoaded |
            | play, before the first position | ChunkBatchFinished | ChunkBatchReceived |
            | play, before the first position | SetHealth 0 | - |
            | play, before the first position | Respawn | - |
            | play, before the first position | StartConfiguration | ConfigurationAcknowledged |
            | play, before the first position | (21 ticks) | - |
            | play | CookieRequest | CookieResponse |
            | play | KeepAlive | KeepAlive |
            | play | Ping | Pong |
            | play | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play | PlayerPosition | AcceptTeleportation |
            | play | ChunkBatchFinished | ChunkBatchReceived |
            | play | SetHealth 0 | - |
            | play | Respawn | - |
            | play | StartConfiguration | ConfigurationAcknowledged |
            | play | (21 ticks) | MovePlayerPosRot, ClientTickEnd |
            | play, dead | CookieRequest | CookieResponse |
            | play, dead | KeepAlive | KeepAlive |
            | play, dead | Ping | Pong |
            | play, dead | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play, dead | PlayerPosition | AcceptTeleportation |
            | play, dead | ChunkBatchFinished | ChunkBatchReceived |
            | play, dead | SetHealth 0 | - |
            | play, dead | Respawn | - |
            | play, dead | StartConfiguration | ConfigurationAcknowledged |
            | play, dead | (21 ticks) | ClientCommand(PERFORM_RESPAWN) |
            | play, respawned, before its position | CookieRequest | CookieResponse |
            | play, respawned, before its position | KeepAlive | KeepAlive |
            | play, respawned, before its position | Ping | Pong |
            | play, respawned, before its position | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play, respawned, before its position | PlayerPosition | AcceptTeleportation, PlayerLoaded |
            | play, respawned, before its position | ChunkBatchFinished | ChunkBatchReceived |
            | play, respawned, before its position | SetHealth 0 | - |
            | play, respawned, before its position | Respawn | - |
            | play, respawned, before its position | StartConfiguration | ConfigurationAcknowledged |
            | play, respawned, before its position | (21 ticks) | - |
            | re-configuration | CookieRequest | CookieResponse |
            | re-configuration | KeepAlive | KeepAlive |
            | re-configuration | Ping | Pong |
            | re-configuration | CodeOfConduct | AcceptCodeOfConduct |
            | re-configuration | SelectKnownPacks | SelectKnownPacks |
            | re-configuration | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | re-configuration | FinishConfiguration | FinishConfiguration |
            | re-configuration | (21 ticks) | - |
            | re-configuration while dead | CookieRequest | CookieResponse |
            | re-configuration while dead | KeepAlive | KeepAlive |
            | re-configuration while dead | Ping | Pong |
            | re-configuration while dead | CodeOfConduct | AcceptCodeOfConduct |
            | re-configuration while dead | SelectKnownPacks | SelectKnownPacks |
            | re-configuration while dead | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | re-configuration while dead | FinishConfiguration | FinishConfiguration |
            | re-configuration while dead | (21 ticks) | - |
            | play after re-configuration, before its position | CookieRequest | CookieResponse |
            | play after re-configuration, before its position | KeepAlive | KeepAlive |
            | play after re-configuration, before its position | Ping | Pong |
            | play after re-configuration, before its position | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play after re-configuration, before its position | PlayerPosition | AcceptTeleportation, PlayerLoaded |
            | play after re-configuration, before its position | ChunkBatchFinished | ChunkBatchReceived |
            | play after re-configuration, before its position | SetHealth 0 | - |
            | play after re-configuration, before its position | Respawn | - |
            | play after re-configuration, before its position | StartConfiguration | ConfigurationAcknowledged |
            | play after re-configuration, before its position | (21 ticks) | - |
            | play after re-configuration | CookieRequest | CookieResponse |
            | play after re-configuration | KeepAlive | KeepAlive |
            | play after re-configuration | Ping | Pong |
            | play after re-configuration | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play after re-configuration | PlayerPosition | AcceptTeleportation |
            | play after re-configuration | ChunkBatchFinished | ChunkBatchReceived |
            | play after re-configuration | SetHealth 0 | - |
            | play after re-configuration | Respawn | - |
            | play after re-configuration | StartConfiguration | ConfigurationAcknowledged |
            | play after re-configuration | (21 ticks) | MovePlayerPosRot, ClientTickEnd |
            | play after re-configuration while dead | CookieRequest | CookieResponse |
            | play after re-configuration while dead | KeepAlive | KeepAlive |
            | play after re-configuration while dead | Ping | Pong |
            | play after re-configuration while dead | ResourcePackPush | ResourcePack(ACCEPTED), ResourcePack(FAILED_DOWNLOAD) |
            | play after re-configuration while dead | PlayerPosition | AcceptTeleportation |
            | play after re-configuration while dead | ChunkBatchFinished | ChunkBatchReceived |
            | play after re-configuration while dead | SetHealth 0 | - |
            | play after re-configuration while dead | Respawn | - |
            | play after re-configuration while dead | StartConfiguration | ConfigurationAcknowledged |
            | play after re-configuration while dead | (21 ticks) | MovePlayerPosRot, ClientTickEnd |
            """;

    @Test
    void answersInEveryStateOnTheBuiltInProtocol() throws Exception {
        assertThat(Wire.table()).isEqualTo(EXPECTED);
    }

    @Test
    void answersInEveryStateOnThePreviousProtocol() throws Exception {
        Path protocols = Path.of(System.getProperty("msc.bot.protocols"));
        List<URL> urls = new ArrayList<>();
        urls.add(TestPlayer.class.getProtectionDomain().getCodeSource().getLocation());
        urls.add(Wire.class.getProtectionDomain().getCodeSource().getLocation());
        try (Stream<Path> files = Files.walk(protocols)) {
            for (Path jar : files.filter(p -> p.toString().endsWith(".jar")).sorted().toList()) {
                urls.add(jar.toUri().toURL());
            }
        }
        try (Bots.ChildFirst loader = new Bots.ChildFirst(urls.toArray(URL[]::new), Wire.class.getClassLoader())) {
            assertThat(loader.loadClass(TestPlayer.class.getName()).getMethod("minecraftVersion").invoke(null))
                    .isNotEqualTo(TestPlayer.minecraftVersion());
            var table = loader.loadClass(Wire.class.getName()).getDeclaredMethod("table");
            table.setAccessible(true);
            assertThat(table.invoke(null)).isEqualTo(EXPECTED);
        }
    }
}
