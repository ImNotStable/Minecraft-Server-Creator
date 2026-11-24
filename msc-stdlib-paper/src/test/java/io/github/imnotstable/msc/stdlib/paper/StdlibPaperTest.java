package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.db.Store;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class StdlibPaperTest {
    ServerMock server;
    Plugin plugin;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        Stdlib.init(plugin);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    InventoryClickEvent click(PlayerMock p, ClickType type, int slot) {
        InventoryClickEvent e = new InventoryClickEvent(p.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                type, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(e);
        return e;
    }

    @Test
    void aGuiRunsHandlersAndKeepsItsItems() {
        PlayerMock p = server.addPlayer();
        List<String> clicks = new ArrayList<>();
        Gui gui = Gui.chest(3, Component.text("Shop"))
                .set(13, ItemStack.of(Material.DIAMOND), c -> clicks.add(c.type() + "@" + c.slot()))
                .editable(0).fill(ItemBuilder.pane(Material.GRAY_STAINED_GLASS_PANE)).open(p);
        assertThat(p.getOpenInventory().getTopInventory()).isSameAs(gui.getInventory());
        assertThat(click(p, ClickType.RIGHT, 13).isCancelled()).isTrue();
        assertThat(click(p, ClickType.LEFT, 5).isCancelled()).isTrue();
        assertThat(clicks).containsExactly("RIGHT@13");
    }

    @Test
    void aPagedGuiTurnsPages() {
        PlayerMock p = server.addPlayer();
        List<Integer> picked = new ArrayList<>();
        Gui gui = Gui.paged(2, Component.text("Numbers"), IntStream.range(0, 20).boxed().toList(),
                i -> ItemStack.of(Material.STONE, i + 1), (i, c) -> picked.add(i),
                new Gui.Nav(ItemStack.of(Material.ARROW), ItemStack.of(Material.ARROW),
                        (n, of) -> ItemBuilder.of(Material.PAPER).name("Page " + n + "/" + of).build())).open(p);
        assertThat(gui.getInventory().getItem(9)).isNull();          // no back arrow on page 1
        click(p, ClickType.LEFT, 17); // next
        click(p, ClickType.LEFT, 0);
        assertThat(picked).containsExactly(9);
        assertThat(Text.plain(gui.getInventory().getItem(13).getItemMeta().customName())).isEqualTo("Page 2/3");
    }

    @Test
    void itemsCarryNameLoreAndData() {
        NamespacedKey key = new NamespacedKey(plugin, "id");
        ItemStack item = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 3)
                .glow().data(key, PersistentDataType.STRING, "slayer").amount(1).build();
        assertThat(Text.plain(item.getItemMeta().customName())).isEqualTo("Slayer");
        assertThat(Text.plain(item.getItemMeta().lore().getFirst())).isEqualTo("Kills: 3");
        assertThat(ItemBuilder.read(item, key, PersistentDataType.STRING)).isEqualTo("slayer");
        assertThat(ItemBuilder.read(ItemStack.of(Material.STONE), key, PersistentDataType.STRING)).isNull();
    }

    @Test
    void placeholdersAreNotParsedAsTags() {
        assertThat(Text.plain(Text.mm("<green>Hi <name>", "name", "<red>evil"))).isEqualTo("Hi <red>evil");
        assertThat(Text.plain(Text.mm("<a> and <b>", "a", Component.text("x"), "b", 2))).isEqualTo("x and 2");
    }

    @Test
    void aSidebarShowsItsLines() {
        PlayerMock p = server.addPlayer();
        Sidebar s = new Sidebar(Component.text("Game"));
        s.lines(List.of(Component.text("a"), Component.text("b"), Component.text("c")));
        s.lines(List.of(Component.text("only")));
        s.show(p);
        assertThat(p.getScoreboard()).isSameAs(s.scoreboard());
        assertThat(s.scoreboard().getEntries()).hasSize(1);
        s.hide(p);
        assertThat(p.getScoreboard()).isNotSameAs(s.scoreboard());
    }

    @Test
    void playerDataIsSavedOnQuit() {
        record Stats(int kills) {}
        Store<Stats> store = Store.memory();
        PlayerData<Stats> data = PlayerData.create(store, () -> new Stats(0));
        PlayerMock p = server.addPlayer();
        data.update(p, s -> new Stats(s.kills() + 2));
        assertThat(data.get(p)).isEqualTo(new Stats(2));
        p.disconnect();
        server.getScheduler().waitAsyncTasksFinished();
        assertThat(store.get(p.getUniqueId().toString())).contains(new Stats(2));
    }

    @Test
    void schedulerCountsDownAndPlaysFrames() {
        List<Integer> ticks = new ArrayList<>();
        boolean[] done = {false};
        Stdlib.scheduler().countdown(3, ticks::add, () -> done[0] = true);
        List<Integer> frames = new ArrayList<>();
        ParticleEffect.of(org.bukkit.Particle.FLAME).play(Stdlib.scheduler(), new org.bukkit.Location(server.addSimpleWorld("w"), 0, 0, 0),
                1, 4, f -> {
                    frames.add(f);
                    return List.of();
                });
        server.getScheduler().performTicks(100);
        assertThat(ticks).containsExactly(3, 2, 1);
        assertThat(done[0]).isTrue();
        assertThat(frames).containsExactly(0, 1, 2, 3);
    }

    @Test
    void databaseConfigComesFromYaml() throws Exception {
        YamlConfiguration y = new YamlConfiguration();
        y.loadFromString("database:\n  type: postgresql\n  host: db\n  user: mc\n  properties:\n    ssl: false\n");
        var c = PaperDatabase.config(y.getConfigurationSection("database"), Path.of("plugins/x"));
        assertThat(c.type().url(c)).isEqualTo("jdbc:postgresql://db:5432/minecraft");
        assertThat(c.properties()).containsEntry("ssl", "false");
        assertThat(PaperDatabase.config(null, Path.of("p")).file()).isEqualTo(Path.of("p/data.db"));
    }
}
