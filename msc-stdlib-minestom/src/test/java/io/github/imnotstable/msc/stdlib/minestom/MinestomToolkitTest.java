package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.ui.Bar;
import io.github.imnotstable.msc.stdlib.ui.Feedback;
import net.kyori.adventure.bossbar.BossBar;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.event.player.PlayerUseItemEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@EnvTest
class MinestomToolkitTest {
    @Test
    void hologramsBoardsAndFeedback(Env env) {
        Stdlib.init(env.process());
        Instance world = env.createFlatInstance();
        Player p = env.createPlayer(world, new Pos(0, 42, 0));
        Hologram h = Hologram.spawn(world, new Pos(0, 44, 0), "<gold>Top", "<gray>1. Alex");
        assertThat(h.entity().getInstance()).isSameAs(world);
        h.remove();
        int[] calls = {0};
        var board = Boards.show(p, "<gold>Game", pl -> List.of("Kills: " + calls[0]++, "", "end"), 5);
        assertThat(board.getLines()).hasSize(3);
        for (int i = 0; i < 6; i++) {
            env.tick();
        }
        assertThat(calls[0]).isGreaterThan(1);
        Boards.hide(p);
        Feedback.message(p, "ok");
        Feedback.title(p, "<gold>Hi", "<gray>there");
    }

    @Test
    void customItemsRegionsPromptsAndBars(Env env) {
        Stdlib.init(env.process());
        Instance world = env.createFlatInstance();
        Player p = env.createPlayer(world, new Pos(0, 42, 0));
        List<String> seen = new ArrayList<>();
        CustomItems.register("dash", () -> ItemBuilder.of(Material.FEATHER).name("Dash").build())
                .onUse(e -> seen.add("use")).cooldown(Duration.ofSeconds(5));
        ItemStack dash = CustomItems.create("dash");
        assertThat(CustomItems.is(dash, "dash")).isTrue();
        for (int i = 0; i < 2; i++) {
            PlayerUseItemEvent e = new PlayerUseItemEvent(p, PlayerHand.MAIN, dash, 0);
            env.process().eventHandler().call(e);
            assertThat(e.isCancelled()).isTrue();
        }
        Regions.define("spawn", world, Cuboid.of(Vec3.ZERO, new Vec3(10, 100, 10))).noPvp()
                .onEnter(pl -> seen.add("enter")).onLeave(pl -> seen.add("leave"));
        Regions.update(p, world, new Pos(5, 42, 5));
        Regions.update(p, world, new Pos(50, 42, 5));
        assertThat(Regions.pvpAllowed(p)).isFalse(); // the player still stands at 0 42 0, inside

        Prompt.ask(p, "Name?", Duration.ofSeconds(30), a -> seen.add("answer " + a), () -> seen.add("cancel"));
        assertThat(Prompt.answer(p, "Castle")).isTrue();
        env.tick();

        boolean[] done = {false};
        Bar bar = Bar.timer(Stdlib.scheduler(), List.of(p), "<time>", Duration.ofMillis(500), BossBar.Color.RED,
                () -> done[0] = true);
        for (int i = 0; i < 15; i++) {
            env.tick();
        }
        assertThat(done[0]).isTrue();
        assertThat(bar.bossBar().progress()).isZero();
        assertThat(seen).containsExactly("use", "enter", "leave", "answer Castle");

        Lang lang = Lang.of(Messages.of("en", Map.of("hi", "Hi <name>")));
        assertThat(io.github.imnotstable.msc.stdlib.text.Text.plain(lang.get(p, "hi", "name", "Alex"))).isEqualTo("Hi Alex");
        assertThat(Players.give(p, ItemStack.of(Material.DIAMOND))).isTrue();
    }
}
