package io.github.imnotstable.msc.stdlib;

import io.github.imnotstable.msc.stdlib.cache.Cache;
import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.db.WriteBehind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StoresAndCachesTest {
    @TempDir
    Path dir;

    static final class TestClock extends Clock {
        Instant now = Instant.EPOCH;

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    record Kit(String name, Instant made) {}

    @Test
    void jsonFilesKeepOneReadableFilePerKey() throws Exception {
        Store<Kit> kits = Store.jsonFiles(dir, Codec.json(Kit.class));
        kits.put("pvp/basic", new Kit("Basic", Instant.EPOCH));
        assertThat(Files.readString(dir.resolve("pvp_basic.json"))).contains("\"1970-01-01T00:00:00Z\"");
        assertThat(kits.get("pvp/basic")).contains(new Kit("Basic", Instant.EPOCH));
        assertThat(kits.keys()).containsExactly("pvp_basic");
        assertThat(kits.remove("pvp/basic")).isTrue();
        assertThat(kits.all()).isEmpty();
    }

    @Test
    void aCacheExpiresEvictsAndLoads() {
        TestClock clock = new TestClock();
        List<String> removed = new ArrayList<>();
        Cache<String, Integer> c = Cache.<String, Integer>builder().maxSize(2).expireAfterWrite(Duration.ofSeconds(10))
                .loader(String::length).onRemove((k, v, cause) -> removed.add(k + ":" + cause)).clock(clock).build();
        assertThat(c.get("abc")).isEqualTo(3);
        c.get("de");
        c.get("abc");          // touch abc, so de is the least recently used
        c.put("f", 1);
        assertThat(removed).containsExactly("de:SIZE");
        clock.now = clock.now.plusSeconds(11);
        assertThat(c.getIfPresent("abc")).isEmpty();
        assertThat(removed).contains("abc:EXPIRED");
        assertThat(c.stats().hits()).isEqualTo(1);
    }

    @Test
    void cachedAndWriteBehindStoresWriteThrough() {
        Store<String> backing = Store.memory();
        Store<String> cached = Store.cached(backing, Cache.<String, String>builder().maxSize(10).build());
        cached.put("a", "1");
        backing.put("a", "changed behind the cache");
        assertThat(cached.get("a")).contains("1");

        try (WriteBehind<String> wb = Store.writeBehind(backing, Duration.ofHours(1))) {
            wb.put("b", "2");
            wb.remove("a");
            assertThat(wb.get("b")).contains("2");
            assertThat(backing.get("b")).isEmpty();
            assertThat(wb.pending()).isEqualTo(2);
            wb.flush();
            assertThat(backing.get("b")).contains("2");
            assertThat(backing.get("a")).isEmpty();
            wb.put("c", "3");
        }
        assertThat(backing.get("c")).contains("3");
    }
}
