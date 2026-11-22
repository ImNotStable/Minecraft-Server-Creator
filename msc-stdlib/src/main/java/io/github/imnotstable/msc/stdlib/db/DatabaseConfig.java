package io.github.imnotstable.msc.stdlib.db;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Where and how to connect. Start from {@link #sqlite} or {@link #server} and change fields with the
 * {@code with*} methods.
 *
 * @param type       the backend
 * @param file       SQLite only: the database file
 * @param host       server backends: host name
 * @param port       server backends: port, 0 for the backend's default
 * @param database   server backends: database (schema) name
 * @param user       server backends: user
 * @param password   server backends: password
 * @param url        a full JDBC URL that replaces file/host/port/database, or empty
 * @param poolSize   most open connections; SQLite is held to 1 so writes never meet a locked file
 * @param properties extra driver properties, such as {@code useSSL=false}
 */
public record DatabaseConfig(DatabaseType type, Path file, String host, int port, String database, String user,
                             String password, String url, int poolSize, Map<String, String> properties) {
    public DatabaseConfig {
        Objects.requireNonNull(type, "type");
        file = file == null ? Path.of("data.db") : file;
        host = host == null ? "localhost" : host;
        port = port <= 0 ? type.defaultPort() : port;
        database = database == null ? "minecraft" : database;
        user = user == null ? "" : user;
        password = password == null ? "" : password;
        url = url == null ? "" : url;
        poolSize = type == DatabaseType.SQLITE ? 1 : Math.max(1, poolSize);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /** A SQLite database in {@code file}, created when missing. */
    public static DatabaseConfig sqlite(Path file) {
        return new DatabaseConfig(DatabaseType.SQLITE, file, null, 0, null, null, null, null, 1, null);
    }

    /** An H2 database in {@code file} (H2 adds {@code .mv.db}). */
    public static DatabaseConfig h2(Path file) {
        return new DatabaseConfig(DatabaseType.H2, file, null, 0, null, null, null, null, 4, null);
    }

    /** An H2 database in memory, kept until the pool closes: for tests and throwaway data. */
    public static DatabaseConfig h2Memory(String name) {
        return h2(Path.of(name)).withUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    }

    /** A MySQL, MariaDB or PostgreSQL server, pool of 10. */
    public static DatabaseConfig server(DatabaseType type, String host, int port, String database, String user,
                                        String password) {
        return new DatabaseConfig(type, null, host, port, database, user, password, null, 10, null);
    }

    public DatabaseConfig withPoolSize(int size) {
        return new DatabaseConfig(type, file, host, port, database, user, password, url, size, properties);
    }

    public DatabaseConfig withUrl(String jdbcUrl) {
        return new DatabaseConfig(type, file, host, port, database, user, password, jdbcUrl, poolSize, properties);
    }

    public DatabaseConfig withProperties(Map<String, String> props) {
        return new DatabaseConfig(type, file, host, port, database, user, password, url, poolSize, props);
    }

    /** Never prints the password. */
    @Override
    public String toString() {
        return "DatabaseConfig[" + type + " " + type.url(this) + " user=" + user + " pool=" + poolSize + "]";
    }
}
