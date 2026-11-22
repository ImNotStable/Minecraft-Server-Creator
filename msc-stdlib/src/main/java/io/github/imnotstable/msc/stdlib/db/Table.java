package io.github.imnotstable.msc.stdlib.db;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A {@code CREATE TABLE IF NOT EXISTS} that works on every {@link DatabaseType}.
 *
 * <pre>{@code
 * Table.named("stats").key("player", ColumnType.UUID).column("kills", ColumnType.INT, "0").sql(db.type())
 * Table.named("logs").id("id").column("msg", ColumnType.TEXT).column("at", ColumnType.INSTANT).sql(db.type())
 * }</pre>
 */
public final class Table {
    private final String name;
    private final List<Col> columns = new ArrayList<>();
    private final List<String> keys = new ArrayList<>();
    private final List<List<String>> uniques = new ArrayList<>();
    private String id;

    private record Col(String name, ColumnType type, int length, boolean notNull, String defaultValue) {}

    private Table(String name) {
        this.name = name;
    }

    public static Table named(String name) {
        return new Table(name);
    }

    /** An auto-increment {@code long} primary key (instead of {@link #key}). */
    public Table id(String column) {
        this.id = column;
        return this;
    }

    /** A primary key column; several make a composite key. STRING keys are 255 long. */
    public Table key(String column, ColumnType type) {
        return key(column, type, 255);
    }

    public Table key(String column, ColumnType type, int length) {
        columns.add(new Col(column, type, length, true, null));
        keys.add(column);
        return this;
    }

    /** A nullable column. STRING columns are 255 long. */
    public Table column(String column, ColumnType type) {
        columns.add(new Col(column, type, 255, false, null));
        return this;
    }

    /** A NOT NULL column with a default, given as SQL ({@code "0"}, {@code "'none'"}). */
    public Table column(String column, ColumnType type, String defaultValue) {
        columns.add(new Col(column, type, 255, true, defaultValue));
        return this;
    }

    public Table column(String column, ColumnType type, int length, boolean notNull, String defaultValue) {
        columns.add(new Col(column, type, length, notNull, defaultValue));
        return this;
    }

    /** A UNIQUE constraint over columns. */
    public Table unique(String... cols) {
        uniques.add(List.of(cols));
        return this;
    }

    public String sql(DatabaseType type) {
        if (id != null && !keys.isEmpty()) {
            throw new IllegalStateException("Table " + name + " has both id() and key()");
        }
        List<String> parts = new ArrayList<>();
        if (id != null) {
            parts.add(type.autoIncrementKey(id));
        }
        for (Col c : columns) {
            String def = c.name() + " " + type.sqlType(c.type(), c.length());
            if (c.notNull()) {
                def += " NOT NULL";
            }
            if (c.defaultValue() != null) {
                def += " DEFAULT " + c.defaultValue();
            }
            parts.add(def);
        }
        if (!keys.isEmpty()) {
            parts.add("PRIMARY KEY (" + String.join(", ", keys) + ")");
        }
        uniques.forEach(u -> parts.add("UNIQUE (" + String.join(", ", u) + ")"));
        return "CREATE TABLE IF NOT EXISTS " + name + " (" + parts.stream().collect(Collectors.joining(", ")) + ")";
    }

    /** {@code CREATE INDEX IF NOT EXISTS} on every backend (MySQL has no IF NOT EXISTS, so it is a plain create there). */
    public static String index(DatabaseType type, String index, String table, String... cols) {
        String ifNot = type == DatabaseType.MYSQL ? "" : "IF NOT EXISTS ";
        return "CREATE INDEX " + ifNot + index + " ON " + table + " (" + String.join(", ", cols) + ")";
    }
}
