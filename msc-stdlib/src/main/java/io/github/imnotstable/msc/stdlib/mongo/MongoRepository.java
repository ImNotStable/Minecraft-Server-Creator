package io.github.imnotstable.msc.stdlib.mongo;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import io.github.imnotstable.msc.stdlib.db.Id;
import io.github.imnotstable.msc.stdlib.db.Indexed;
import io.github.imnotstable.msc.stdlib.util.Json;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Records as MongoDB documents, the same calls as the SQL {@code Repository}: the key ({@link Id}, else {@code id},
 * else the first component) is {@code _id}, {@link Indexed} components get indexes. Values go through {@link Json}, so
 * UUIDs and times are text. {@link #collection()} and {@link #find(Bson)} take any driver filter
 * ({@code Filters.gt("coins", 10)}).
 */
public final class MongoRepository<T extends Record> {
    private final MongoCollection<Document> collection;
    private final Class<T> type;
    private final String idName;
    private final RecordComponent idComponent;

    MongoRepository(MongoCollection<Document> collection, Class<T> type) {
        this.collection = collection;
        this.type = type;
        RecordComponent[] cs = type.getRecordComponents();
        RecordComponent id = null;
        for (RecordComponent c : cs) {
            if (c.isAnnotationPresent(Id.class)) {
                id = c;
            }
        }
        for (RecordComponent c : cs) {
            if (id == null && c.getName().equals("id")) {
                id = c;
            }
        }
        this.idComponent = id == null ? cs[0] : id;
        this.idName = idComponent.getName();
        idComponent.getAccessor().setAccessible(true);
        for (RecordComponent c : cs) {
            Indexed idx = c.getAnnotation(Indexed.class);
            if (idx != null) {
                collection.createIndex(Indexes.ascending(c.getName()), new IndexOptions().unique(idx.unique()));
            }
        }
    }

    public T save(T record) {
        Document d = Document.parse(Json.write(record));
        Object id = d.remove(idName);
        d.put("_id", id);
        collection.replaceOne(Filters.eq("_id", id), d, new ReplaceOptions().upsert(true));
        return record;
    }

    public void saveAll(List<T> records) {
        records.forEach(this::save);
    }

    public Optional<T> find(Object id) {
        return Optional.ofNullable(collection.find(Filters.eq("_id", key(id))).first()).map(this::map);
    }

    public T findOr(Object id, Supplier<T> initial) {
        return find(id).orElseGet(initial);
    }

    public List<T> findAll() {
        return find(new Document());
    }

    public List<T> findBy(String field, Object value) {
        return find(Filters.eq(field.equals(idName) ? "_id" : field, key(value)));
    }

    /** Records matching any driver filter. */
    public List<T> find(Bson filter) {
        List<T> out = new ArrayList<>();
        collection.find(filter).forEach(d -> out.add(map(d)));
        return out;
    }

    /** The {@code n} records with the highest {@code field}. */
    public List<T> top(String field, int n) {
        List<T> out = new ArrayList<>();
        collection.find().sort(Sorts.descending(field)).limit(n).forEach(d -> out.add(map(d)));
        return out;
    }

    public boolean delete(Object id) {
        return collection.deleteOne(Filters.eq("_id", key(id))).getDeletedCount() > 0;
    }

    public long count() {
        return collection.countDocuments();
    }

    /** Adds {@code amount} to a number field in the database itself, safe across servers. */
    public boolean increment(Object id, String field, double amount) {
        Number n = amount == Math.rint(amount) ? (Number) (long) amount : (Number) amount;
        return collection.updateOne(Filters.eq("_id", key(id)), Updates.inc(field, n)).getModifiedCount() > 0;
    }

    /** Read, change, write (not atomic across servers; use {@link #increment} for counters). */
    public T update(Object id, UnaryOperator<T> change, Supplier<T> initial) {
        return save(change.apply(findOr(id, initial)));
    }

    public <R> CompletableFuture<R> async(Function<MongoRepository<T>, R> work) {
        return CompletableFuture.supplyAsync(() -> work.apply(this), java.util.concurrent.ForkJoinPool.commonPool());
    }

    public MongoCollection<Document> collection() {
        return collection;
    }

    /** Keys stored as their JSON form: UUIDs and enums as text. */
    private static Object key(Object id) {
        return id instanceof Number || id instanceof String || id instanceof Boolean ? id : Json.read(Json.write(id), Object.class);
    }

    private T map(Document d) {
        Document copy = new Document(d);
        copy.put(idName, copy.remove("_id"));
        return Json.read(copy.toJson(), type);
    }
}
