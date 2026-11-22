package io.github.imnotstable.msc.stdlib.db;

/**
 * Portable column types; {@link DatabaseType#sqlType} maps each to the backend's own. Values bind and read as:
 * STRING/TEXT {@code String}, INT {@code int}, LONG {@code long}, DOUBLE {@code double}, BOOLEAN {@code boolean},
 * UUID {@code java.util.UUID} (stored as text), INSTANT {@code java.time.Instant} (stored as epoch millis),
 * BYTES {@code byte[]}.
 */
public enum ColumnType {
    /** Text up to a length; the only text type that can be a key. */
    STRING,
    /** Text of any length. */
    TEXT,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    UUID,
    INSTANT,
    BYTES
}
