package io.github.imnotstable.msc.stdlib.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One schema change, applied once per database and namespace. Versions are recorded in {@code msc_migrations}, so
 * several plugins can share one database with their own namespaces. Never edit an applied migration: add the next
 * version.
 *
 * @param version     1, 2, 3... unique within the namespace
 * @param description what it changes
 * @param change      the work, run in a transaction (MySQL and MariaDB commit DDL on their own)
 */
public record Migration(int version, String description, Change change) {
    private static final String TABLE = "msc_migrations";

    /** A migration of SQL statements, run in order. */
    public static Migration of(int version, String description, String... statements) {
        return new Migration(version, description, c -> {
            for (String sql : statements) {
                Database.update(c, sql);
            }
        });
    }

    /** A migration of code, such as copying data between tables. */
    public static Migration of(int version, String description, Change change) {
        return new Migration(version, description, change);
    }

    @FunctionalInterface
    public interface Change {
        void apply(Connection c) throws SQLException;
    }

    /** The migrations applied by this call. */
    static List<Migration> apply(Database db, String namespace, List<Migration> migrations) {
        Set<Integer> seen = new HashSet<>();
        for (Migration m : migrations) {
            if (m.version() < 1 || !seen.add(m.version())) {
                throw new IllegalArgumentException("Migration versions must be unique and from 1: " + m.version());
            }
        }
        db.update(Table.named(TABLE).key("namespace", ColumnType.STRING, 64).key("version", ColumnType.INT)
                .column("description", ColumnType.TEXT).column("applied_at", ColumnType.INSTANT).sql(db.type()));
        Set<Integer> done = new HashSet<>(db.query("SELECT version FROM " + TABLE + " WHERE namespace = ?",
                r -> r.integer("version"), namespace));
        List<Migration> applied = new ArrayList<>();
        for (Migration m : migrations.stream().sorted(Comparator.comparingInt(Migration::version)).toList()) {
            if (done.contains(m.version())) {
                continue;
            }
            db.transaction(c -> {
                m.change().apply(c);
                Database.update(c, "INSERT INTO " + TABLE + " (namespace, version, description, applied_at) VALUES (?, ?, ?, ?)",
                        namespace, m.version(), m.description(), java.time.Instant.now());
                return null;
            });
            applied.add(m);
        }
        return applied;
    }
}
