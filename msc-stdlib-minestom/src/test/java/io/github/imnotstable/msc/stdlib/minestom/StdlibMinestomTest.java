package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.inventory.InventoryPreClickEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.inventory.click.Click;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.tag.Tag;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@EnvTest
class StdlibMinestomTest {
    static InventoryPreClickEvent click(Env env, Player p, Click c) {
        InventoryPreClickEvent e = new InventoryPreClickEvent(p.getOpenInventory(), p, c);
        env.process().eventHandler().call(e);
        return e;
    }

    @Test
    void aGuiRunsHandlersAndKeepsItsItems(Env env) {
        Stdlib.init(env.process());
        Instance world = env.createFlatInstance();
        Player p = env.createPlayer(world, new Pos(0, 42, 0));
        List<Integer> clicks = new ArrayList<>();
        Gui gui = Gui.chest(3, Component.text("Shop")).set(13, ItemStack.of(Material.DIAMOND), c -> clicks.add(c.slot()))
                .editable(0).fill(ItemBuilder.filler()).open(p);
        assertThat(p.getOpenInventory()).isSameAs(gui.inventory());
        assertThat(click(env, p, new Click.Right(13)).isCancelled()).isTrue();
        assertThat(click(env, p, new Click.Left(0)).isCancelled()).isFalse();
        InventoryPreClickEvent shift = new InventoryPreClickEvent(p.getInventory(), p, new Click.LeftShift(3));
        env.process().eventHandler().call(shift);
        assertThat(shift.isCancelled()).isTrue();
        assertThat(clicks).containsExactly(13);
    }

    @Test
    void aPagedGuiTurnsPages(Env env) {
        Stdlib.init(env.process());
        Player p = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        List<Integer> picked = new ArrayList<>();
        Gui gui = Gui.paged(2, Component.text("Numbers"), IntStream.range(0, 20).boxed().toList(),
                i -> ItemStack.of(Material.STONE, i + 1), (i, c) -> picked.add(i)).open(p);
        click(env, p, new Click.Left(17));
        click(env, p, new Click.Left(0));
        assertThat(picked).containsExactly(9);
        assertThat(Text.plain(gui.inventory().getItemStack(13).get(net.minestom.server.component.DataComponents.CUSTOM_NAME)))
                .isEqualTo("Page 2/3");
    }

    @Test
    void itemsCarryNamesAndTags() {
        Tag<String> kit = Tag.String("kit");
        ItemStack item = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 3)
                .glow().tag(kit, "slayer").build();
        assertThat(ItemBuilder.read(item, kit)).isEqualTo("slayer");
        assertThat(Text.plain(item.get(net.minestom.server.component.DataComponents.LORE).getFirst())).isEqualTo("Kills: 3");
    }

    @Test
    void playerDataIsSavedOnLeave(Env env) {
        Stdlib.init(env.process());
        record Stats(int kills) {}
        Store<Stats> store = Store.memory();
        PlayerData<Stats> data = PlayerData.create(store, () -> new Stats(0));
        Player p = env.createPlayer(env.createFlatInstance(), new Pos(0, 42, 0));
        data.update(p, s -> new Stats(s.kills() + 2));
        data.saveAll();
        assertThat(store.get(p.getUuid().toString())).contains(new Stats(2));
    }

    @Test
    void schedulerCountsDown(Env env) {
        Stdlib.init(env.process());
        List<Integer> ticks = new ArrayList<>();
        boolean[] done = {false};
        Stdlib.scheduler().countdown(2, ticks::add, () -> done[0] = true);
        for (int i = 0; i < 60; i++) {
            env.tick();
        }
        assertThat(ticks).containsExactly(2, 1);
        assertThat(done[0]).isTrue();
    }
}
