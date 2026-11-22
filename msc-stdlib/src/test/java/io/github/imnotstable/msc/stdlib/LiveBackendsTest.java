package io.github.imnotstable.msc.stdlib;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.DatabaseConfig;
import io.github.imnotstable.msc.stdlib.db.DatabaseType;
import io.github.imnotstable.msc.stdlib.db.Id;
import io.github.imnotstable.msc.stdlib.db.Indexed;
import io.github.imnotstable.msc.stdlib.db.Repository;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.economy.Economy;
import io.github.imnotstable.msc.stdlib.messaging.Messenger;
import io.github.imnotstable.msc.stdlib.mongo.Mongo;
import io.github.imnotstable.msc.stdlib.mongo.MongoRepository;
import io.github.imnotstable.msc.stdlib.redis.Redis;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The backends CI cannot run, against real servers. Set {@code MSC_STDLIB_LIVE} to {@code name=url} pairs joined by
 * spaces, for example {@code mariadb=jdbc:mariadb://localhost:3306/test?user=root&password=x
 * postgresql=jdbc:postgresql://localhost:5432/test?user=postgres&password=x redis=redis://localhost:6379
 * mongo=mongodb://localhost:27017}. Names left out are skipped.
 */
class LiveBackendsTest {
    static final Map<String, String> LIVE = new HashMap<>();

    static {
        String env = System.getenv("MSC_STDLIB_LIVE");
        if (env != null) {
            for (String pair : env.trim().split("\\s+")) {
                int eq = pair.indexOf('=');
                LIVE.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
        }
    }

    enum Tier { BRONZE, GOLD }

    record Player(@Id UUID id, @Indexed String name, int coins, Tier tier, Instant seen, List<String> tags) {}

    static String live(String name) {
        Assumptions.assumeTrue(LIVE.containsKey(name), "MSC_STDLIB_LIVE has no " + name);
        return LIVE.get(name);
    }

    void sqlRoundTrip(DatabaseType type, String url) {
        try (Database db = Database.open(DatabaseConfig.server(type, "unused", 0, "unused", null, null).withUrl(url))) {
            String table = "live_players_" + System.nanoTime();
            Repository<Player> repo = Repository.of(db, table, Player.class);
            Player p = new Player(UUID.randomUUID(), "Alex", 5, Tier.GOLD, Instant.ofEpochMilli(123), List.of("a"));
            repo.save(p);
            repo.save(new Player(p.id(), "Alex", 7, Tier.GOLD, p.seen(), p.tags()));
            repo.saveAll(List.of(new Player(UUID.randomUUID(), "Bo", 9, Tier.BRONZE, null, List.of())));
            assertThat(repo.find(p.id()).orElseThrow().coins()).isEqualTo(7);
            assertThat(repo.top("coins", 1).getFirst().name()).isEqualTo("Bo");
            assertThat(repo.query().where("tier", "=", Tier.GOLD).count()).isEqualTo(1);
            assertThat(repo.query().orderBy("coins", true).offset(1).list()).extracting(Player::name).containsExactly("Alex");
            assertThat(repo.increment(p.id(), "coins", 3)).isTrue();
            assertThat(Repository.of(db, table, Player.class).find(p.id()).orElseThrow().coins()).isEqualTo(10);
            Store<String> kv = Store.sql(db, table + "_kv", Codec.string());
            kv.put("k", "1");
            kv.put("k", "2");
            assertThat(kv.get("k")).contains("2");
            Economy eco = Economy.sql(db, table + "_eco", "$", 100);
            UUID a = UUID.randomUUID(), b = UUID.randomUUID();
            eco.deposit(a, 500);
            assertThat(eco.transfer(a, b, 200)).isTrue();
            assertThat(eco.withdraw(b, 201)).isFalse();
            db.update("DROP TABLE " + table);
            db.update("DROP TABLE " + table + "_kv");
            db.update("DROP TABLE " + table + "_eco");
        }
    }

    @Test
    void mariadb() {
        sqlRoundTrip(DatabaseType.MARIADB, live("mariadb"));
    }

    @Test
    void postgresql() {
        sqlRoundTrip(DatabaseType.POSTGRESQL, live("postgresql"));
    }

    @Test
    void mongo() {
        try (Mongo mongo = Mongo.connect(live("mongo"), "msc_stdlib_live")) {
            MongoRepository<Player> repo = mongo.repository("players_" + System.nanoTime(), Player.class);
            Player p = new Player(UUID.randomUUID(), "Alex", 5, Tier.GOLD, Instant.ofEpochMilli(123), List.of("a"));
            repo.save(p);
            assertThat(repo.find(p.id())).contains(p);
            assertThat(repo.increment(p.id(), "coins", 2)).isTrue();
            assertThat(repo.findBy("name", "Alex").getFirst().coins()).isEqualTo(7);
            Store<Chat> s = mongo.store("kv_" + System.nanoTime(), Codec.json(Chat.class));
            s.put("x", new Chat("hello"));
            assertThat(s.get("x")).contains(new Chat("hello"));
            Store<String> plain = mongo.store("kv_" + System.nanoTime(), Codec.string());
            plain.put("y", "\"text\"");
            assertThat(plain.keys()).containsExactly("y");
            repo.collection().drop();
        }
    }

    record Chat(String text) {}

    @Test
    void redis() throws Exception {
        try (Redis redis = Redis.connect(live("redis"))) {
            Store<String> s = redis.store("live" + System.nanoTime(), Codec.string(), Duration.ofSeconds(30));
            s.put("a", "1");
            assertThat(s.get("a")).contains("1");
            assertThat(s.keys()).containsExactly("a");
            assertThat(redis.increment("live-counter-" + System.nanoTime(), 3)).isEqualTo(3);
            var lock = redis.lock("live-lock-" + System.nanoTime(), Duration.ofSeconds(5));
            assertThat(lock).isPresent();
            lock.get().close();
            try (Messenger m = redis.messenger()) {
                CompletableFuture<Chat> got = new CompletableFuture<>();
                m.subscribe("live-chat", Chat.class, got::complete);
                Thread.sleep(200);
                m.publish("live-chat", new Chat("hi"));
                assertThat(got.get(5, TimeUnit.SECONDS)).isEqualTo(new Chat("hi"));
            }
        }
    }
}
