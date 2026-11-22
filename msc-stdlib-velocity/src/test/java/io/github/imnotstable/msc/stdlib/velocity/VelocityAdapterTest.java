package io.github.imnotstable.msc.stdlib.velocity;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import com.velocitypowered.api.scheduler.TaskStatus;
import io.github.imnotstable.msc.stdlib.text.Messages;
import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class VelocityAdapterTest {
    /** Records what the scheduler asked Velocity for: delay and period in milliseconds. */
    static final List<String> SCHEDULED = new ArrayList<>();

    @SuppressWarnings("unchecked")
    static <T> T fake(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, h);
    }

    static ProxyServer proxy() {
        ScheduledTask task = fake(ScheduledTask.class, (p, m, a) -> m.getName().equals("status") ? TaskStatus.SCHEDULED : null);
        long[] delay = {0};
        Scheduler.TaskBuilder[] builder = new Scheduler.TaskBuilder[1];
        builder[0] = fake(Scheduler.TaskBuilder.class, (p, m, a) -> switch (m.getName()) {
            case "delay" -> {
                delay[0] = ((TimeUnit) a[1]).toMillis((Long) a[0]);
                yield builder[0];
            }
            case "repeat" -> {
                SCHEDULED.add("every " + ((TimeUnit) a[1]).toMillis((Long) a[0]) + " after " + delay[0]);
                delay[0] = -1;
                yield builder[0];
            }
            case "schedule" -> {
                if (delay[0] >= 0) {
                    SCHEDULED.add("once after " + delay[0]);
                }
                delay[0] = 0;
                yield task;
            }
            default -> builder[0];
        });
        Scheduler scheduler = fake(Scheduler.class, (p, m, a) -> builder[0]);
        return fake(ProxyServer.class, (p, m, a) -> m.getName().equals("getScheduler") ? scheduler : null);
    }

    @Test
    void ticksBecomeMilliseconds() {
        Stdlib.init(new Object(), proxy());
        SCHEDULED.clear();
        Stdlib.scheduler().later(20, () -> { });
        var t = Stdlib.scheduler().repeat(2, 10, () -> { });
        assertThat(SCHEDULED).containsExactly("once after 1000", "every 500 after 100");
        assertThat(t.cancelled()).isFalse();
    }

    @Test
    void languagesFallBackForTheConsole() {
        List<Component> sent = new ArrayList<>();
        CommandSource console = fake(CommandSource.class, (p, m, a) -> {
            if (m.getName().equals("sendMessage") && a != null && a.length == 1 && a[0] instanceof Component c) {
                sent.add(c);
            }
            return null;
        });
        Lang.of(Messages.of("en", Map.of("hi", "<green>Hi <name>"))).send(console, "hi", "name", "Alex");
        assertThat(sent).extracting(Text::plain).containsExactly("Hi Alex");
    }
}
