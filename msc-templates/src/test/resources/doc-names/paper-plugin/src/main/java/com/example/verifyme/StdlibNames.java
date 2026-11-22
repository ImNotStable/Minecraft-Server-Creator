package com.example.verifyme;

import io.github.imnotstable.msc.stdlib.cache.Cache;
import io.github.imnotstable.msc.stdlib.config.Config;
import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.Id;
import io.github.imnotstable.msc.stdlib.db.Repository;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.economy.Economy;
import io.github.imnotstable.msc.stdlib.event.EventBus;
import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Region;
import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.messaging.Messenger;
import io.github.imnotstable.msc.stdlib.paper.Bar;
import io.github.imnotstable.msc.stdlib.paper.Boards;
import io.github.imnotstable.msc.stdlib.paper.Cmd;
import io.github.imnotstable.msc.stdlib.paper.CustomItems;
import io.github.imnotstable.msc.stdlib.paper.Feedback;
import io.github.imnotstable.msc.stdlib.paper.Gui;
import io.github.imnotstable.msc.stdlib.paper.Hologram;
import io.github.imnotstable.msc.stdlib.paper.ItemBuilder;
import io.github.imnotstable.msc.stdlib.paper.Lang;
import io.github.imnotstable.msc.stdlib.paper.Nametags;
import io.github.imnotstable.msc.stdlib.paper.PaperDatabase;
import io.github.imnotstable.msc.stdlib.paper.ParticleEffect;
import io.github.imnotstable.msc.stdlib.paper.PlayerData;
import io.github.imnotstable.msc.stdlib.paper.Players;
import io.github.imnotstable.msc.stdlib.paper.Prompt;
import io.github.imnotstable.msc.stdlib.paper.Regions;
import io.github.imnotstable.msc.stdlib.paper.Sidebar;
import io.github.imnotstable.msc.stdlib.paper.Sounds;
import io.github.imnotstable.msc.stdlib.paper.Stdlib;
import io.github.imnotstable.msc.stdlib.paper.Teleports;
import io.github.imnotstable.msc.stdlib.sched.Sequence;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import io.github.imnotstable.msc.stdlib.time.RateLimiter;
import io.github.imnotstable.msc.stdlib.util.Format;
import io.github.imnotstable.msc.stdlib.util.Leaderboard;
import io.github.imnotstable.msc.stdlib.util.Randoms;
import io.github.imnotstable.msc.stdlib.util.WeightedRandom;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses the msc-stdlib names CLAUDE.md lists, so one the shipped library does not have fails the build. Never called. */
final class StdlibNames {
    private StdlibNames() {
    }

    record Home(@Id UUID id, String name, int uses) {
    }

