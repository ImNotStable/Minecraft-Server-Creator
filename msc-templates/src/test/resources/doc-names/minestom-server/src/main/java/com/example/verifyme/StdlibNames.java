package com.example.verifyme;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.ColumnType;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.Migration;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.Table;
import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.minestom.Gui;
import io.github.imnotstable.msc.stdlib.minestom.ItemBuilder;
import io.github.imnotstable.msc.stdlib.minestom.Locations;
import io.github.imnotstable.msc.stdlib.minestom.ParticleEffect;
import io.github.imnotstable.msc.stdlib.minestom.PlayerData;
import io.github.imnotstable.msc.stdlib.minestom.Stdlib;
import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import io.github.imnotstable.msc.stdlib.util.WeightedRandom;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.particle.Particle;
import net.minestom.server.scoreboard.Sidebar;
import net.minestom.server.tag.Tag;

/** Uses the msc-stdlib names CLAUDE.md lists, so one the shipped library does not have fails the build. Never called. */
final class StdlibNames {
    private StdlibNames() {
    }

    record Account(int coins) {
    }

    static void use(Player player, Instance instance) {
        Stdlib.init(MinecraftServer.process());
        Tag<String> tag = Tag.String("kit");
        ItemStack item = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 1)
                .glow().hideTooltip().model("minecraft:diamond_sword").tag(tag, "slayer").build();
        ItemBuilder.of(Material.STONE).raw().amount(2);
        String kit = ItemBuilder.read(item, tag);
        Gui.chest(3, Text.mm("<gold>Menu <name>", "name", kit)).set(0, item, click -> click.close())
                .fill(ItemBuilder.filler()).border(item).editable(4).onClose(p -> { }).open(player);
        Gui.paged(6, Component.text("List"), List.of(1, 2), n -> item, (entry, click) -> { }).open(player);
        String plain = Text.plain(Component.text("x"));

        List<Vec3> points = Shapes.circle(1, 8);
        points = List.of(Shapes.sphere(1, 8), Shapes.helix(1, 2, 1, 8), Shapes.star(2, 1, 5, 3),
                Shapes.cuboidEdges(Vec3.ZERO, Vec3.ZERO, 1), points).stream().flatMap(List::stream).toList();
        ParticleEffect.of(Particle.FLAME).spawn(instance, player.getPosition());
        ParticleEffect.dust(NamedTextColor.RED, 1).draw(instance, player.getPosition(),
                Locations.facing(player.getPosition(), points));
        List<Vec3> frame = points;
        ParticleEffect.of(Particle.FLAME).play(Stdlib.scheduler(), instance, player.getPosition(), 2, 10, f -> frame);
        new Sidebar(Component.text("Game")).addViewer(player);

        Database db = Database.open(DatabaseConfig.sqlite(Path.of("data.db")));
        DatabaseConfig.server(io.github.imnotstable.msc.stdlib.db.DatabaseType.MYSQL, "h", 0, "d", "u", "p");
        db.migrate("names", List.of(Migration.of(1, "t", Table.named("t").key("k", ColumnType.STRING)
                .column("v", ColumnType.INT).sql(db.type()))));
        db.update("DELETE FROM t");
        db.insert("INSERT INTO t (k, v) VALUES (?, ?)", "a", 1);
        db.query("SELECT v FROM t", r -> r.integer("v"));
        db.queryOne("SELECT v FROM t", r -> r.integer("v"));
        db.transaction(c -> Database.update(c, "DELETE FROM t"));
        db.async(d -> d.update("DELETE FROM t"));
        Store<Account> store = Store.sql(db, "accounts", Codec.json(Account.class));
        PlayerData<Account> data = PlayerData.create(Store.memory(), () -> new Account(0));
        data.update(player, a -> data.get(player));
        data.saveAll();
        store.keys();
        db.close();

