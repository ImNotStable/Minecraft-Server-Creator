package io.github.imnotstable.msc.stdlib.db;

import com.google.gson.Gson;

import java.lang.reflect.Constructor;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Records in a table, with no SQL to write. The table and its columns follow the record: it is created when missing,
 * new components become new columns, and {@link Indexed} components get indexes. Works on every {@link DatabaseType}.
 *
 * <pre>{@code
 * record Home(@Id UUID id, @Indexed UUID owner, String name, String world, double x, double y, double z) {}
 * Repository<Home> homes = Repository.of(db, "homes", Home.class);
 * homes.save(new Home(UUID.randomUUID(), player, "base", "world", 1, 64, 2));
 * List<Home> mine = homes.findBy("owner", player);
 * List<Home> top = homes.query().where("y", ">", 100).orderBy("y", true).limit(10).list();
 * homes.async(r -> r.findAll()).thenAccept(...);
 * }</pre>
 *
 * Column types: {@code String} (text), {@code int}, {@code long}, {@code double}/{@code float}, {@code boolean},
 * {@link UUID}, {@link Instant}, enums (by name), {@code byte[]}; anything else (lists, maps, other records) is stored
 * as JSON text through Gson. Component names become snake_case columns ({@code lastSeen} is {@code last_seen}); queries
 * take either.
 */
public final class Repository<T extends Record> {
    private static final Gson GSON = io.github.imnotstable.msc.stdlib.util.Json.GSON;

    private final Database db;
    private final String table;
    private final Class<T> type;
    private final List<Field> fields;
    private final Field id;
    private final Constructor<T> constructor;
    private final String upsert;

