package com.example.verifyme;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.ColumnType;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.Migration;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.Table;
import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.paper.Gui;
import io.github.imnotstable.msc.stdlib.paper.ItemBuilder;
import io.github.imnotstable.msc.stdlib.paper.Locations;
import io.github.imnotstable.msc.stdlib.paper.PaperDatabase;
import io.github.imnotstable.msc.stdlib.paper.ParticleEffect;
import io.github.imnotstable.msc.stdlib.paper.PlayerData;
import io.github.imnotstable.msc.stdlib.paper.Sidebar;
import io.github.imnotstable.msc.stdlib.paper.Stdlib;
import io.github.imnotstable.msc.stdlib.paper.Text;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import io.github.imnotstable.msc.stdlib.util.WeightedRandom;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Uses the msc-stdlib names CLAUDE.md lists, so one the shipped library does not have fails the build. Never called. */
final class StdlibNames {
    private StdlibNames() {
    }

    record Account(int coins) {
    }

    static void use(JavaPlugin plugin, Player player, NamespacedKey key) {
        Stdlib.init(plugin);
        ItemStack item = ItemBuilder.of(Material.DIAMOND_SWORD).name("<red>Slayer").lore("<gray>Kills: <k>", "k", 1)
                .enchant(Enchantment.SHARPNESS, 1).glow().unbreakable().model(key)
                .data(key, PersistentDataType.STRING, "slayer").meta(m -> { }).build();
        String id = ItemBuilder.read(item, key, PersistentDataType.STRING);
        ItemStack head = ItemBuilder.head(player).build();
        Gui.chest(3, Text.mm("<gold>Menu <name>", "name", id)).set(0, head, click -> click.close())
                .fill(ItemBuilder.filler()).border(item).editable(4).onClose(p -> { }).open(player);
        Gui.paged(6, Component.text("List"), List.of(1, 2), n -> item, (n, click) -> { }).open(player);
        String plain = Text.plain(Text.legacy("&aHi"));

        List<Vec3> ring = Shapes.translate(Shapes.rotateY(Shapes.circle(1, 8), 1), Vec3.ZERO);
        List<Vec3> points = List.of(Shapes.line(Vec3.ZERO, Vec3.ZERO, 2), Shapes.arc(1, 0, 1, 4, true),
                Shapes.disc(1, 0.5), Shapes.sphere(1, 8), Shapes.helix(1, 2, 1, 8),
                Shapes.cuboidEdges(Vec3.ZERO, Vec3.ZERO, 1), Shapes.star(2, 1, 5, 3),
                Shapes.randomInSphere(1, 3, new Random()), ring).stream().flatMap(List::stream).toList();
        ParticleEffect.of(Particle.FLAME).spawn(player.getLocation());
        ParticleEffect.dust(Color.RED, 1).draw(player.getLocation(), Locations.facing(player.getLocation(), points));
        ParticleEffect.of(Particle.FLAME).play(Stdlib.scheduler(), player.getLocation(), 2, 10, f -> points);

        Database db = Database.open(PaperDatabase.config(plugin.getConfig().getConfigurationSection("database"),
                plugin.getDataFolder().toPath()));
        db.migrate("names", List.of(Migration.of(1, "t", Table.named("t").key("k", ColumnType.STRING)
                .column("v", ColumnType.INT).sql(db.type()))));
        db.update("DELETE FROM t");
        db.insert("INSERT INTO t (k, v) VALUES (?, ?)", "a", 1);
        db.query("SELECT v FROM t", r -> r.integer("v"));
        db.queryOne("SELECT v FROM t", r -> r.integer("v"));
        db.batch("DELETE FROM t WHERE k = ?", List.<Object[]>of(new Object[] {"a"}));
        db.transaction(c -> Database.update(c, "DELETE FROM t"));
        db.async(d -> d.update("DELETE FROM t"));
        Store<Account> store = Store.sql(db, "accounts", Codec.json(Account.class));
        PlayerData<Account> data = PlayerData.create(Store.memory(), () -> new Account(0));
        data.update(player, a -> data.get(player));
        data.saveAll();
        store.keys();
        db.close();

        Cooldowns<UUID> cooldowns = new Cooldowns<>();
        cooldowns.tryUse(player.getUniqueId(), Durations.parse("1h30m"));
        Durations.format(cooldowns.remaining(player.getUniqueId()));
        Stdlib.scheduler().later(20, () -> { });
        Stdlib.scheduler().repeat(0, 20, () -> { }).cancel();
        Stdlib.scheduler().countdown(3, s -> { }, () -> { });
        Stdlib.scheduler().supplyAsync(() -> plain);
        Sidebar board = new Sidebar(Component.text("Game"));
        board.lines(List.of(Component.text("a")));
        board.line(0, Component.text("b"));
        board.show(player);
        board.hide(player);
        new WeightedRandom<String>().add("a", 1).pick(new Random());
    }
}
