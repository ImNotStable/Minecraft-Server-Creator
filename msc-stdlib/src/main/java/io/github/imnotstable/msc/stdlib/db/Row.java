package io.github.imnotstable.msc.stdlib.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/** The current row of a query, read with the same conversions {@link Database} binds with. */
public final class Row {
    private final ResultSet rs;

    Row(ResultSet rs) {
        this.rs = rs;
    }

    public String string(String column) throws SQLException {
        return rs.getString(column);
    }

    public int integer(String column) throws SQLException {
        return rs.getInt(column);
    }

    public long longValue(String column) throws SQLException {
        return rs.getLong(column);
    }

    public double doubleValue(String column) throws SQLException {
        return rs.getDouble(column);
    }

    public boolean bool(String column) throws SQLException {
        return rs.getBoolean(column);
    }

    public byte[] bytes(String column) throws SQLException {
        return rs.getBytes(column);
    }

    /** Null when the column is NULL. */
    public UUID uuid(String column) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : UUID.fromString(s.trim());
    }

    /** Null when the column is NULL. */
    public Instant instant(String column) throws SQLException {
        long millis = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(millis);
    }

    /** Null when the column is NULL. */
    public <E extends Enum<E>> E enumValue(String column, Class<E> type) throws SQLException {
        String s = rs.getString(column);
        return s == null ? null : Enum.valueOf(type, s);
    }

    /** True when the last column read was NULL (after {@link #integer}, {@link #longValue} and the like). */
    public boolean wasNull() throws SQLException {
        return rs.wasNull();
    }

    /** The JDBC result set, for anything else. */
    public ResultSet resultSet() {
        return rs;
    }
}
