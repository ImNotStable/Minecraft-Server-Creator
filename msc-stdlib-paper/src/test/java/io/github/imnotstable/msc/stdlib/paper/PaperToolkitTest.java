package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.ui.Bar;
import io.github.imnotstable.msc.stdlib.ui.Feedback;
import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PaperToolkitTest {
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

    static List<BossBar> bars(PlayerMock p) {
        List<BossBar> out = new ArrayList<>();
        p.activeBossBars().forEach(out::add);
        return out;
    }

    static String next(PlayerMock p) {
        return Text.plain(p.nextComponentMessage());
    }

    InventoryClickEvent click(PlayerMock p, int slot) {
        InventoryClickEvent e = new InventoryClickEvent(p.getOpenInventory(), InventoryType.SlotType.CONTAINER, slot,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(e);
        return e;
    }

    @Test
    void feedbackSendsTheLinesAsWritten() {
        PlayerMock p = server.addPlayer();
        Feedback.message(p, "<green>Bought <item>", "item", "a sword");
        assertThat(next(p)).isEqualTo("Bought a sword");
        Feedback.title(p, "<gold>Round <n>", "<gray>Go", "n", 2);
        Feedback.actionBar(p, "<yellow>Coins: <c>", "c", 5);
    }

    @Test
    void barsShowCountDownAndHide() {
        PlayerMock p = server.addPlayer();
        boolean[] done = {false};
        Bar timer = Bar.timer(Stdlib.scheduler(), List.of(p), "<yellow><time>", Duration.ofSeconds(2), BossBar.Color.YELLOW, () -> done[0] = true);
        assertThat(bars(p)).contains(timer.bossBar());
        server.getScheduler().performTicks(20);
        assertThat(timer.bossBar().progress()).isBetween(0.45f, 0.55f);
        server.getScheduler().performTicks(25);
        assertThat(done[0]).isTrue();
        assertThat(bars(p)).doesNotContain(timer.bossBar());
        Bar shown = Bar.create("<green>Saved", BossBar.Color.GREEN).show(p).hideAfter(Stdlib.scheduler(), Duration.ofSeconds(1));
        server.getScheduler().performTicks(21);
        assertThat(bars(p)).doesNotContain(shown.bossBar());
    }

    @Test
    void promptsTakeTheNextAnswerOrCancel() {
        PlayerMock p = server.addPlayer();
        List<String> got = new ArrayList<>();
        Prompt.ask(p, "Name?", Duration.ofSeconds(30), got::add, () -> got.add("cancelled"));
        assertThat(Prompt.waiting(p)).isTrue();
        assertThat(Prompt.answer(p, " Castle ")).isTrue();
        server.getScheduler().performOneTick();
        Prompt.ask(p, "Again?", Duration.ofSeconds(1), got::add, () -> got.add("timeout"));
        server.getScheduler().performTicks(21);
        assertThat(got).containsExactly("Castle", "timeout");
        assertThat(Prompt.answer(p, "late")).isFalse();
    }

    @Test
    void customItemsKeepTheirIdAndRunHandlers() {
        PlayerMock p = server.addPlayer();
        List<String> used = new ArrayList<>();
        CustomItems.register("wand", () -> ItemBuilder.of(Material.BLAZE_ROD).name("<yellow>Wand").build())
                .onUse(e -> used.add("use")).cooldown(Duration.ofSeconds(5)).undroppable();
        ItemStack wand = CustomItems.create("wand");
        assertThat(CustomItems.is(wand, "wand")).isTrue();
        assertThat(CustomItems.id(ItemStack.of(Material.STICK))).isEmpty();
        for (int i = 0; i < 2; i++) {
            PlayerInteractEvent e = new PlayerInteractEvent(p, Action.RIGHT_CLICK_AIR, wand, null,
                    org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND);
            server.getPluginManager().callEvent(e);
            assertThat(e.useItemInHand()).isEqualTo(org.bukkit.event.Event.Result.DENY);
        }
        assertThat(used).containsExactly("use"); // the second is inside the cooldown
    }

    @Test
    void regionsRunEnterAndLeaveAndProtect() {
        World world = server.addSimpleWorld("world");
        PlayerMock p = server.addPlayer();
        List<String> seen = new ArrayList<>();
        Regions.define("spawn", "world", Cuboid.of(new Vec3(0, 0, 0), new Vec3(10, 100, 10))).noBuild()
                .onEnter(pl -> seen.add("enter")).onLeave(pl -> seen.add("leave"));
        Regions.update(p, new Location(world, 5, 64, 5));
        Regions.update(p, new Location(world, 6, 64, 5));
        Regions.update(p, new Location(world, 50, 64, 5));
        assertThat(seen).containsExactly("enter", "leave");
        Block block = world.getBlockAt(3, 64, 3);
        BlockBreakEvent e = new BlockBreakEvent(block, p);
        server.getPluginManager().callEvent(e);
        assertThat(e.isCancelled()).isTrue();
        assertThat(Regions.at(new Location(world, 1, 1, 1))).extracting(Regions.Area::name).containsExactly("spawn");
    }

    @Test
    void menusHaveLayoutsThemesConfirmAndLiveUpdates() {
        PlayerMock p = server.addPlayer();
        List<String> clicks = new ArrayList<>();
        Gui gui = Gui.chest(3, Component.text("Kits")).layout(
                        "#########",
                        "# a   b #",
                        "#########")
                .bind('a', ItemStack.of(Material.IRON_SWORD), c -> clicks.add("a"))
                .bind('b', ItemStack.of(Material.BOW), c -> clicks.add("b"))
                .theme(new Gui.Theme(ItemBuilder.pane(Material.BLACK_STAINED_GLASS_PANE),
                        ItemBuilder.pane(Material.GRAY_STAINED_GLASS_PANE), null))
                .refreshEvery(5, g -> g.update(13, ItemStack.of(Material.CLOCK, server.getCurrentTick() % 60 + 1)))
                .open(p);
        assertThat(gui.slots('#')).hasSize(20);
        assertThat(gui.getInventory().getItem(0).getType()).isEqualTo(Material.BLACK_STAINED_GLASS_PANE);
        assertThat(gui.getInventory().getItem(12).getType()).isEqualTo(Material.GRAY_STAINED_GLASS_PANE);
        click(p, 11);
        click(p, 15);
        assertThat(clicks).containsExactly("a", "b");
        server.getScheduler().performTicks(6);
        assertThat(gui.getInventory().getItem(13).getType()).isEqualTo(Material.CLOCK);
        assertThat(gui.viewers()).containsExactly(p);
    }

    @Test
    void commandsRouteSubcommandsCheckArgsAndComplete() {
        PlayerMock p = server.addPlayer("Alex");
        server.addPlayer("Bo");
        List<String> ran = new ArrayList<>();
        Cmd party = Cmd.create("party").playerOnly()
                .sub(Cmd.create("invite").usage("<player>").tab(Cmd.Ctx::onlinePlayerNames)
                        .executes(c -> ran.add("invite " + c.argPlayer(0).getName())))
                .sub(Cmd.create("pay").executes(c -> ran.add("pay " + c.argInt(0))));
        party.run(p, new String[]{"invite", "Bo"}, "/party");
        party.run(p, new String[]{"pay", "x"}, "/party");
        party.run(p, new String[]{"invite"}, "/party");
        party.run(server.getConsoleSender(), new String[]{"pay", "1"}, "/party");
        assertThat(ran).containsExactly("invite Bo");
        assertThat(next(p)).isEqualTo("'x' is not a whole number.");
        assertThat(next(p)).isEqualTo("Missing argument 1. Usage: /party invite <player>");
        assertThat(party.complete(p, new String[]{"in"})).containsExactly("invite");
        assertThat(party.complete(p, new String[]{"invite", "b"})).containsExactly("Bo");
    }

    @Test
    void playersAndLanguages() {
        PlayerMock p = server.addPlayer();
        ItemStack stack = ItemStack.of(Material.STONE, 64);
        for (int i = 0; i < 36; i++) {
            p.getInventory().addItem(stack.clone());
        }
        assertThat(Players.freeSlots(p)).isZero();
        Players.give(p, ItemStack.of(Material.DIAMOND)); // fits nowhere in storage: dropped or put in the off hand
        p.setHealth(3);
        Players.heal(p);
        assertThat(p.getHealth()).isEqualTo(20);
        Lang lang = Lang.of(Messages.of("en", Map.of("hi", "<green>Hi <name>")));
        lang.send(p, "hi", "name", "Alex");
        assertThat(next(p)).isEqualTo("Hi Alex");
    }
}