        Cooldowns<UUID> cooldowns = new Cooldowns<>();
        cooldowns.tryUse(player.getUuid(), Durations.parse("1h30m"));
        Durations.format(cooldowns.remaining(player.getUuid()));
        Stdlib.scheduler().later(20, () -> { });
        Stdlib.scheduler().repeat(0, 20, () -> { }).cancel();
        Stdlib.scheduler().countdown(3, s -> { }, () -> { });
        new WeightedRandom<String>().add(plain, 1).pick(new Random());
    }

    record Entry(@io.github.imnotstable.msc.stdlib.db.Id String id, int score) {
    }

    static void shared(io.github.imnotstable.msc.stdlib.db.Database db) {
        io.github.imnotstable.msc.stdlib.db.Repository<Entry> repo =
                io.github.imnotstable.msc.stdlib.db.Repository.of(db, "entries", Entry.class);
        repo.top("score", 3);
        io.github.imnotstable.msc.stdlib.db.Store<String> s = io.github.imnotstable.msc.stdlib.db.Store.writeBehind(
                io.github.imnotstable.msc.stdlib.db.Store.cached(
                        io.github.imnotstable.msc.stdlib.db.Store.jsonFiles(java.nio.file.Path.of("d"),
                                io.github.imnotstable.msc.stdlib.db.Codec.string()),
                        io.github.imnotstable.msc.stdlib.cache.Cache.<String, String>builder().maxSize(5).build()),
                java.time.Duration.ofSeconds(5));
        s.keys();
        io.github.imnotstable.msc.stdlib.economy.Economy.sql(db, "coins", "$", 100);
        io.github.imnotstable.msc.stdlib.config.Config.save(java.nio.file.Path.of("c.json"), new Entry("a", 1));
        io.github.imnotstable.msc.stdlib.messaging.Messenger.local().close();
        Class<?> backends = io.github.imnotstable.msc.stdlib.redis.Redis.class;
        backends = io.github.imnotstable.msc.stdlib.mongo.Mongo.class;
        io.github.imnotstable.msc.stdlib.util.Format.number(backends.getName().length());
        new io.github.imnotstable.msc.stdlib.time.RateLimiter<String>(1, java.time.Duration.ofSeconds(1)).tryAcquire("a");
        new io.github.imnotstable.msc.stdlib.util.Leaderboard<String>().add("a", 1);
        new io.github.imnotstable.msc.stdlib.event.EventBus().post("x");
        io.github.imnotstable.msc.stdlib.geom.Region region = io.github.imnotstable.msc.stdlib.geom.Cuboid.of(
                io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO, io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO);
        region.center();
        io.github.imnotstable.msc.stdlib.util.Randoms.chance(0.5);
    }

    static void sequence(io.github.imnotstable.msc.stdlib.sched.Scheduler scheduler) {
        io.github.imnotstable.msc.stdlib.sched.Sequence.of(scheduler).run(() -> { }).start();
    }

    static void toolkit(net.minestom.server.entity.Player player, net.minestom.server.instance.Instance instance) {
        io.github.imnotstable.msc.stdlib.ui.Feedback.success(player, "a");
        io.github.imnotstable.msc.stdlib.ui.Feedback.fail(player, "a");
        io.github.imnotstable.msc.stdlib.ui.Feedback.info(player, "a");
        io.github.imnotstable.msc.stdlib.ui.Feedback.actionBar(player, "a");
        io.github.imnotstable.msc.stdlib.ui.Feedback.title(player, "a", "b");
        io.github.imnotstable.msc.stdlib.ui.Sounds.SUCCESS.play(player);
        io.github.imnotstable.msc.stdlib.ui.Bar.create("a", net.kyori.adventure.bossbar.BossBar.Color.RED).show(player);
        io.github.imnotstable.msc.stdlib.ui.Bar.timer(io.github.imnotstable.msc.stdlib.minestom.Stdlib.scheduler(),
                java.util.List.of(player), "<time>", java.time.Duration.ofSeconds(5),
                net.kyori.adventure.bossbar.BossBar.Color.RED, () -> { });
        io.github.imnotstable.msc.stdlib.minestom.Hologram.spawn(instance, player.getPosition(), "a").remove();
        io.github.imnotstable.msc.stdlib.minestom.Boards.show(player, "t", p -> java.util.List.of("a"), 20);
        String id = "dash";
        java.util.function.Supplier<net.minestom.server.item.ItemStack> factory =
                () -> net.minestom.server.item.ItemStack.of(net.minestom.server.item.Material.FEATHER);
        io.github.imnotstable.msc.stdlib.minestom.CustomItems.register(id, factory).onUse(e -> { }).onHit(e -> { })
                .cooldown(java.time.Duration.ofSeconds(1));
        String name = "spawn";
        io.github.imnotstable.msc.stdlib.geom.Region region = io.github.imnotstable.msc.stdlib.geom.Region.sphere(
                io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO, 5);
        io.github.imnotstable.msc.stdlib.minestom.Regions.define(name, instance, region).noBuild().noDamage()
                .onEnter(p -> { });
        net.minestom.server.entity.Player victim = player;
        io.github.imnotstable.msc.stdlib.minestom.Regions.pvpAllowed(victim);
        io.github.imnotstable.msc.stdlib.minestom.Prompt.ask(player, "?", java.time.Duration.ofSeconds(5), a -> { }, () -> { });
        io.github.imnotstable.msc.stdlib.minestom.Teleports.warmup(player, null, player.getPosition(), 3, true, ok -> { });
        io.github.imnotstable.msc.stdlib.minestom.Players.give(player);
        io.github.imnotstable.msc.stdlib.minestom.Players.heal(player);
        io.github.imnotstable.msc.stdlib.minestom.Players.reset(player, net.minestom.server.entity.GameMode.SURVIVAL);
        io.github.imnotstable.msc.stdlib.minestom.Lang.of(io.github.imnotstable.msc.stdlib.text.Messages.of("en",
                java.util.Map.of())).send(player, "k");
    }
}
