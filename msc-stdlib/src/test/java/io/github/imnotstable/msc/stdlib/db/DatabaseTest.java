package io.github.imnotstable.msc.stdlib.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTest {
    @TempDir
    Path dir;
    Database db;

    @BeforeEach
    void open() {
        db = Database.open(DatabaseConfig.sqlite(dir.resolve("sub/data.db")));
    }

    @AfterEach
    void close() {
        db.close();
    }

    enum Rank { MEMBER, ADMIN }

    @Test
    void migratesOnceAndRoundTripsEveryType() {
        List<Migration> ms = List.of(
                Migration.of(1, "players", Table.named("players").key("id", ColumnType.UUID)
                        .column("name", ColumnType.STRING).column("coins", ColumnType.LONG, "0")
                        .column("vip", ColumnType.BOOLEAN, "0").column("seen", ColumnType.INSTANT)
                        .column("rank", ColumnType.STRING).column("score", ColumnType.DOUBLE).sql(db.type())),
                Migration.of(2, "index", Table.index(db.type(), "players_name", "players", "name")));
        assertThat(db.migrate("test", ms)).hasSize(2);
        assertThat(db.migrate("test", ms)).isEmpty();
        assertThat(db.migrate("other", ms.subList(0, 1))).hasSize(1); // its own namespace, IF NOT EXISTS

        UUID id = UUID.randomUUID();
        Instant seen = Instant.ofEpochMilli(1_700_000_000_123L);
        db.update("INSERT INTO players (id, name, coins, vip, seen, rank, score) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, "Steve", 5L, true, seen, Rank.ADMIN, 2.5);
        record P(UUID id, String name, long coins, boolean vip, Instant seen, Rank rank, double score) {}
        P p = db.queryOne("SELECT * FROM players WHERE id = ?", r -> new P(r.uuid("id"), r.string("name"),
                r.longValue("coins"), r.bool("vip"), r.instant("seen"), r.enumValue("rank", Rank.class),
                r.doubleValue("score")), id).orElseThrow();
        assertThat(p).isEqualTo(new P(id, "Steve", 5, true, seen, Rank.ADMIN, 2.5));
    }

    @Test
    void insertReturnsIdsAndAFailedTransactionRollsBack() {
        db.update(Table.named("logs").id("id").column("msg", ColumnType.TEXT).sql(db.type()));
        assertThat(db.insert("INSERT INTO logs (msg) VALUES (?)", "a")).isEqualTo(1);
        assertThat(db.insert("INSERT INTO logs (msg) VALUES (?)", "b")).isEqualTo(2);
        assertThatThrownBy(() -> db.transaction(c -> {
            Database.update(c, "INSERT INTO logs (msg) VALUES (?)", "c");
            throw new java.sql.SQLException("boom");
        })).isInstanceOf(Database.DatabaseException.class);
        assertThat(db.query("SELECT msg FROM logs", r -> r.string("msg"))).containsExactly("a", "b");
        db.batch("INSERT INTO logs (msg) VALUES (?)", List.of(new Object[]{"d"}, new Object[]{"e"}));
        assertThat(db.async(d -> d.query("SELECT msg FROM logs", r -> r.string("msg"))).join()).hasSize(4);
    }

    @Test
    void aStoreKeepsValuesAsJson() {
        record Profile(int coins, List<String> kits) {}
        Store<Profile> s = Store.sql(db, "profiles", Codec.json(Profile.class));
        s.put("a", new Profile(1, List.of("pvp")));
        s.put("a", new Profile(2, List.of()));
        s.update("b", p -> new Profile(p.coins() + 1, p.kits()), () -> new Profile(0, List.of()));
        assertThat(s.get("a")).contains(new Profile(2, List.of()));
        assertThat(s.all()).containsOnlyKeys("a", "b");
        assertThat(s.remove("a")).isTrue();
        assertThat(s.keys()).containsExactly("b");
        assertThat(Store.sql(db, "profiles", Codec.json(Profile.class)).get("b")).contains(new Profile(1, List.of()));
    }

    @Test
    void aMissingDriverIsNamed() {
        assertThatThrownBy(() -> Database.open(DatabaseConfig.server(DatabaseType.MYSQL, "h", 0, "d", "u", "p")))
                .hasMessageContaining("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void eachBackendGetsItsOwnSql() {
        List<String> cols = List.of("k", "v");
        assertThat(DatabaseType.POSTGRESQL.upsert("t", cols, List.of("k")))
                .isEqualTo("INSERT INTO t (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v");
        assertThat(DatabaseType.MARIADB.upsert("t", cols, List.of("k")))
                .isEqualTo("INSERT INTO t (k, v) VALUES (?, ?) ON DUPLICATE KEY UPDATE v = VALUES(v)");
        assertThat(Table.named("t").id("id").column("b", ColumnType.BYTES).sql(DatabaseType.POSTGRESQL))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t (id BIGSERIAL PRIMARY KEY, b BYTEA)");
        assertThat(Table.named("t").key("k", ColumnType.STRING, 16).column("v", ColumnType.TEXT).sql(DatabaseType.MYSQL))
                .isEqualTo("CREATE TABLE IF NOT EXISTS t (k VARCHAR(16) NOT NULL, v LONGTEXT, PRIMARY KEY (k))");
        DatabaseConfig c = DatabaseConfig.server(DatabaseType.MYSQL, "db", 0, "mc", "root", "secret");
        assertThat(c.type().url(c)).isEqualTo("jdbc:mysql://db:3306/mc");
        assertThat(c.toString()).doesNotContain("secret");
    }
}
