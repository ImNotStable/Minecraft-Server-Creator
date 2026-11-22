package io.github.imnotstable.msc.stdlib.db;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryTest {
    @TempDir
    Path dir;

    enum Rank { MEMBER, VIP }

    record Kit(String name, List<String> items) {}

    record Profile(@Id UUID id, @Indexed String name, int coins, long xp, double ratio, boolean banned, Rank rank,
                   Instant lastSeen, List<Kit> kits, Map<String, Integer> stats) {}

    record ProfileV2(@Id UUID id, @Indexed String name, int coins, long xp, double ratio, boolean banned, Rank rank,
                     Instant lastSeen, List<Kit> kits, Map<String, Integer> stats, int wins, String title) {}

    static Database open(DatabaseType type, Path dir) {
        return Database.open(type == DatabaseType.H2 ? DatabaseConfig.h2Memory("repo" + System.nanoTime())
                : DatabaseConfig.sqlite(dir.resolve("data.db")));
    }

    static Profile profile(String name, int coins) {
        return new Profile(UUID.randomUUID(), name, coins, 10L * coins, coins / 3.0, false, Rank.MEMBER,
                Instant.ofEpochMilli(1_700_000_000_000L + coins), List.of(new Kit("pvp", List.of("sword"))),
                Map.of("kills", coins));
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"SQLITE", "H2"})
    void savesFindsQueriesAndDeletesRecords(DatabaseType type) {
        try (Database db = open(type, dir)) {
            Repository<Profile> repo = Repository.of(db, "profiles", Profile.class);
            Profile alex = repo.save(profile("Alex", 50));
            repo.saveAll(List.of(profile("Bo", 10), profile("Cy", 90), profile("Di", 30)));
            assertThat(repo.find(alex.id())).contains(alex);
            assertThat(repo.save(new Profile(alex.id(), "Alex", 55, 1, 1, true, Rank.VIP, null, List.of(), Map.of())))
                    .isNotNull();
            assertThat(repo.find(alex.id()).orElseThrow().rank()).isEqualTo(Rank.VIP);
            assertThat(repo.find(alex.id()).orElseThrow().lastSeen()).isNull();
            assertThat(repo.count()).isEqualTo(4);
            assertThat(repo.findBy("name", "Cy")).hasSize(1);
            assertThat(repo.top("coins", 2)).extracting(Profile::name).containsExactly("Cy", "Alex");
            assertThat(repo.query().where("coins", ">=", 30).orderBy("coins", false).list())
                    .extracting(Profile::name).containsExactly("Di", "Alex", "Cy");
            assertThat(repo.query().where("name", "in", List.of("Bo", "Di")).count()).isEqualTo(2);
            assertThat(repo.query().where("lastSeen", "=", null).count()).isEqualTo(1);
            assertThat(repo.page(1, 2, "coins", true)).extracting(Profile::name).containsExactly("Di", "Bo");
            assertThat(repo.query().orderBy("coins", true).offset(3).list()).extracting(Profile::name).containsExactly("Bo");
            assertThat(repo.increment(alex.id(), "coins", 5)).isTrue();
            assertThat(repo.update(alex.id(), p -> new Profile(p.id(), p.name(), p.coins() * 2, p.xp(), p.ratio(),
                    p.banned(), p.rank(), p.lastSeen(), p.kits(), p.stats()), () -> null).coins()).isEqualTo(120);
            assertThat(repo.delete(alex.id())).isTrue();
            assertThat(repo.exists(alex.id())).isFalse();
            assertThat(repo.query().where("coins", "<", 20).delete()).isEqualTo(1);
            assertThat(repo.async(Repository::count).join()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"SQLITE", "H2"})
    void newComponentsBecomeColumnsWithDefaults(DatabaseType type) {
        try (Database db = open(type, dir)) {
            Profile old = Repository.of(db, "profiles", Profile.class).save(profile("Alex", 5));
            Repository<ProfileV2> v2 = Repository.of(db, "profiles", ProfileV2.class);
            ProfileV2 read = v2.find(old.id()).orElseThrow();
            assertThat(read.wins()).isZero();
            assertThat(read.title()).isNull();
            assertThat(read.kits()).isEqualTo(old.kits());
            assertThat(db.columns("profiles")).contains("wins", "title", "last_seen");
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"SQLITE", "H2"})
    void indexedUniqueFieldsRefuseRepeats(DatabaseType type) {
        record Account(@Id long id, @Indexed(unique = true) String email) {}
        try (Database db = open(type, dir)) {
            Repository<Account> repo = Repository.of(db, "accounts", Account.class);
            repo.save(new Account(1, "a@x"));
            assertThatThrownBy(() -> repo.save(new Account(2, "a@x"))).isInstanceOf(Database.DatabaseException.class);
            assertThatThrownBy(() -> repo.findBy("nope", 1)).hasMessageContaining("email");
            Repository.of(db, "accounts", Account.class); // opening again keeps the index, adds nothing
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseType.class, names = {"SQLITE", "H2"})
    void storesAndHelpersWorkOnEachBackend(DatabaseType type) {
        try (Database db = open(type, dir)) {
            Store<String> s = Store.sql(db, "kv", Codec.string());
            s.put("a", "1");
            s.put("a", "2");
            assertThat(s.get("a")).contains("2");
            db.update(Table.named("logs").id("id").column("msg", ColumnType.TEXT).sql(db.type()));
            assertThat(db.insert("INSERT INTO logs (msg) VALUES (?)", "x")).isEqualTo(1);
            db.script("INSERT INTO logs (msg) VALUES ('y'); INSERT INTO logs (msg) VALUES ('z')");
            assertThat(db.queryAsync("SELECT msg FROM logs ORDER BY id", r -> r.string("msg")).join())
                    .containsExactly("x", "y", "z");
            assertThat(db.tableExists("LOGS")).isTrue();
            assertThat(db.isHealthy()).isTrue();
            assertThat(db.stats().active()).isZero();
        }
    }
}
