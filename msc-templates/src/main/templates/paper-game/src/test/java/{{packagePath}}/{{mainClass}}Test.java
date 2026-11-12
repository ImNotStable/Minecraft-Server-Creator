package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

/** Loads the real plugin on a MockBukkit server: use this kind of test for events, commands and players. */
class {{mainClass}}Test {
    private ServerMock server;
    private WorldMock world;
    private {{mainClass}} plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load({{mainClass}}.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /** Every message the sender has received so far, as plain text. */
    private static List<String> messages(PlayerMock player) {
        List<String> out = new ArrayList<>();
        for (Component c = player.nextComponentMessage(); c != null; c = player.nextComponentMessage()) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    private List<String> console(String command) {
        var console = server.getConsoleSender();
        server.dispatchCommand(console, command);
        List<String> out = new ArrayList<>();
        for (Component c = console.nextComponentMessage(); c != null; c = console.nextComponentMessage()) {
            out.add(PlainTextComponentSerializer.plainText().serialize(c));
        }
        return out;
    }

    private Location at(double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    @Test
    void appliesTheWorldRules() {
        assertThat(world.getTime()).isEqualTo(6000);
        assertThat(world.getGameRuleValue(GameRules.ADVANCE_TIME)).isFalse();
        assertThat(world.getGameRuleValue(GameRules.KEEP_INVENTORY)).isTrue();
        assertThat(world.getGameRuleValue(GameRules.SPAWN_MOBS)).isFalse();
    }

    @Test
    void joiningGivesAdventureModeTheKitAndTheFirstStage() {
        PlayerMock steve = server.addPlayer("Steve");

        assertThat(steve.getGameMode()).isEqualTo(GameMode.ADVENTURE);
        assertThat(steve.getInventory().contains(Material.WOODEN_SWORD)).isTrue();
        assertThat(steve.getInventory().contains(Material.BREAD, 8)).isTrue();
        assertThat(steve.getLocation().getBlockZ()).isZero();
        assertThat(plugin.game().stage(steve).name()).isEqualTo("start");
    }

    @Test
    void steppingIntoTheGoalAdvancesAndSavesProgress() throws Exception {
        PlayerMock steve = server.addPlayer("Steve");
        messages(steve);

        // A real move, as the client sends it. Teleports do not count as moves.
        server.getPluginManager().callEvent(new PlayerMoveEvent(steve, steve.getLocation(), at(0.5, -60, 11.5)));

        assertThat(plugin.game().stage(steve).name()).isEqualTo("arena");
        assertThat(steve.getLocation().getBlockZ()).isEqualTo(40);
        assertThat(messages(steve)).contains("Stage 2/2: arena");
        server.getScheduler().waitAsyncTasksFinished();
        Path saved = plugin.getDataFolder().toPath().resolve("progress.yml");
        assertThat(Files.readString(saved)).contains(steve.getUniqueId() + ": arena");
    }

    @Test
    void deathKeepsTheInventoryAndRespawnsAtTheCurrentStage() {
        PlayerMock steve = server.addPlayer("Steve");
        plugin.game().moveTo(steve, 1, "test");
        List<PlayerDeathEvent> deaths = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onDeath(PlayerDeathEvent event) {
                deaths.add(event);
            }
        }, plugin);

        steve.setHealth(0);
        steve.respawn();

        assertThat(deaths).singleElement().satisfies(e -> {
            assertThat(e.getKeepInventory()).isTrue();
            assertThat(e.getDrops()).isEmpty();
        });
        assertThat(steve.getLocation().getBlockZ()).isEqualTo(40);
    }

    @Test
    void naturalSpawnsAreCancelledButPluginSpawnsAreNot() {
        LivingEntity zombie = (LivingEntity) world.spawnEntity(at(0, -60, 0), EntityType.ZOMBIE);
        CreatureSpawnEvent natural = new CreatureSpawnEvent(zombie, SpawnReason.NATURAL);
        CreatureSpawnEvent custom = new CreatureSpawnEvent(zombie, SpawnReason.CUSTOM);
        server.getPluginManager().callEvent(natural);
        server.getPluginManager().callEvent(custom);

        assertThat(natural.isCancelled()).isTrue();
        assertThat(custom.isCancelled()).isFalse();
    }

    @Test
    void consoleCanReadStatusAndJumpPlayersToAStage() {
        PlayerMock steve = server.addPlayer("Steve");

        assertThat(console("game stage arena Steve")).containsExactly("Steve is on stage 2 arena");
        assertThat(steve.getLocation().getBlockZ()).isEqualTo(40);
        assertThat(console("game status")).hasSize(2).last().asString()
                .startsWith("Steve: stage 2/2 arena, at 0 -60 40, adventure");
        assertThat(console("game reset Steve")).containsExactly("Steve was reset to stage 1");
        assertThat(plugin.game().stage(steve).name()).isEqualTo("start");
    }

    @Test
    void badArgumentsGetAUsefulReply() {
        server.addPlayer("Steve");
        assertThat(console("game")).containsExactly(GameCommand.USAGE);
        assertThat(console("game stage moon Steve")).containsExactly("no stage moon. Stages: [start, arena]");
        assertThat(console("game next")).containsExactly("name a player: next <player>");
        assertThat(console("game kit Alex")).containsExactly("no online player Alex");
    }

    @Test
    void playersWithoutThePermissionCannotUseTheCommand() {
        PlayerMock alex = server.addPlayer("Alex");
        messages(alex);
        alex.setOp(false);

        alex.performCommand("game next");

        assertThat(plugin.game().stage(alex).name()).isEqualTo("start");
        CommandSender admin = server.addPlayer("Admin");
        admin.setOp(true);
        ((PlayerMock) admin).performCommand("game next");
        assertThat(plugin.game().stage((PlayerMock) admin).name()).isEqualTo("arena");
    }
}
