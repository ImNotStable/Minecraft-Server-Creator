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
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.border.ClientboundInitializeBorderPacket;
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
    void tellsTheServerItLoadedAfterEveryRespawn() {
        // Until the client says it loaded, a server ignores its moves and the player cannot die, even by /kill;
        // Paper restarts that wait on a world change too (#253).
        List<Packet> sent = new ArrayList<>();
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5, sent);
        assertThat(sent).containsOnlyOnce(ServerboundPlayerLoadedPacket.INSTANCE);
        sent.clear();
        p.receive(teleport(5.5, 1, 0.5));
        assertThat(sent).doesNotContain(ServerboundPlayerLoadedPacket.INSTANCE);
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:the_nether"), false, false));
        p.receive(teleport(0.5, 100, 0.5));
        assertThat(sent).containsOnlyOnce(ServerboundPlayerLoadedPacket.INSTANCE);
        sent.clear();
        p.receive(new ClientboundSetHealthPacket(0, 20, 5));
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:overworld"), false, false));
        p.receive(teleport(9.5, -60, -9.5));
        assertThat(sent).containsOnlyOnce(ServerboundPlayerLoadedPacket.INSTANCE);
    }

    @Test
    void eventsBetweenCallsKeepOnlyTheNewest()throws InterruptedException {
        TestPlayer p = Players.at(new FlatWorld(), null, 0.5, 1, 0.5);
        for (int i = 0; i < 100_000; i++) {
            p.event("[actionbar] tick " + i);
        }
        assertThat(p.pendingEvents()).isEqualTo(TestPlayer.MAX_EVENTS);
        String out = p.run(List.of(), "w");
        assertThat(out).startsWith("  [actionbar] tick " + (100_000 - TestPlayer.MAX_EVENTS) + "\n")
                .contains("  (" + (100_000 - 40) + " more)\n");
    }

    @Test
    void followsThePlayerIntoAnotherWorld() throws InterruptedException {
        FlatWorld blocks = new FlatWorld();
        List<String> planned = new ArrayList<>();
        TestPlayer p = Players.at(blocks, (world, from, to) -> {
            planned.add(world);
            return List.of(new int[] {0, 1, 0, 'w'}, new int[] {1, 1, 0, 'w'});
        }, 0.5, 1, 0.5);
        p.receive(login("minecraft:overworld"));
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:the_nether"), false, false));
        p.receive(teleport(0.5, 1, 0.5));
        assertThat(p.run(List.of(), "w")).startsWith("  [changed world] minecraft:the_nether\n")
                .doesNotContain("[respawned]");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("w_nether");
        assertThat(Players.drive(p, Script.parse("walk 1,1,0"), 100)).startsWith("reached");
        assertThat(planned).containsExactly("w_nether");
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:arena"), false, false));
        p.receive(teleport(1.5, 1, 0.5)); // the respawned player moves once the server places it
        p.useWorld("w");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("minecraft:arena");
    }

    @Test
    void asksTheLocatorWhichMinestomInstanceThePlayerIsIn() throws InterruptedException {
        // Minestom moves a player between two instances of one dimension with no Respawn and no world border reset:
        // only a position sync; and its instances have names of their own, whatever their dimension.
        FlatWorld blocks = new FlatWorld();
        String[] at = {"lobby"};
        List<String> asked = new ArrayList<>();
        TestPlayer p = TestPlayer.offline("MscTester", blocks, null, (player, pos) -> {
            asked.add(player + " " + pos.x() + "," + pos.y() + "," + pos.z());
            return at[0];
        }, new ArrayList<>());
        p.receive(login("minecraft:the_nether"));
        p.receive(teleport(0.5, 1, 0.5));
        p.useWorld("w");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("lobby");
        // A walk under way when the server moves the player stops, and the next action asks again.
        Script.Step walk = Script.parse("goto 40.5,1,0.5");
        p.begin(walk);
        p.tick();
        at[0] = "arena";
        p.receive(teleport(100.5, 1, 0.5));
        p.tick();
        assertThat(walk.resultNow()).startsWith("moved by server");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("arena");
        // A swap to an instance at the same spot sends no teleport at all: each call asks once.
        at[0] = "arena2";
        p.run(List.of(), "w");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("arena2");
        assertThat(asked).containsExactly("MscTester 0,1,0", "MscTester 100,1,0", "MscTester 100,1,0");
    }

    @Test
    void aTeleportKeepsThePlayerFallingWhileTheLocatorAnswers() throws InterruptedException {
        // A plugin teleports the player into the air during a wait: it falls at once, and the locator's answer (here
        // another Minestom instance) arrives without a new step.
        FlatWorld blocks = new FlatWorld();
        java.util.concurrent.CountDownLatch answer = new java.util.concurrent.CountDownLatch(1);
        List<String> asked = java.util.Collections.synchronizedList(new ArrayList<>());
        TestPlayer p = TestPlayer.offline("MscTester", blocks, null, (player, pos) -> {
            asked.add(pos.y() + "");
            if (asked.size() == 1) {
                return "lobby";
            }
            answer.await();
            return "arena";
        }, new ArrayList<>());
        p.receive(login("minecraft:overworld"));
        p.receive(teleport(0.5, 1, 0.5));
        p.useWorld("world");
        Script.Step wait = Script.parse("wait 100");
        p.begin(wait);
        p.tick();
        p.receive(teleport(0.5, 20, 0.5));
        for (int i = 0; i < 40; i++) {
            p.tick();
        }
        assertThat(p.body.y).isEqualTo(1);
        assertThat(p.body.onGround).isTrue();
        assertThat(p.view().world()).isEqualTo("lobby");
        answer.countDown();
        until(() -> p.view().world().equals("arena"));
        assertThat(wait.resultNow()).isNull();
        assertThat(asked).containsExactly("1", "20");
    }

    @Test
    void aRespawnWaitsForTheLocatorAloneThenFalls() throws InterruptedException {
        // A dimension change stops the simulation, as the old world is surely wrong, only until the locator answers.
        FlatWorld blocks = new FlatWorld();
        TestPlayer p = TestPlayer.offline("MscTester", blocks, null,
                (player, pos) -> pos.y() > 5 ? "nether_arena" : "world", new ArrayList<>());
        p.receive(login("minecraft:overworld"));
        p.receive(teleport(0.5, 1, 0.5));
        p.useWorld("world");
        Script.Step wait = Script.parse("wait 100");
        p.begin(wait);
        p.receive(new ClientboundRespawnPacket(spawn("minecraft:the_nether"), false, false));
        assertThat(p.view()).isNull();
        p.receive(teleport(0.5, 10, 0.5));
        until(() -> p.view() != null);
        assertThat(p.view().world()).isEqualTo("nether_arena");
        for (int i = 0; i < 40; i++) {
            p.tick();
        }
        assertThat(p.body.y).isEqualTo(1);
        assertThat(blocks.regions.getLast().world()).isEqualTo("nether_arena");
        assertThat(wait.resultNow()).isNull();
    }

    /** Wait up to five seconds for an answer from the locator's thread. */
    private static void until(java.util.function.BooleanSupplier done) throws InterruptedException {
        long end = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < end) {
            Thread.sleep(1);
        }
        assertThat(done.getAsBoolean()).isTrue();
    }

    @Test
    void aWorldBorderAloneIsNoWorldChange() {
        // Paper sends one for a per-player border, with the player staying where it is.
        FlatWorld blocks = new FlatWorld();
        List<String> asked = new ArrayList<>();
        TestPlayer p = TestPlayer.offline("MscTester", blocks, null, (player, pos) -> {
            asked.add(player);
            return "world";
        }, new ArrayList<>());
        p.receive(login("minecraft:overworld"));
        p.receive(teleport(0.5, 1, 0.5));
        p.useWorld("world");
        Script.Step walk = Script.parse("goto 40.5,1,0.5");
        p.begin(walk);
        p.tick();
        p.receive(new ClientboundInitializeBorderPacket(0, 0, 6e7, 6e7, 0, 29_999_984, 5, 15));
        p.tick();
        assertThat(walk.resultNow()).isNull();
        assertThat(p.view()).isNotNull();
        assertThat(asked).hasSize(1);
    }

    @Test
    void goesByTheDimensionWhenTheLocatorFindsThePlayerNowhere() {
        FlatWorld blocks = new FlatWorld();
        TestPlayer p = TestPlayer.offline("MscTester", blocks, null, (player, pos) -> null, new ArrayList<>());
        p.receive(login("minecraft:the_nether"));
        p.receive(teleport(0.5, 1, 0.5));
        p.useWorld("w");
        assertThat(Players.drive(p, Script.parse("jump"), 100)).startsWith("rose");
        assertThat(blocks.regions.getLast().world()).isEqualTo("w_nether");
    }

    static ClientboundLoginPacket login(String world) {
        return new ClientboundLoginPacket(7, false, new Key[] {Key.key(world)}, 20, 10, 10, false, true, false,
                spawn(world), false, false);
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
