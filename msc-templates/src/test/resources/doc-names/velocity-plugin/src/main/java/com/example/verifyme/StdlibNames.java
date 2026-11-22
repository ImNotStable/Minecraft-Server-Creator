package com.example.verifyme;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import io.github.imnotstable.msc.stdlib.cache.Cache;
import io.github.imnotstable.msc.stdlib.config.Config;
import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.Id;
import io.github.imnotstable.msc.stdlib.db.Repository;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.economy.Economy;
import io.github.imnotstable.msc.stdlib.event.EventBus;
import io.github.imnotstable.msc.stdlib.messaging.Messenger;
import io.github.imnotstable.msc.stdlib.sched.Sequence;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.time.Cooldowns;
import io.github.imnotstable.msc.stdlib.time.Durations;
import io.github.imnotstable.msc.stdlib.time.RateLimiter;
import io.github.imnotstable.msc.stdlib.ui.Bar;
import io.github.imnotstable.msc.stdlib.ui.Feedback;
import io.github.imnotstable.msc.stdlib.ui.Sounds;
import io.github.imnotstable.msc.stdlib.util.Format;
import io.github.imnotstable.msc.stdlib.util.Leaderboard;
import io.github.imnotstable.msc.stdlib.util.Randoms;
import io.github.imnotstable.msc.stdlib.velocity.Lang;
import io.github.imnotstable.msc.stdlib.velocity.PlayerData;
import io.github.imnotstable.msc.stdlib.velocity.Servers;
import io.github.imnotstable.msc.stdlib.velocity.Stdlib;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.bossbar.BossBar;

/** Uses the msc-stdlib names CLAUDE.md lists, so one the shipped library does not have fails the build. Never called. */
final class StdlibNames {
    private StdlibNames() {
    }

    record Profile(@Id UUID id, int coins) {
    }

    static void use(Object plugin, ProxyServer proxy, Player player) {
        Stdlib.init(plugin, proxy);
        Servers.send(player, "lobby");
        Servers.current(player);
        Servers.players("lobby");
        Servers.broadcast("<gold>Hi");
        Lang.load(Path.of("data"), "en", Map.of()).send(player, "k");
        Lang.of(Messages.of("en", Map.of()));
        Feedback.success(player, "a");
        Feedback.fail(player, "a");
        Feedback.info(player, "a");
        Feedback.actionBar(player, "a");
        Feedback.title(player, "a", "b");
        Sounds.SUCCESS.play(player);
        Bar.timer(Stdlib.scheduler(), List.of(player), "<time>", Duration.ofSeconds(5), BossBar.Color.RED, () -> { });
        Database db = Database.open(DatabaseConfig.sqlite(Path.of("data.db")));
        Repository<Profile> repo = Repository.of(db, "profiles", Profile.class);
        PlayerData.create(Store.sql(db, "kv", Codec.json(Profile.class)), () -> new Profile(UUID.randomUUID(), 0));
        Store.jsonFiles(Path.of("d"), Codec.string());
        Store<String> s = Store.writeBehind(Store.cached(Store.memory(), Cache.<String, String>builder().build()),
                Duration.ofSeconds(1));
        s.keys();
        repo.top("coins", 3);
        Economy.sql(db, "coins", "$", 100);
        Config.save(Path.of("c.json"), new Profile(UUID.randomUUID(), 0));
        Messenger.local().close();
        Class<?> backends = io.github.imnotstable.msc.stdlib.redis.Redis.class;
        backends = io.github.imnotstable.msc.stdlib.mongo.Mongo.class;
        Format.number(backends.getName().length());
        Durations.parse("1m");
        new Cooldowns<UUID>();
        new RateLimiter<UUID>(1, Duration.ofSeconds(1));
        new Leaderboard<UUID>();
        new EventBus();
        Sequence.of(Stdlib.scheduler()).start();
        Randoms.chance(0.5);
        Text.mm("<green>x");
    }
}
