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
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
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

/**
 * Loads the real plugin on a MockBukkit server: use this kind of test for events, commands and players. The plugin
 * reads {@link #CONFIG}, not your config.yml, so editing config.yml does not break these tests.
 */
class {{mainClass}}Test {
    /** Its time of day and bread, kept inside GameConfig.MAX_TIME and Kit.MAX_AMOUNT when you lower them. */
    static final int TIME = Math.min(6000, GameConfig.MAX_TIME);
    static final int BREAD = Math.min(8, Kit.MAX_AMOUNT);

    /** The game these tests play. A key it leaves out takes GameConfig's default. */
    static final String CONFIG = """
            game-mode: adventure
            difficulty: normal
            time: %d
            natural-spawns: false
            keep-inventory: true
            kit: [wooden_sword, 'bread:%d']
            stages:
              - {name: start, spawn: [0.5, -60, 0.5, 0, 0], goal: [-2, -61, 10, 2, -58, 12]}
              - {name: arena, spawn: [0.5, -60, 40.5, 0, 0]}
            """.formatted(TIME, BREAD);

    private ServerMock server;
    private WorldMock world;
    private {{mainClass}} plugin;

    @BeforeEach
    void setUp() throws InvalidConfigurationException {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(CONFIG);
        plugin = MockBukkit.loadWithConfig({{mainClass}}.class, config);
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
        assertThat(world.getTime()).isEqualTo(TIME);
        assertThat(world.getGameRuleValue(GameRules.ADVANCE_TIME)).isFalse();
        assertThat(world.getGameRuleValue(GameRules.KEEP_INVENTORY)).isTrue();
        assertThat(world.getGameRuleValue(GameRules.SPAWN_MOBS)).isFalse();
    }

    @Test
    void joiningGivesAdventureModeTheKitAndTheFirstStage() {
        PlayerMock steve = server.addPlayer("Steve");

        assertThat(steve.getGameMode()).isEqualTo(GameMode.ADVENTURE);
        assertThat(steve.getInventory().contains(Material.WOODEN_SWORD)).isTrue();
        assertThat(steve.getInventory().contains(Material.BREAD, BREAD)).isTrue();
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
    void aBadProgressFileDisablesThePluginInsteadOfCrashing() throws Exception {
        server.getPluginManager().disablePlugin(plugin);
        Path saved = plugin.getDataFolder().toPath().resolve("progress.yml");
        Files.createDirectories(saved.getParent());
        Files.writeString(saved, "stage:\n  not-a-uuid: arena\n");

        server.getPluginManager().enablePlugin(plugin);

        assertThat(plugin.isEnabled()).isFalse();
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
        // Only the lines it checks: status lines you add for your own state do not break it.
        assertThat(console("game status")).anySatisfy(line -> assertThat(line)
                .startsWith("Steve: stage 2/2 arena, at 0 -60 40, adventure"));
        assertThat(console("game reset Steve")).containsExactly("Steve was reset to stage 1");
        assertThat(plugin.game().stage(steve).name()).isEqualTo("start");
    }

    /** Words are split on runs of spaces, and tab completion offers stage names after them. */
    @Test
    void extraSpacesBetweenWordsAreIgnored() {
        server.addPlayer("Steve");
        assertThat(console("game  stage   arena  Steve")).containsExactly("Steve is on stage 2 arena");
        GameCommand command = new GameCommand(plugin.game(), server, "test");
        assertThat(command.suggestions(new String[] {"stage", "", ""})).containsExactly("start", "arena");
        assertThat(command.suggestions(new String[] {"stage", "", "ar"})).containsExactly("arena");
        assertThat(command.suggestions(new String[] {""})).contains("stage", "status");
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
