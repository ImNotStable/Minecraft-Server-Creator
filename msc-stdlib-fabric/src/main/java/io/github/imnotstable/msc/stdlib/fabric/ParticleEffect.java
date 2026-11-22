package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A particle with its settings, sent at a point, over a shape from {@link io.github.imnotstable.msc.stdlib.geom.Shapes},
 * or animated. Points are {@link Vec3}; {@code Locations.vec(entity.position())} turns a position into one.
 *
 * <pre>{@code
 * ParticleEffect.of(ParticleTypes.FLAME).draw(level, Locations.vec(player.position()), Shapes.circle(1.5, 24));
 * }</pre>
 */
public record ParticleEffect(ParticleOptions particle, int count, Vec3 offset, double speed, boolean force) {
    public static ParticleEffect of(ParticleOptions particle) {
        return new ParticleEffect(particle, 1, Vec3.ZERO, 0, false);
    }

    /** Coloured dust: {@code rgb} like 0xFF0000, {@code size} 0.01 to 4. */
    public static ParticleEffect dust(int rgb, float size) {
        return of(new DustParticleOptions(rgb, size));
    }

    public ParticleEffect count(int n) {
        return new ParticleEffect(particle, n, offset, speed, force);
    }

    public ParticleEffect offset(double x, double y, double z) {
        return new ParticleEffect(particle, count, new Vec3(x, y, z), speed, force);
    }

    public ParticleEffect speed(double s) {
        return new ParticleEffect(particle, count, offset, s, force);
    }

    public ParticleEffect force(boolean f) {
        return new ParticleEffect(particle, count, offset, speed, f);
    }

    /** Shown to every player near {@code at}. */
    public void spawn(ServerLevel level, Vec3 at) {
        level.sendParticles(particle, force, false, at.x(), at.y(), at.z(), count, offset.x(), offset.y(), offset.z(), speed);
    }

    /** Shown only to {@code viewer}. */
    public void spawn(ServerPlayer viewer, Vec3 at) {
        viewer.level().sendParticles(viewer, particle, force, false, at.x(), at.y(), at.z(), count, offset.x(),
                offset.y(), offset.z(), speed);
    }

    public void draw(ServerLevel level, Vec3 origin, Collection<Vec3> points) {
        for (Vec3 p : points) {
            spawn(level, origin.add(p));
        }
    }

    /** Draws {@code frame.apply(0)}, {@code frame.apply(1)}... every {@code periodTicks}, {@code frames} times (0: until cancelled). */
    public Scheduler.Task play(Scheduler scheduler, ServerLevel level, Vec3 origin, long periodTicks, int frames,
                               IntFunction<List<Vec3>> frame) {
        int[] i = {0};
        Scheduler.Task[] self = new Scheduler.Task[1];
        self[0] = scheduler.repeat(0, periodTicks, () -> {
            if (frames > 0 && i[0] >= frames) {
                self[0].cancel();
                return;
            }
            draw(level, origin, frame.apply(i[0]++));
        });
        return self[0];
    }
}