    static void use(JavaPlugin plugin, Player player, NamespacedKey key) {
        Stdlib.init(plugin);
        Feedback.success(player, "ok");
        Feedback.fail(player, "no");
        Feedback.info(player, "hi");
        Feedback.actionBar(player, "bar");
        Feedback.title(player, "a", "b");
        Sounds.SUCCESS.play(player);
        Bar.create("x", BossBar.Color.RED).progress(0.5).hideAfter(Duration.ofSeconds(1));
        Bar.timer(List.of(player), "<time>", Duration.ofSeconds(5), BossBar.Color.YELLOW, () -> { });
        Hologram.spawn(player.getLocation(), "line").remove();
        new Sidebar(Component.text("t")).show(player);
        Boards.show(player, "t", p -> List.of("a"), 20);
        Nametags.set(player, "[A] ", "", NamedTextColor.RED);

        int rows = 3;
        Component title = Component.text("Menu");
        ItemStack item = ItemBuilder.of(Material.DIAMOND).name("<red>x").lore("y").glow().model(key)
                .data(key, PersistentDataType.STRING, "v").build();
        ItemStack head = ItemBuilder.head(player).build();
        Gui.chest(rows, title).layout("#########").bind('#', ItemBuilder.pane(Material.BLACK_STAINED_GLASS_PANE), c -> { })
                .set(0, head, c -> { }).theme(Gui.Theme.DARK).update(0, item).refreshEvery(20, g -> { })
                .animate(1, 10, List.of(item)).editable(4).onClose(p -> { }).open(player);
        Gui.paged(rows, title, List.of(1), n -> item, (n, c) -> { });
        Gui.confirm(title, item, p -> { }, p -> { });

        String id = "wand";
        CustomItems.register(id, () -> item).onUse(e -> { }).onHit(e -> { }).cooldown(Duration.ofSeconds(1));
        String name = "spawn";
        String world = "world";
        Region region = Cuboid.of(Vec3.ZERO, new Vec3(1, 1, 1));
        Regions.define(name, world, region).noBuild().noPvp().onEnter(p -> { });
        Cmd.create(name).sub(Cmd.create("x").usage("<a>").tab(c -> List.of()).cooldown(Duration.ofSeconds(1))
                .executes(c -> { })).register(plugin);
        Prompt.ask(player, "?", Duration.ofSeconds(5), a -> { }, () -> { });
        Teleports.warmup(player, player.getLocation(), 3, true, ok -> { });
        Players.give(player, item);
        Players.heal(player);
        Players.reset(player, GameMode.SURVIVAL);
        Lang.load(plugin, "en", Map.of("k", "v")).send(player, "k");

        Database db = Database.open(PaperDatabase.config(plugin.getConfig().getConfigurationSection("database"),
                plugin.getDataFolder().toPath()));
        String table = "homes";
        Repository<Home> repo = Repository.of(db, table, Home.class);
        repo.save(new Home(UUID.randomUUID(), "a", 0));
        repo.find(UUID.randomUUID());
        repo.findBy("name", "a");
        repo.query().where("uses", ">", 1).list();
        repo.top("uses", 3);
        repo.increment(UUID.randomUUID(), "uses", 1);
        repo.async(r -> r.count());
        db.async(d -> d.update("DELETE FROM homes"));
        Store<String> s = Store.writeBehind(Store.cached(Store.sql(db, "kv", Codec.string()),
                Cache.<String, String>builder().maxSize(10).build()), Duration.ofSeconds(5));
        Store.jsonFiles(Path.of("kits"), Codec.string());
        Store.memory();
        s.keys();
        Economy.sql(db, "coins", "$", 100).balance(UUID.randomUUID());
        Config.load(Path.of("c.json"), Home.class, () -> new Home(UUID.randomUUID(), "a", 0));
        Messenger.local().close();
        Class<?> backends = io.github.imnotstable.msc.stdlib.redis.Redis.class;
        backends = io.github.imnotstable.msc.stdlib.mongo.Mongo.class;
        PlayerData.create(Store.<Home>memory(), () -> new Home(UUID.randomUUID(), "a", 0));

        Format.number(1);
        Format.compact(1);
        Format.money(1, "$");
        Format.bar(1, 2, 10);
        Format.clock(Duration.ZERO);
        Format.duration(Durations.parse("1m"));
        Format.ordinal(1);
        new Cooldowns<UUID>().tryUse(player.getUniqueId(), Duration.ofSeconds(1));
        new RateLimiter<UUID>(1, Duration.ofSeconds(1)).tryAcquire(player.getUniqueId());
        new Leaderboard<UUID>().add(player.getUniqueId(), 1);
        new WeightedRandom<String>().add("a", 1).pick(new Random());
        Randoms.chance(0.5);
        new EventBus().post(backends);
        Sequence.of(Stdlib.scheduler()).run(() -> { }).pause(20).start();
        ParticleEffect.of(Particle.FLAME).draw(player.getLocation(), Shapes.circle(1, 8));
        ParticleEffect.of(Particle.FLAME).play(Stdlib.scheduler(), player.getLocation(), 2, 5, f -> Shapes.star(2, 1, 5, 3));
        db.close();
    }
}
