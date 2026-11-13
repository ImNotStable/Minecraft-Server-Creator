package io.github.imnotstable.msc.bot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.junit.jupiter.api.Test;

class TestPlayerTest {
    private static ClientboundPlayerPositionPacket teleport(double x, double y, double z, PositionElement... relative) {
        return new ClientboundPlayerPositionPacket(2, x, y, z, 0, 0, 0, 0, 0, relative);
    }

    @Test
    void aTeleportEndsAWalkButACorrectionDoesNot() {
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5);
        Script.Step walk = Script.parse("goto 40.5,1,0.5");
        p.begin(walk);
        p.tick();
        p.receive(teleport(0.5, 0, 0, PositionElement.Y, PositionElement.Z)); // relative y and z: x=0.5, back
        p.tick();
        assertThat(walk.resultNow()).isNull();
        p.receive(teleport(100.5, 1, 0.5));
        assertThat(walk.resultNow()).isEqualTo("moved by server to 100.50,1.00,0.50 (100.0 blocks)");
        assertThat(p.body.x).isEqualTo(100.5);
    }

    @Test
    void tpReportsWhetherTheServerMovedThePlayer() {
        List<Packet> sent = new ArrayList<>();
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5, sent);
        Script.Step tp = Script.parse("tp 2.5,101,2.5");
        p.begin(tp);
        p.tick();
        assertThat(sent).contains(new ServerboundChatCommandPacket("tp MscTester 2.5 101.0 2.5"));
        p.receive(teleport(2.5, 101, 2.5));
        p.tick();
        assertThat(tp.resultNow()).isEqualTo("moved to 2.50,101.00,2.50");
        // A server with no /tp command (Minestom without the agent's) answers nothing: say so, not "sent".
        assertThat(Players.drive(p, Script.parse("tp 9,101,9"), 100))
                .isEqualTo("no teleport happened: the server did not move the player (it may have no /tp command)");
    }

    @Test
    void relativeTeleportsAddToThePosition() {
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5);
        p.receive(teleport(1, 5, -2, PositionElement.X, PositionElement.Y, PositionElement.Z));
        assertThat(new double[] {p.body.x, p.body.y, p.body.z}).containsExactly(1.5, 6, -1.5);
    }

    @Test
    void dyingStopsTheStepAndARespawnRevives() throws InterruptedException {
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5);
        Script.Step walk = Script.parse("goto 40.5,1,0.5");
        p.begin(walk);
        p.receive(new ClientboundSetHealthPacket(12, 20, 5));
        p.receive(new ClientboundSetHealthPacket(0, 20, 5));
        assertThat(walk.resultNow()).isEqualTo("stopped: died");
        assertThat(p.dead()).isTrue();
        p.receive(new ClientboundSetHealthPacket(20, 20, 5));
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:overworld"), false, false));
        assertThat(p.dead()).isFalse();
        assertThat(p.run(List.of(), "w")).startsWith("""
                  [damage] health 20.0 -> 12.0
                  [damage] health 12.0 -> 0.0
                  [healed] health 20.0
                  [respawned]
                MscTester at 0.50,1.00,0.50""");
    }

    @Test
    void eventsBetweenCallsKeepOnlyTheNewest() throws InterruptedException {
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5);
        for (int i = 0; i < 100_000; i++) {
            p.event("[actionbar] tick " + i);
        }
        assertThat(p.pendingEvents()).isEqualTo(TestPlayer.MAX_EVENTS);
        String out = p.run(List.of(), "w");
        assertThat(out).startsWith("  [actionbar] tick " + (100_000 - TestPlayer.MAX_EVENTS) + "\n")
                .contains("  (" + (100_000 - 40) + " more)\n");
    }

    static PlayerSpawnInfo spawn(String world) {
        return new PlayerSpawnInfo(0, Key.key(world), 0, GameMode.SURVIVAL, GameMode.SURVIVAL, false, false, null, 0, 63);
    }

    @Test
    void aPackNeedsPackMetaAtTheZipRoot() throws IOException {
        assertThat(TestPlayer.hasPackMeta(zip("pack.mcmeta"))).isTrue();
        assertThat(TestPlayer.hasPackMeta(zip("mypack/pack.mcmeta"))).isFalse();
        assertThat(TestPlayer.hasPackMeta("not a zip".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isFalse();
    }

    @Test
    void aPackBehindARedirectLoadsLikeOnAClient() throws Exception {
        byte[] pack = zip("pack.mcmeta");
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/release/pack.zip", x -> {
            x.getResponseHeaders().add("Location", "/cdn/pack.zip");
            x.sendResponseHeaders(302, -1);
            x.close();
        });
        server.createContext("/cdn/pack.zip", x -> {
            x.sendResponseHeaders(200, pack.length);
            x.getResponseBody().write(pack);
            x.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/release/pack.zip";
            TestPlayer.PackCheck check = TestPlayer.checkPack(url, null);
            assertThat(check.ok()).isTrue();
            assertThat(check.result()).startsWith("HTTP 200, " + pack.length + " bytes");
        } finally {
            server.stop(0);
        }
    }

    private static byte[] zip(String name) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry(name));
            out.write('{');
            out.closeEntry();
        }
        return bytes.toByteArray();
    }
}
