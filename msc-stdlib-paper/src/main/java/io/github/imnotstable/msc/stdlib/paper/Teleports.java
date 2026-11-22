package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

/** Teleports with a warm-up: /spawn, /home, /warp. */
public final class Teleports {
    private Teleports() {}

    /**
     * Counts down {@code seconds} in the action bar, then teleports with a sound. Moving off the block cancels it
     * (when {@code cancelOnMove}). {@code done} gets true when the player arrived.
     */
    public static Scheduler.Task warmup(Player player, Location to, int seconds, boolean cancelOnMove, Consumer<Boolean> done) {
        Location start = player.getLocation().toBlockLocation();
        Scheduler.Task[] task = new Scheduler.Task[1];
        task[0] = Stdlib.scheduler().countdown(seconds, left -> {
            if (cancelOnMove && !player.getLocation().toBlockLocation().equals(start)) {
                task[0].cancel();
                Feedback.fail(player, "Teleport cancelled: you moved");
                done.accept(false);
                return;
            }
            Feedback.actionBar(player, "<yellow>Teleporting in <white><s></white>s, don't move", "s", left);
            Sounds.TICK.play(player);
        }, () -> {
            if (!player.isOnline()) {
                done.accept(false);
                return;
            }
            player.teleportAsync(to).thenAccept(ok -> {
                if (ok) {
                    Sounds.TELEPORT.play(player);
                }
                done.accept(ok);
            });
        });
        return task[0];
    }
}
