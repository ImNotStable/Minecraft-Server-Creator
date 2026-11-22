package com.example.verifyme;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.ColumnType;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.DatabaseType;
import io.github.imnotstable.msc.stdlib.db.Migration;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.Table;
import io.github.imnotstable.msc.stdlib.neoforge.Gui;
import io.github.imnotstable.msc.stdlib.neoforge.ItemBuilder;
import io.github.imnotstable.msc.stdlib.neoforge.Locations;
import io.github.imnotstable.msc.stdlib.neoforge.ParticleEffect;
import io.github.imnotstable.msc.stdlib.neoforge.PlayerData;
import io.github.imnotstable.msc.stdlib.neoforge.Stdlib;
import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import io.github.imnotstable.msc.stdlib.util.WeightedRandom;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Uses the msc-stdlib names CLAUDE.md lists, so one the shipped library does not have fails the build. Never called. */
final class StdlibNames {
    private StdlibNames() {
    }

    record Account(int coins) {
    }

    static void use(ServerPlayer player, ServerLevel level) {
        Bootstrap.bootStrap();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
                .forEach(DataComponentInitializers.PendingComponents::apply);
        Stdlib.init();
        Stdlib.server();
        ItemStack item = ItemBuilder.of(Items.DIAMOND).name(ItemBuilder.text("Hi", ChatFormatting.GREEN))
                .lore(Component.literal("x")).glow().unbreakable().model("minecraft:diamond_sword").data("kit", "a")
                .with(DataComponents.MAX_STACK_SIZE, 1).build();
        String kit = ItemBuilder.read(item, "kit");
        Gui.chest(3, Component.literal(kit)).set(0, item, click -> click.close()).fill(ItemBuilder.filler())
                .border(item).editable(4).onClose(p -> { }).open(player);
        Gui.paged(6, Component.literal("List"), List.of(1, 2), n -> item, (entry, click) -> { }).open(player);

        Vec3 here = Locations.vec(player.position());
        List<Vec3> points = List.of(Shapes.circle(1, 8), Shapes.sphere(1, 8), Shapes.helix(1, 2, 1, 8),
                Shapes.star(2, 1, 5, 3), Shapes.cuboidEdges(Vec3.ZERO, Vec3.ZERO, 1)).stream()
                .flatMap(List::stream).toList();
        ParticleEffect.of(ParticleTypes.FLAME).spawn(level, here);
        ParticleEffect.dust(0xFF0000, 1).draw(level, here, Locations.facing(player, points));
        ParticleEffect.of(ParticleTypes.FLAME).play(Stdlib.scheduler(), level, here, 2, 10, f -> points);

        Database db = Database.open(DatabaseConfig.sqlite(Path.of("data.db")));
        DatabaseConfig.server(DatabaseType.MYSQL, "h", 0, "d", "u", "p");
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
        cooldowns.tryUse(player.getUUID(), Durations.parse("1h30m"));
        Durations.format(cooldowns.remaining(player.getUUID()));
        Stdlib.scheduler().later(20, () -> { });
        Stdlib.scheduler().repeat(0, 20, () -> { }).cancel();
        Stdlib.scheduler().countdown(3, s -> { }, () -> { });
        Stdlib.scheduler().supplyAsync(() -> kit);
        new WeightedRandom<String>().add("a", 1).pick(new Random());
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

    static void toolkit(net.minecraft.server.level.ServerPlayer player) {
        io.github.imnotstable.msc.stdlib.neoforge.Feedback.success(player, "a");
        io.github.imnotstable.msc.stdlib.neoforge.Feedback.fail(player, "a");
        io.github.imnotstable.msc.stdlib.neoforge.Feedback.info(player, "a");
        io.github.imnotstable.msc.stdlib.neoforge.Feedback.actionBar(player, "a");
        io.github.imnotstable.msc.stdlib.neoforge.Feedback.title(player, net.minecraft.network.chat.Component.literal("a"),
                net.minecraft.network.chat.Component.literal("b"));
        io.github.imnotstable.msc.stdlib.neoforge.Sounds.SUCCESS.play(player);
        io.github.imnotstable.msc.stdlib.neoforge.Bar.create(net.minecraft.network.chat.Component.literal("a"),
                net.minecraft.world.BossEvent.BossBarColor.RED).show(player);
        io.github.imnotstable.msc.stdlib.neoforge.Bar.timer(java.util.List.of(player),
                t -> net.minecraft.network.chat.Component.literal(t), java.time.Duration.ofSeconds(5),
                net.minecraft.world.BossEvent.BossBarColor.RED, () -> { });
        io.github.imnotstable.msc.stdlib.neoforge.Hologram.spawn(player.level(),
                io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO, net.minecraft.network.chat.Component.literal("a")).remove();
        String id = "dash";
        java.util.function.Supplier<net.minecraft.world.item.ItemStack> factory =
                () -> new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.FEATHER);
        io.github.imnotstable.msc.stdlib.neoforge.CustomItems.register(id, factory).onUse(p -> { }).onHit((p, e) -> { })
                .cooldown(java.time.Duration.ofSeconds(1));
        String name = "spawn";
        io.github.imnotstable.msc.stdlib.geom.Region region = io.github.imnotstable.msc.stdlib.geom.Region.sphere(
                io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO, 5);
        io.github.imnotstable.msc.stdlib.neoforge.Regions.define(name, net.minecraft.world.level.Level.OVERWORLD, region)
                .noBuild().noPvp().onEnter(p -> { });
        io.github.imnotstable.msc.stdlib.neoforge.Prompt.ask(player, "?", java.time.Duration.ofSeconds(5), a -> { }, () -> { });
        io.github.imnotstable.msc.stdlib.neoforge.Teleports.warmup(player, player.level(),
                io.github.imnotstable.msc.stdlib.geom.Vec3.ZERO, 3, true, ok -> { });
        io.github.imnotstable.msc.stdlib.neoforge.Players.give(player);
        io.github.imnotstable.msc.stdlib.neoforge.Players.heal(player);
        io.github.imnotstable.msc.stdlib.neoforge.Players.reset(player, net.minecraft.world.level.GameType.SURVIVAL);
        io.github.imnotstable.msc.stdlib.neoforge.Lang.of(io.github.imnotstable.msc.stdlib.text.Messages.of("en",
                java.util.Map.of())).send(player, "k");
    }
}
