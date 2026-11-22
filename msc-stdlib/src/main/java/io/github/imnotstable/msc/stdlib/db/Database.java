package io.github.imnotstable.msc.stdlib.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A pooled connection to SQLite, MySQL, MariaDB or PostgreSQL.
 *
 * <pre>{@code
 * Database db = Database.open(DatabaseConfig.sqlite(dataFolder.resolve("data.db")));
 * db.migrate("myplugin", List.of(Migration.of(1, "homes",
 *         Table.named("homes").key("owner", ColumnType.UUID).key("name", ColumnType.STRING)
 *                 .column("world", ColumnType.STRING).column("x", ColumnType.DOUBLE).sql(db.type()))));
 * db.update("INSERT INTO homes (owner, name, world, x) VALUES (?, ?, ?, ?)", id, "base", "world", 10.5);
 * List<String> names = db.query("SELECT name FROM homes WHERE owner = ?", r -> r.string("name"), id);
 * db.async(d -> d.query(...)).thenAccept(...);   // off the game thread
 * }</pre>
 *
 * Calls block: on a game server run them through {@link #async} (and hop back to the main thread for game
 * changes). Parameters bind {@link UUID} as text, {@link Instant} as epoch millis, enums by name and
 * {@code null} as SQL NULL.
 */
public final class Database implements AutoCloseable {
    private final DatabaseConfig config;
    private final HikariDataSource pool;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private Database(DatabaseConfig config, HikariDataSource pool) {
        this.config = config;
        this.pool = pool;
    }

    /** Opens the pool; for SQLite creates the file's folder. */
    public static Database open(DatabaseConfig config) {
        try {
            Class.forName(config.type().driver());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("No JDBC driver " + config.type().driver() + " for " + config.type()
                    + ": add it to the server or the plugin", e);
        }
        if (config.type() == DatabaseType.SQLITE && config.url().isBlank()) {
            Path parent = config.file().toAbsolutePath().getParent();
            try {
                Files.createDirectories(parent);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Cannot create " + parent, e);
            }
        }
        HikariConfig h = new HikariConfig();
        h.setJdbcUrl(config.type().url(config));
        h.setDriverClassName(config.type().driver());
        if (!config.user().isEmpty()) {
            h.setUsername(config.user());
            h.setPassword(config.password());
        }
        h.setMaximumPoolSize(config.poolSize());
        h.setPoolName("msc-stdlib-" + config.type().name().toLowerCase(java.util.Locale.ROOT));
        config.properties().forEach(h::addDataSourceProperty);
        return new Database(config, new HikariDataSource(h));
    }

    public DatabaseType type() {
        return config.type();
    }

    public DatabaseConfig config() {
        return config;
    }

    /** Runs INSERT/UPDATE/DELETE/DDL; returns the changed row count. */
    public int update(String sql, Object... params) {
        return withConnection(c -> update(c, sql, params));
    }

    /** Runs an INSERT and returns the generated key (an auto-increment id). */
    public long insert(String sql, Object... params) {
        return withConnection(c -> {
            try (PreparedStatement s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                bind(s, params);
                s.executeUpdate();
                try (ResultSet keys = s.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("No generated key for: " + sql);
                    }
                    return keys.getLong(1);
                }
            }
        });
    }

    /** Runs one statement for each parameter row, in one transaction. */
    public int[] batch(String sql, List<Object[]> rows) {
        return transaction(c -> {
            try (PreparedStatement s = c.prepareStatement(sql)) {
                for (Object[] row : rows) {
                    bind(s, row);
                    s.addBatch();
                }
                return s.executeBatch();
            }
        });
    }

    /** Every row of a SELECT, mapped. */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        return withConnection(c -> query(c, sql, mapper, params));
    }

    /** The first row of a SELECT, if any. */
    public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        List<T> rows = query(sql, mapper, params);
        return rows.isEmpty() ? Optional.empty() : Optional.ofNullable(rows.getFirst());
    }

    /** Work on one connection in a transaction: committed when {@code work} returns, rolled back when it throws. */
    public <T> T transaction(SqlFunction<Connection, T> work) {
        return withConnection(c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (Throwable t) {
                c.rollback();
                throw t;
            } finally {
                c.setAutoCommit(auto);
            }
        });
    }

    /** Work on a pooled connection, without a transaction. */
    public <T> T withConnection(SqlFunction<Connection, T> work) {
        try (Connection c = pool.getConnection()) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    /** Runs {@code work} off the calling thread. */
    public <T> CompletableFuture<T> async(java.util.function.Function<Database, T> work) {
        return CompletableFuture.supplyAsync(() -> work.apply(this), executor);
    }

    /** Applies the migrations of {@code namespace} not applied yet, in version order. See {@link Migration}. */
    public List<Migration> migrate(String namespace, List<Migration> migrations) {
        return Migration.apply(this, namespace, migrations);
    }

    /** Statement helpers for use inside {@link #transaction}. */
    public static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, params);
            return s.executeUpdate();
        }
    }

    public static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            bind(s, params);
            try (ResultSet rs = s.executeQuery()) {
                List<T> out = new ArrayList<>();
                Row row = new Row(rs);
                while (rs.next()) {
                    out.add(mapper.map(row));
                }
                return out;
            }
        }
    }

    static void bind(PreparedStatement s, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            switch (p) {
                case null -> s.setNull(i + 1, Types.NULL);
                case UUID u -> s.setString(i + 1, u.toString());
                case Instant t -> s.setLong(i + 1, t.toEpochMilli());
                case Enum<?> e -> s.setString(i + 1, e.name());
                case Boolean b -> s.setBoolean(i + 1, b);
                default -> s.setObject(i + 1, p);
            }
        }
    }

    /** Closes the pool and waits for running {@link #async} work. */
    @Override
    public void close() {
        executor.close();
        pool.close();
    }

    /** Maps the current row. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(Row row) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlFunction<A, T> {
        T apply(A a) throws SQLException;
    }

    /** An {@link SQLException} from a database call, unchecked. */
    public static final class DatabaseException extends RuntimeException {
        DatabaseException(SQLException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
