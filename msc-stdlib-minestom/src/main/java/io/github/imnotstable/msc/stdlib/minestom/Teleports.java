package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;

import java.util.function.Consumer;

/** Teleports with a warm-up you show yourself (eachSecond); moving off the block cancels when asked. */
public final class Teleports {
    private Teleports() {}

    /** To {@code to} in {@code instance} (null: the player's own) after {@code seconds}. */
    public static Scheduler.Task warmup(Player player, Instance instance, Pos to, int seconds, boolean cancelOnMove,
                                        java.util.function.IntConsumer eachSecond, Consumer<Boolean> done) {
        Pos start = player.getPosition();
        Scheduler.Task[] task = new Scheduler.Task[1];
        task[0] = Stdlib.scheduler().countdown(seconds, left -> {
            if (cancelOnMove && !player.getPosition().sameBlock(start)) {
                task[0].cancel();
                done.accept(false);
                return;
            }
            eachSecond.accept(left);
        }, () -> {
            if (!player.isOnline()) {
                done.accept(false);
                return;
            }
            var move = instance == null || instance == player.getInstance() ? player.teleport(to)
                    : player.setInstance(instance, to);
            move.thenRun(() -> {
                done.accept(true);
            });
        });
        return task[0];
    }
}
