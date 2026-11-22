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
}
