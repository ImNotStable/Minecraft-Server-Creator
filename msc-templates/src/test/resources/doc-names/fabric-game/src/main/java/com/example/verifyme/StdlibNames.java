package com.example.verifyme;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.ColumnType;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.DatabaseType;
import io.github.imnotstable.msc.stdlib.db.Migration;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.Table;
import io.github.imnotstable.msc.stdlib.fabric.Gui;
import io.github.imnotstable.msc.stdlib.fabric.ItemBuilder;
import io.github.imnotstable.msc.stdlib.fabric.Locations;
import io.github.imnotstable.msc.stdlib.fabric.ParticleEffect;
import io.github.imnotstable.msc.stdlib.fabric.PlayerData;
import io.github.imnotstable.msc.stdlib.fabric.Stdlib;
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
}
