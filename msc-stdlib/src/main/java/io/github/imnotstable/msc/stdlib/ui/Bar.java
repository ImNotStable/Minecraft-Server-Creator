package io.github.imnotstable.msc.stdlib.ui;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import io.github.imnotstable.msc.stdlib.text.Text;
import io.github.imnotstable.msc.stdlib.util.Format;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A boss bar at the top of the screen: progress, timers, boss health, objectives.
 *
 * <pre>{@code
 * Bar bar = Bar.create("<red>Dragon <hp>/<max>", BossBar.Color.RED).placeholders("hp", 200, "max", 200).show(players);
 * bar.progress(hp / 200.0).placeholders("hp", hp, "max", 200);
 * Bar.timer(scheduler, players, "<yellow>Next round in <time>", Duration.ofSeconds(30), BossBar.Color.YELLOW, () -> startRound());
 * Bar.create("<green>Saved!", BossBar.Color.GREEN).show(player).hideAfter(scheduler, Duration.ofSeconds(3));
 * }</pre>
 * {@code scheduler} is the platform's: {@code Stdlib.scheduler()} on Paper and Minestom.
 */
public final class Bar {
    private final BossBar bar;
    private final String text;
    private final List<Audience> viewers = new ArrayList<>();
    private Scheduler.Task task;

    private Bar(String text, BossBar.Color color) {
        this.text = text;
        this.bar = BossBar.bossBar(Text.mm(text), 1f, color, BossBar.Overlay.PROGRESS);
    }

    /** Full, with MiniMessage text. Shown to nobody until {@link #show}. */
    public static Bar create(String miniMessage, BossBar.Color color) {
        return new Bar(miniMessage, color);
    }

    /**
     * A bar that counts down {@code length}, its text's {@code <time>} showing what is left ({@code 0:25}), then hides
     * and runs {@code done}. Cancel with {@link #hide}.
     */
    public static Bar timer(Scheduler scheduler, Iterable<? extends Audience> to, String miniMessage, Duration length,
                            BossBar.Color color, Runnable done) {
        Bar b = create(miniMessage, color);
        to.forEach(b::show);
        long total = Math.max(1, length.toMillis() / 50);
        long[] left = {total};
        b.placeholders("time", Format.clock(length));
        b.task = scheduler.repeat(0, 1, () -> {
            left[0]--;
            if (left[0] % 20 == 0) {
                b.placeholders("time", Format.clock(Duration.ofMillis(left[0] * 50)));
            }
            b.progress(left[0] / (double) total);
            if (left[0] <= 0) {
                b.hide();
                done.run();
            }
        });
        return b;
    }

    public Bar show(Audience to) {
        to.showBossBar(bar);
        viewers.add(to);
        return this;
    }

    public Bar show(Iterable<? extends Audience> to) {
        to.forEach(this::show);
        return this;
    }

    public Bar hide(Audience from) {
        from.hideBossBar(bar);
        viewers.remove(from);
        return this;
    }

    /** Hides from everyone and stops a timer. */
    public void hide() {
        if (task != null) {
            task.cancel();
        }
        viewers.forEach(v -> v.hideBossBar(bar));
        viewers.clear();
    }

    /** Hides from everyone after {@code d}. */
    public Bar hideAfter(Scheduler scheduler, Duration d) {
        scheduler.later(Math.max(1, d.toMillis() / 50), this::hide);
        return this;
    }

    /** 0 to 1, clamped. */
    public Bar progress(double fraction) {
        bar.progress((float) Math.max(0, Math.min(1, fraction)));
        return this;
    }

    /** Re-renders the text with these placeholders. */
    public Bar placeholders(Object... placeholders) {
        bar.name(Text.mm(text, placeholders));
        return this;
    }

    /** New MiniMessage text. */
    public Bar text(String miniMessage, Object... placeholders) {
        bar.name(Text.mm(miniMessage, placeholders));
        return this;
    }

    public Bar color(BossBar.Color color) {
        bar.color(color);
        return this;
    }

    /** Notches: {@code BossBar.Overlay.NOTCHED_10} for ten segments. */
    public Bar overlay(BossBar.Overlay overlay) {
        bar.overlay(overlay);
        return this;
    }

    /** The Adventure boss bar, for anything else. */
    public BossBar bossBar() {
        return bar;
    }
}
