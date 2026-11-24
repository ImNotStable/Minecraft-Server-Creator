package io.github.imnotstable.msc.stdlib.vanilla;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.function.Consumer;

/** Teleports with a warm-up you show yourself (eachSecond); moving off the block cancels when asked. */
public final class Teleports {
    private Teleports() {}

    public static Scheduler.Task warmup(ServerPlayer player, ServerLevel level, Vec3 to, int seconds, boolean cancelOnMove,
                                        java.util.function.IntConsumer eachSecond, Consumer<Boolean> done) {
        net.minecraft.core.BlockPos start = player.blockPosition();
        Scheduler.Task[] task = new Scheduler.Task[1];
        task[0] = Stdlib.scheduler().countdown(seconds, left -> {
            if (cancelOnMove && !player.blockPosition().equals(start)) {
                task[0].cancel();
                done.accept(false);
                return;
            }
            eachSecond.accept(left);
        }, () -> {
            if (player.hasDisconnected()) {
                done.accept(false);
                return;
            }
            player.teleportTo(level, to.x(), to.y(), to.z(), Set.of(), player.getYRot(), player.getXRot(), true);
            done.accept(true);
        });
        return task[0];
    }
}
