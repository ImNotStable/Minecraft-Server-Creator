package io.github.imnotstable.msc.stdlib.mongo;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.ReplaceOptions;
import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Store;
import org.bson.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MongoDB for stores and record repositories. Needs {@code org.mongodb:mongodb-driver-sync} in the project.
 *
 * <pre>{@code
 * Mongo mongo = Mongo.connect("mongodb://localhost:27017", "minecraft");
 * MongoRepository<Profile> profiles = mongo.repository("profiles", Profile.class);
 * Store<Kit> kits = mongo.store("kits", Codec.json(Kit.class));
 * }</pre>
 */
public final class Mongo implements AutoCloseable {
    private final MongoClient client;
    private final MongoDatabase database;

    private Mongo(MongoClient client, String database) {
        this.client = client;
        this.database = client.getDatabase(database);
    }

    public static Mongo connect(String uri, String database) {
        return new Mongo(MongoClients.create(uri), database);
    }

    /** The driver's database, for anything else. */
    public MongoDatabase database() {
        return database;
    }

    public MongoCollection<Document> collection(String name) {
        return database.getCollection(name);
    }

    /** Values as documents in {@code collection}, {@code _id} the key. JSON codecs keep the value's own fields. */
    public <V> Store<V> store(String collection, Codec<V> codec) {
        MongoCollection<Document> c = collection(collection);
        return new Store<>() {
            @Override
            public Optional<V> get(String key) {
                Document d = c.find(Filters.eq("_id", key)).first();
                return Optional.ofNullable(d).map(Mongo::withoutId).map(json -> codec.decode(json));
            }

            @Override
            public void put(String key, V value) {
                c.replaceOne(Filters.eq("_id", key), toDocument(codec.encode(value)).append("_id", key),
                        new ReplaceOptions().upsert(true));
            }

            @Override
            public boolean remove(String key) {
                return c.deleteOne(Filters.eq("_id", key)).getDeletedCount() > 0;
            }

            @Override
            public List<String> keys() {
                List<String> out = new ArrayList<>();
                c.find().projection(new Document("_id", 1)).forEach(d -> out.add(String.valueOf(d.get("_id"))));
                return out;
            }

            @Override
            public Map<String, V> all() {
                Map<String, V> out = new LinkedHashMap<>();
                c.find().forEach(d -> out.put(String.valueOf(d.get("_id")), codec.decode(withoutId(d))));
                return out;
            }
        };
    }

    /** Records in {@code collection}, see {@link MongoRepository}. */
    public <T extends Record> MongoRepository<T> repository(String collection, Class<T> type) {
        return new MongoRepository<>(collection(collection), type);
    }

    /** A JSON value as a document; a value that is not a JSON object goes under {@code value}. */
    static Document toDocument(String json) {
        String t = json.strip();
        return t.startsWith("{") ? Document.parse(t) : Document.parse("{\"value\":" + t + "}");
    }

    static String withoutId(Document d) {
        Document copy = new Document(d);
        copy.remove("_id");
        if (copy.size() == 1 && copy.containsKey("value")) {
            return Document.parse(copy.toJson()).toJson().replaceFirst("^\\{\\s*\"value\"\\s*:\\s*", "")
                    .replaceFirst("}\\s*$", "");
        }
        return copy.toJson();
    }

    @Override
    public void close() {
        client.close();
    }
}
