package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.util.Format;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Function;

/**
 * A boss bar: progress, timers, boss health.
 *
 * <pre>{@code
 * Bar.timer(players, left -> Component.literal("Next round in " + left), Duration.ofSeconds(30),
 *         BossEvent.BossBarColor.YELLOW, this::startRound);
 * Bar bar = Bar.create(Component.literal("Dragon"), BossEvent.BossBarColor.RED).show(player).progress(0.5);
 * }</pre>
 */
public final class Bar {
    private final ServerBossEvent event;
    private Scheduler.Task task;

    private Bar(Component name, BossEvent.BossBarColor color) {
        this.event = new ServerBossEvent(UUID.randomUUID(), name, color, BossEvent.BossBarOverlay.PROGRESS);
    }

    public static Bar create(Component name, BossEvent.BossBarColor color) {
        return new Bar(name, color);
    }

    /** Counts down {@code length}; {@code text} gets the time left ({@code 0:25}). Hides and runs {@code done} at 0. */
    public static Bar timer(Iterable<ServerPlayer> to, Function<String, Component> text, Duration length,
                            BossEvent.BossBarColor color, Runnable done) {
        Bar b = create(text.apply(Format.clock(length)), color);
        to.forEach(b::show);
        long total = Math.max(1, length.toMillis() / 50);
        long[] left = {total};
        b.task = Stdlib.scheduler().repeat(0, 1, () -> {
            left[0]--;
            if (left[0] % 20 == 0) {
                b.event.setName(text.apply(Format.clock(Duration.ofMillis(left[0] * 50))));
            }
            b.progress(left[0] / (double) total);
            if (left[0] <= 0) {
                b.hide();
                done.run();
            }
        });
        return b;
    }

    public Bar show(ServerPlayer player) {
        event.addPlayer(player);
        return this;
    }

    public Bar hide(ServerPlayer player) {
        event.removePlayer(player);
        return this;
    }

    public void hide() {
        if (task != null) {
            task.cancel();
        }
        event.removeAllPlayers();
    }

    public Bar hideAfter(Duration d) {
        Stdlib.scheduler().later(Math.max(1, d.toMillis() / 50), this::hide);
        return this;
    }

    public Bar progress(double fraction) {
        event.setProgress((float) Math.max(0, Math.min(1, fraction)));
        return this;
    }

    public Bar name(Component name) {
        event.setName(name);
        return this;
    }

    public Bar color(BossEvent.BossBarColor color) {
        event.setColor(color);
        return this;
    }

    public ServerBossEvent event() {
        return event;
    }
}
