package io.github.imnotstable.msc.stdlib.redis;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.messaging.Messenger;
import io.github.imnotstable.msc.stdlib.util.Json;
import redis.clients.jedis.RedisClient;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.resps.ScanResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Redis for stores with expiry, cross-server messaging, counters and locks. Needs {@code redis.clients:jedis} in the
 * project (the guide shows the line).
 *
 * <pre>{@code
 * Redis redis = Redis.connect("redis://localhost:6379");
 * Store<Party> parties = redis.store("parties", Codec.json(Party.class), Duration.ofHours(1));
 * Messenger net = redis.messenger();
 * long online = redis.increment("online", 1);
 * try (var lock = redis.lock("arena-1", Duration.ofSeconds(30)).orElseThrow()) { ... }
 * }</pre>
 */
public final class Redis implements AutoCloseable {
    private final RedisClient jedis;

    private Redis(RedisClient jedis) {
        this.jedis = jedis;
    }

    /** {@code redis://[user:password@]host:port[/db]}. */
    public static Redis connect(String uri) {
        return new Redis(RedisClient.create(uri));
    }

    /** The Jedis client, for anything else Redis does (sorted sets, streams, hashes...). */
    public RedisClient jedis() {
        return jedis;
    }

    /** Values under {@code prefix:key}, kept forever. */
    public <V> Store<V> store(String prefix, Codec<V> codec) {
        return store(prefix, codec, null);
    }

    /** Values under {@code prefix:key}; each write lives {@code ttl} (null: forever). */
    public <V> Store<V> store(String prefix, Codec<V> codec, Duration ttl) {
        String p = prefix + ":";
        return new Store<>() {
            @Override
            public Optional<V> get(String key) {
                return Optional.ofNullable(jedis.get(p + key)).map(codec::decode);
            }

            @Override
            public void put(String key, V value) {
                if (ttl == null) {
                    jedis.set(p + key, codec.encode(value));
                } else {
                    jedis.set(p + key, codec.encode(value), SetParams.setParams().px(ttl.toMillis()));
                }
            }

            @Override
            public boolean remove(String key) {
                return jedis.del(p + key) > 0;
            }

            @Override
            public List<String> keys() {
                List<String> out = new ArrayList<>();
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams params = new ScanParams().match(p + "*").count(500);
                do {
                    ScanResult<String> page = jedis.scan(cursor, params);
                    page.getResult().forEach(k -> out.add(k.substring(p.length())));
                    cursor = page.getCursor();
                } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
                return out;
            }

            @Override
            public Map<String, V> all() {
                Map<String, V> out = new LinkedHashMap<>();
                keys().forEach(k -> get(k).ifPresent(v -> out.put(k, v)));
                return out;
            }
        };
    }

    /** Adds {@code by} to a counter (created at 0) and returns the new value; atomic across servers. */
    public long increment(String key, long by) {
        return jedis.incrBy(key, by);
    }

    /**
     * A lock only one holder across all servers gets, released on close or after {@code ttl} (so a crashed server
     * cannot hold it forever). Empty when someone else holds it.
     */
    public Optional<Lock> lock(String name, Duration ttl) {
        String token = UUID.randomUUID().toString();
        String key = "lock:" + name;
        String ok = jedis.set(key, token, SetParams.setParams().nx().px(ttl.toMillis()));
        if (!"OK".equals(ok)) {
            return Optional.empty();
        }
        return Optional.of(() -> jedis.eval(
                "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                List.of(key), List.of(token)));
    }

    /** A held {@link #lock}. */
    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }

    /** Pub/sub over this Redis, one listening connection for every channel. Close it on shutdown. */
    public Messenger messenger() {
        Map<String, List<Consumer<String>>> handlers = new ConcurrentHashMap<>();
        JedisPubSub pubSub = new JedisPubSub() {
            @Override
            public void onMessage(String channel, String message) {
                handlers.getOrDefault(channel, List.of()).forEach(h -> h.accept(message));
            }
        };
        Thread listener = Thread.ofPlatform().daemon().name("msc-stdlib-redis-messenger").unstarted(() -> {
            try {
                jedis.subscribe(pubSub, "msc-stdlib:keepalive");
            } catch (RuntimeException e) {
                // Closed: unsubscribe ends subscribe() normally; anything else ends the listener.
            }
        });
        listener.start();
        return new Messenger() {
            @Override
            public void publish(String channel, Object message) {
                jedis.publish(channel, Json.write(message));
            }

            @Override
            public <T> Subscription subscribe(String channel, Class<T> type, Consumer<T> handler) {
                Consumer<String> h = json -> handler.accept(Json.read(json, type));
                boolean first = handlers.computeIfAbsent(channel, c -> new CopyOnWriteArrayList<>()).isEmpty();
                handlers.get(channel).add(h);
                if (first) {
                    awaitSubscribed(pubSub);
                    pubSub.subscribe(channel);
                }
                return () -> handlers.getOrDefault(channel, List.of()).remove(h);
            }

            @Override
            public void close() {
                if (pubSub.isSubscribed()) {
                    pubSub.unsubscribe();
                }
            }
        };
    }

    private static void awaitSubscribed(JedisPubSub pubSub) {
        for (int i = 0; i < 200 && !pubSub.isSubscribed(); i++) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Override
    public void close() {
        jedis.close();
    }
}