    /** One record component and its column. */
    record Field(RecordComponent component, String column, ColumnType columnType, int length, boolean json,
                 boolean indexed, boolean unique) {
        Object read(Record r) {
            try {
                return component.getAccessor().invoke(r);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private Repository(Database db, String table, Class<T> type) {
        this.db = db;
        this.table = table;
        this.type = type;
        RecordComponent[] components = type.getRecordComponents();
        if (components.length == 0) {
            throw new IllegalArgumentException(type.getSimpleName() + " has no components to store");
        }
        RecordComponent idComponent = null;
        for (RecordComponent c : components) {
            if (c.isAnnotationPresent(Id.class)) {
                idComponent = c;
            }
        }
        if (idComponent == null) {
            for (RecordComponent c : components) {
                if (c.getName().equals("id")) {
                    idComponent = c;
                }
            }
        }
        if (idComponent == null) {
            idComponent = components[0];
        }
        List<Field> fs = new ArrayList<>();
        Field idField = null;
        for (RecordComponent c : components) {
            Field f = field(c, c == idComponent);
            fs.add(f);
            if (c == idComponent) {
                idField = f;
            }
        }
        this.fields = List.copyOf(fs);
        this.id = idField;
        try {
            this.constructor = type.getDeclaredConstructor(
                    java.util.Arrays.stream(components).map(RecordComponent::getType).toArray(Class<?>[]::new));
            this.constructor.setAccessible(true);
            for (RecordComponent c : components) {
                c.getAccessor().setAccessible(true);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("No canonical constructor on " + type, e);
        }
        this.upsert = db.type().upsert(table, fields.stream().map(Field::column).toList(), List.of(id.column()));
    }

    /** The repository for {@code type} in {@code table}; creates and extends the table to match the record. */
    public static <T extends Record> Repository<T> of(Database db, String table, Class<T> type) {
        Repository<T> r = new Repository<>(db, table, type);
        r.syncSchema();
        return r;
    }

    private static Field field(RecordComponent c, boolean isId) {
        Class<?> t = c.getType();
        Indexed idx = c.getAnnotation(Indexed.class);
        Length len = c.getAnnotation(Length.class);
        boolean keyed = isId || idx != null;
        ColumnType ct;
        boolean json = false;
        if (t == String.class) {
            ct = keyed || len != null ? ColumnType.STRING : ColumnType.TEXT;
        } else if (t == int.class || t == Integer.class || t == short.class || t == Short.class || t == byte.class
                || t == Byte.class) {
            ct = ColumnType.INT;
        } else if (t == long.class || t == Long.class) {
            ct = ColumnType.LONG;
        } else if (t == double.class || t == Double.class || t == float.class || t == Float.class) {
            ct = ColumnType.DOUBLE;
        } else if (t == boolean.class || t == Boolean.class) {
            ct = ColumnType.BOOLEAN;
        } else if (t == UUID.class) {
            ct = ColumnType.UUID;
        } else if (t == Instant.class) {
            ct = ColumnType.INSTANT;
        } else if (t.isEnum()) {
            ct = ColumnType.STRING;
        } else if (t == byte[].class) {
            ct = ColumnType.BYTES;
        } else {
            ct = keyed ? ColumnType.STRING : ColumnType.TEXT;
            json = true;
        }
        int length = len != null ? len.value() : t.isEnum() ? 64 : 255;
        return new Field(c, snake(c.getName()), ct, length, json, idx != null, idx != null && idx.unique());
    }

    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private void syncSchema() {
        db.transaction(c -> {
            Set<String> existing = Schema.columns(c, table);
            if (existing.isEmpty()) {
                Table t = Table.named(table);
                for (Field f : fields) {
                    if (f == id) {
                        t.key(f.column(), f.columnType(), f.length());
                    } else {
                        t.column(f.column(), f.columnType(), f.length(), primitive(f), primitive(f) ? zero(f) : null);
                    }
                }
                Database.update(c, t.sql(db.type()));
            } else {
                for (Field f : fields) {
                    if (!existing.contains(f.column())) {
                        String def = f.column() + " " + db.type().sqlType(f.columnType(), f.length())
                                + (primitive(f) ? " DEFAULT " + zero(f) + " NOT NULL" : "");
                        Database.update(c, "ALTER TABLE " + table + " ADD " + (db.type() == DatabaseType.POSTGRESQL
                                || db.type() == DatabaseType.SQLITE || db.type() == DatabaseType.H2 ? "COLUMN " : "") + def);
                    }
                }
            }
            Set<String> indexes = Schema.indexes(c, table);
            for (Field f : fields) {
                String name = ("idx_" + table + "_" + f.column()).toLowerCase(Locale.ROOT);
                if (f.indexed() && !indexes.contains(name)) {
                    Database.update(c, "CREATE " + (f.unique() ? "UNIQUE " : "") + "INDEX " + name + " ON " + table
                            + " (" + f.column() + ")");
                }
            }
            return null;
        });
    }

    private static boolean primitive(Field f) {
        return f.component().getType().isPrimitive();
    }

    private String zero(Field f) {
        if (f.columnType() == ColumnType.BOOLEAN) {
            return db.type() == DatabaseType.SQLITE ? "0" : "FALSE";
        }
        return "0";
    }

    // ---- writes ----

    /** Inserts or replaces the record with its key. */
    public T save(T record) {
        db.update(upsert, values(record));
        return record;
    }

    /** {@link #save} for many, in one transaction. */
    public void saveAll(Collection<T> records) {
        if (records.isEmpty()) {
            return;
        }
        db.batch(upsert, records.stream().map(this::values).toList());
    }

    /** Deletes the record with this key; true when there was one. */
    public boolean delete(Object id) {
        return db.update("DELETE FROM " + table + " WHERE " + this.id.column() + " = ?", toDb(this.id, id)) > 0;
    }

    public int deleteAll() {
        return db.update("DELETE FROM " + table);
    }

    /**
     * Reads, changes and writes one record in a transaction ({@code initial} when there is none); returns the new one.
     * Two servers changing the same record at once can still overwrite each other on SQLite and H2 files; use
     * {@link #increment} for counters.
     */
    public T update(Object id, UnaryOperator<T> change, Supplier<T> initial) {
        return db.transaction(c -> {
            T current = selectOne(c, " WHERE " + this.id.column() + " = ?", toDb(this.id, id)).orElseGet(initial);
            T next = change.apply(current);
            Database.update(c, upsert, values(next));
            return next;
        });
    }

    /** Adds {@code amount} to a number column of one record in the database itself, safe across servers. */
    public boolean increment(Object id, String field, double amount) {
        Field f = field(field);
        Object delta = f.columnType() == ColumnType.DOUBLE ? (Object) amount : (Object) (long) amount;
        return db.update("UPDATE " + table + " SET " + f.column() + " = " + f.column() + " + ? WHERE " + this.id.column()
                + " = ?", delta, toDb(this.id, id)) > 0;
    }

    // ---- reads ----

    public Optional<T> find(Object id) {
        return db.withConnection(c -> selectOne(c, " WHERE " + this.id.column() + " = ?", toDb(this.id, id)));
    }

    /** The record, or {@code initial} (not saved). */
    public T findOr(Object id, Supplier<T> initial) {
        return find(id).orElseGet(initial);
    }

    public boolean exists(Object id) {
        return query().where(this.id.component().getName(), "=", id).count() > 0;
    }

    public List<T> findAll() {
        return query().list();
    }

    /** Every record whose {@code field} equals {@code value}. */
    public List<T> findBy(String field, Object value) {
        return query().where(field, "=", value).list();
    }

    public Optional<T> findOneBy(String field, Object value) {
        return query().where(field, "=", value).first();
    }

    public long count() {
        return query().count();
    }

    /** Page {@code page} (from 0) of {@code size} records, ordered by {@code field}. */
    public List<T> page(int page, int size, String field, boolean descending) {
        return query().orderBy(field, descending).limit(size).offset((long) page * size).list();
    }

    /** The {@code n} records with the highest {@code field}: leaderboards. */
    public List<T> top(String field, int n) {
        return query().orderBy(field, true).limit(n).list();
    }

    /** A query: {@code where}, {@code orderBy}, {@code limit}, then {@code list}, {@code first}, {@code count} or {@code delete}. */
    public Query query() {
        return new Query();
    }

    /** Runs {@code work} off the calling thread: {@code repo.async(r -> r.find(id))}. */
    public <R> CompletableFuture<R> async(Function<Repository<T>, R> work) {
        return db.async(d -> work.apply(this));
    }

    public String table() {
        return table;
    }

    public Database database() {
        return db;
    }

    /** Builds a query; field names are record component names or columns. */
    public final class Query {
        private final StringBuilder where = new StringBuilder();
        private final List<Object> params = new ArrayList<>();
        private final List<String> order = new ArrayList<>();
        private int limit = -1;
        private long offset;

        private Query() {}

        /**
         * A condition, joined with AND. {@code op} is one of {@code = != < <= > >= like in}; for {@code in} pass a
         * collection. A null value with {@code =} or {@code !=} tests IS NULL / IS NOT NULL.
         */
        public Query where(String field, String op, Object value) {
            return add(" AND ", field, op, value);
        }

        /** A condition joined with OR to everything before it. */
        public Query or(String field, String op, Object value) {
            return add(" OR ", field, op, value);
        }

        private Query add(String join, String field, String op, Object value) {
            Field f = field(field);
            String o = op.trim().toLowerCase(Locale.ROOT);
            if (!where.isEmpty()) {
                where.append(join);
            }
            switch (o) {
                case "=", "!=", "<>" -> {
                    if (value == null) {
                        where.append(f.column()).append(o.equals("=") ? " IS NULL" : " IS NOT NULL");
                        return this;
                    }
                    where.append(f.column()).append(o.equals("=") ? " = ?" : " <> ?");
                    params.add(toDb(f, value));
                }
                case "<", "<=", ">", ">=" -> {
                    where.append(f.column()).append(' ').append(o).append(" ?");
                    params.add(toDb(f, value));
                }
                case "like" -> {
                    where.append(f.column()).append(" LIKE ?");
                    params.add(value);
                }
                case "in" -> {
                    Collection<?> values = (Collection<?>) value;
                    if (values.isEmpty()) {
                        where.append("1 = 0");
                        return this;
                    }
                    where.append(f.column()).append(" IN (")
                            .append(String.join(", ", java.util.Collections.nCopies(values.size(), "?"))).append(')');
                    values.forEach(v -> params.add(toDb(f, v)));
                }
                default -> throw new IllegalArgumentException("Unknown operator '" + op + "' (= != < <= > >= like in)");
            }
            return this;
        }

        public Query orderBy(String field, boolean descending) {
            order.add(field(field).column() + (descending ? " DESC" : " ASC"));
            return this;
        }

        public Query limit(int n) {
            this.limit = n;
            return this;
        }

        public Query offset(long n) {
            this.offset = n;
            return this;
        }

        public List<T> list() {
            return db.withConnection(c -> select(c, tail()));
        }

        public Optional<T> first() {
            int saved = limit;
            limit = 1;
            List<T> one = list();
            limit = saved;
            return one.stream().findFirst();
        }

        public long count() {
            return db.queryOne("SELECT COUNT(*) AS n FROM " + table + whereSql(), r -> r.longValue("n"), params.toArray())
                    .orElse(0L);
        }

        public int delete() {
            return db.update("DELETE FROM " + table + whereSql(), params.toArray());
        }

        public CompletableFuture<List<T>> listAsync() {
            return db.async(d -> list());
        }

        private String whereSql() {
            return where.isEmpty() ? "" : " WHERE " + where;
        }

        private String tail() {
            StringBuilder sql = new StringBuilder(whereSql());
            if (!order.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", order));
            }
            if (limit >= 0) {
                sql.append(" LIMIT ").append(limit);
            }
            if (offset > 0) {
                if (limit < 0) {
                    // MySQL, MariaDB and SQLite need a LIMIT before OFFSET.
                    switch (db.type()) {
                        case MYSQL, MARIADB -> sql.append(" LIMIT 18446744073709551615");
                        case SQLITE -> sql.append(" LIMIT -1");
                        default -> { }
                    }
                }
                sql.append(" OFFSET ").append(offset);
            }
            return sql.toString();
        }

        private List<T> select(Connection c, String tail) throws SQLException {
            return Database.query(c, "SELECT * FROM " + table + tail, Repository.this::map, params.toArray());
        }
    }

    // ---- mapping ----

    private Optional<T> selectOne(Connection c, String where, Object... params) throws SQLException {
        return Database.query(c, "SELECT * FROM " + table + where, this::map, params).stream().findFirst();
    }

    Field field(String name) {
        for (Field f : fields) {
            if (f.component().getName().equals(name) || f.column().equalsIgnoreCase(name)) {
                return f;
            }
        }
        throw new IllegalArgumentException("No field '" + name + "' in " + type.getSimpleName() + " (fields: "
                + fields.stream().map(f -> f.component().getName()).toList() + ")");
    }

    private Object[] values(T record) {
        Object[] out = new Object[fields.size()];
        for (int i = 0; i < out.length; i++) {
            Field f = fields.get(i);
            out[i] = toDb(f, f.read(record));
        }
        return out;
    }

    private static Object toDb(Field f, Object v) {
        if (v == null) {
            return null;
        }
        if (f.json()) {
            return GSON.toJson(v);
        }
        if (v instanceof Float x) {
            return x.doubleValue();
        }
        if (v instanceof Short || v instanceof Byte) {
            return ((Number) v).intValue();
        }
        return v;
    }

    private T map(Row r) throws SQLException {
        Object[] args = new Object[fields.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = fromDb(fields.get(i), r);
        }
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot build " + type.getSimpleName() + " from a row", e);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object fromDb(Field f, Row r) throws SQLException {
        Class<?> t = f.component().getType();
        String col = f.column();
        if (f.json()) {
            String s = r.string(col);
            Type generic = f.component().getGenericType();
            return s == null ? null : GSON.fromJson(s, generic);
        }
        Object v = switch (f.columnType()) {
            case STRING, TEXT -> t.isEnum() ? r.enumValue(col, (Class) t) : r.string(col);
            case INT -> {
                int x = r.integer(col);
                yield r.wasNull() && !t.isPrimitive() ? null : t == short.class || t == Short.class ? (Object) (short) x
                        : t == byte.class || t == Byte.class ? (Object) (byte) x : (Object) x;
            }
            case LONG -> {
                long x = r.longValue(col);
                yield r.wasNull() && !t.isPrimitive() ? null : x;
            }
            case DOUBLE -> {
                double x = r.doubleValue(col);
                yield r.wasNull() && !t.isPrimitive() ? null : t == float.class || t == Float.class ? (Object) (float) x
                        : (Object) x;
            }
            case BOOLEAN -> {
                boolean x = r.bool(col);
                yield r.wasNull() && !t.isPrimitive() ? null : x;
            }
            case UUID -> r.uuid(col);
            case INSTANT -> r.instant(col);
            case BYTES -> r.bytes(col);
        };
        return v;
    }

    /** Columns by component name, for building your own SQL against the table. */
    public Map<String, String> columns() {
        Map<String, String> out = new LinkedHashMap<>();
        fields.forEach(f -> out.put(f.component().getName(), f.column()));
        return out;
    }
}
