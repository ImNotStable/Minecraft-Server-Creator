package io.github.imnotstable.msc.stdlib;

import io.github.imnotstable.msc.stdlib.config.Config;
import io.github.imnotstable.msc.stdlib.event.EventBus;
import io.github.imnotstable.msc.stdlib.geom.Cuboid;
import io.github.imnotstable.msc.stdlib.geom.Region;
import io.github.imnotstable.msc.stdlib.geom.Shapes;
import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.messaging.Messenger;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.sched.Sequence;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.time.RateLimiter;
import io.github.imnotstable.msc.stdlib.util.Format;
import io.github.imnotstable.msc.stdlib.util.Leaderboard;
import io.github.imnotstable.msc.stdlib.util.Randoms;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class CoreUtilitiesTest {
    @TempDir
    Path dir;

    record Settings(int maxPlayers, Duration round, List<String> arenas) implements Config.Validated {
        @Override
        public void validate() {
            Config.require(maxPlayers > 0, "maxPlayers must be above 0");
        }
    }

    record SettingsV2(int maxPlayers, Duration round, List<String> arenas, boolean pvp) {}

    @Test
    void configsWriteDefaultsAndAddNewSettings() throws Exception {
        Path f = dir.resolve("cfg/settings.json");
        Settings s = Config.load(f, Settings.class, () -> new Settings(16, Duration.ofMinutes(5), List.of("a")));
        assertThat(s.round()).isEqualTo(Duration.ofMinutes(5));
        Files.writeString(f, Files.readString(f).replace("16", "8"));
        SettingsV2 v2 = Config.load(f, SettingsV2.class, () -> new SettingsV2(16, Duration.ZERO, List.of(), true));
        assertThat(v2.maxPlayers()).isEqualTo(8);
        assertThat(v2.pvp()).isTrue();
        assertThat(Files.readString(f)).contains("\"pvp\": true");
        Files.writeString(f, "{\"maxPlayers\": 0}");
        assertThatThrownBy(() -> Config.load(f, Settings.class, () -> new Settings(1, Duration.ZERO, List.of())))
                .hasMessageContaining("maxPlayers must be above 0");
    }

    static final class Scored extends EventBus.CancellableEvent {
        int points;
    }

    @Test
    void theEventBusRunsHandlersByPriorityAndStopsOnCancel() {
        EventBus bus = new EventBus();
        List<String> ran = new ArrayList<>();
        bus.on(Scored.class, e -> ran.add("normal"));
        bus.on(Scored.class, 10, false, e -> {
            ran.add("first");
            e.cancelled(true);
        });
        bus.on(Object.class, 0, true, e -> ran.add("any"));
        assertThat(bus.post(new Scored()).cancelled()).isTrue();
        assertThat(ran).containsExactly("first", "any");
    }

    @Test
    void formatsReadWell() {
        assertThat(Format.number(1250)).isEqualTo("1,250");
        assertThat(Format.compact(1250)).isEqualTo("1.25K");
        assertThat(Format.compact(3_400_000)).isEqualTo("3.4M");
        assertThat(Format.money(1500, "$")).isEqualTo("$1,500.00");
        assertThat(Format.percent(0.256)).isEqualTo("25.6%");
        assertThat(Format.ordinal(22)).isEqualTo("22nd");
        assertThat(Format.ordinal(13)).isEqualTo("13th");
        assertThat(Format.roman(14)).isEqualTo("XIV");
        assertThat(Format.bar(3, 5, 5)).isEqualTo("▮▮▮▯▯");
        assertThat(Format.coloredBar(1, 2, 4, "green", "gray")).isEqualTo("<green>▮▮</green><gray>▮▮</gray>");
        assertThat(Format.duration(Duration.ofSeconds(200))).isEqualTo("3m 20s");
        assertThat(Format.clock(Duration.ofSeconds(3725))).isEqualTo("1:02:05");
        assertThat(Format.ago(Instant.EPOCH, Instant.EPOCH.plusSeconds(300))).isEqualTo("5 minutes ago");
        assertThat(Format.plural(3, "berry")).isEqualTo("3 berries");
        assertThat(Format.title("minecraft:diamond_sword")).isEqualTo("Diamond Sword");
    }

    @Test
    void leaderboardsRankWithTies() {
        Leaderboard<String> lb = new Leaderboard<>();
        lb.add("a", 5);
        lb.add("b", 9);
        lb.add("c", 5);
        assertThat(lb.top(3)).extracting(Leaderboard.Entry::rank).containsExactly(1, 2, 2);
        assertThat(lb.rank("b")).isEqualTo(1);
        Leaderboard<String> laps = new Leaderboard<>(true);
        laps.best("x", 30);
        laps.best("x", 25);
        assertThat(laps.score("x")).isEqualTo(25);
    }

    @Test
    void regionsAndShapes() {
        Cuboid c = Cuboid.of(new Vec3(2, 0, 2), new Vec3(0, 1, 0));
        assertThat(c.volume()).isEqualTo(18);
        assertThat(c.contains(new Vec3(2.9, 1.5, 0))).isTrue();
        assertThat(c.contains(new Vec3(3, 0, 0))).isFalse();
        assertThat(c.blocks()).hasSize(18);
        assertThat(c.shell()).hasSize(18);
        assertThat(c.contains(c.randomPoint(new Random(1)))).isTrue();
        assertThat(Region.sphere(Vec3.ZERO, 2).contains(new Vec3(1, 1, 1))).isTrue();
        assertThat(Region.cylinder(Vec3.ZERO, 1, 3).contains(new Vec3(0, 4, 0))).isFalse();
        assertThat(Shapes.torus(3, 1, 12, 6)).hasSize(72);
        assertThat(Shapes.polygon(6, 2, 4)).hasSize(24);
        assertThat(Shapes.cone(2, 3, 4, 10).getLast().y()).isEqualTo(3);
        assertThat(Shapes.curve(Vec3.ZERO, new Vec3(1, 2, 0), new Vec3(2, 0, 0), 3).get(1)).isEqualTo(new Vec3(1, 1, 0));
        Vec3 facing = Shapes.orient(List.of(new Vec3(0, 1, 0)), new Vec3(1, 0, 0)).getFirst();
        assertThat(facing.x()).isCloseTo(1, within(1e-9));
        assertThat(Vec3.direction(0, -90).y()).isCloseTo(1, within(1e-9));
        assertThat(Randoms.sample(List.of(1, 2, 3), 2)).hasSize(2);
    }

    @Test
    void rateLimitsRefill() {
        StoresAndCachesTest.TestClock clock = new StoresAndCachesTest.TestClock();
        RateLimiter<String> r = new RateLimiter<>(2, Duration.ofSeconds(2), clock);
        assertThat(r.tryAcquire("a")).isTrue();
        assertThat(r.tryAcquire("a")).isTrue();
        assertThat(r.tryAcquire("a")).isFalse();
        clock.now = clock.now.plusSeconds(1);
        assertThat(r.tryAcquire("a")).isTrue();
    }

    /** A scheduler driven by hand, one tick at a time. */
    static final class ManualScheduler implements Scheduler {
        private final List<long[]> due = new ArrayList<>();
        private final List<Runnable> tasks = new ArrayList<>();
        long now;

        @Override
        public Executor sync() {
            return Runnable::run;
        }

        @Override
        public Executor async() {
            return Runnable::run;
        }

        @Override
        public Task later(long delay, Runnable task) {
            due.add(new long[]{now + delay});
            tasks.add(task);
            return new Task() {
                @Override
                public void cancel() {
                    int i = tasks.indexOf(task);
                    if (i >= 0) {
                        tasks.remove(i);
                        due.remove(i);
                    }
                }

                @Override
                public boolean cancelled() {
                    return !tasks.contains(task);
                }
            };
        }

        @Override
        public Task repeat(long delay, long period, Runnable task) {
            throw new UnsupportedOperationException();
        }

        void tick() {
            for (int i = 0; i < tasks.size(); i++) {
                if (due.get(i)[0] <= now) {
                    Runnable r = tasks.remove(i);
                    due.remove(i);
                    r.run();
                    i = -1;
                }
            }
            now++;
        }
    }

    @Test
    void aSequenceRunsStepsWithPauses() {
        ManualScheduler s = new ManualScheduler();
        List<String> ran = new ArrayList<>();
        Sequence.of(s).run(() -> ran.add("go@" + s.now)).pause(5).repeat(2, 3, n -> ran.add(n + "@" + s.now))
                .run(() -> ran.add("end@" + s.now)).start();
        for (int i = 0; i < 20; i++) {
            s.tick();
        }
        assertThat(ran).containsExactly("go@0", "0@5", "1@8", "end@8");
    }

    @Test
    void messagesFallBackByLanguage() throws Exception {
        Messages m = Messages.load(dir, "en", Map.of("hi", "<green>Hi <name>", "bye", "Bye"));
        Files.writeString(dir.resolve("messages_de.properties"), "hi=Hallo <name>\n");
        m = Messages.load(dir, "en", Map.of("hi", "<green>Hi <name>", "bye", "Bye", "new", "New"));
        assertThat(m.get(Locale.GERMANY, "hi")).isEqualTo("Hallo <name>");
        assertThat(m.get(Locale.GERMANY, "bye")).isEqualTo("Bye");
        assertThat(m.get("missing")).isEqualTo("missing");
        assertThat(Files.readString(dir.resolve("messages_en.properties"))).contains("new=New");
    }

    record Chat(String from, String text) {}

    @Test
    void aLocalMessengerDeliversDecodedMessages() {
        try (Messenger m = Messenger.local()) {
            List<Chat> got = new ArrayList<>();
            var sub = m.subscribe("chat", Chat.class, got::add);
            m.publish("chat", new Chat("lobby", "hi"));
            sub.close();
            m.publish("chat", new Chat("lobby", "gone"));
            assertThat(got).containsExactly(new Chat("lobby", "hi"));
        }
    }
}
