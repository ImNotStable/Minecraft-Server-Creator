package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A particle with its settings, drawn at a point, over a shape from {@link io.github.imnotstable.msc.stdlib.geom.Shapes},
 * or animated.
 *
 * <pre>{@code
 * ParticleEffect.of(Particle.FLAME).draw(loc, Shapes.circle(1.5, 24));
 * ParticleEffect.dust(Color.RED, 1.2f).play(Stdlib.scheduler(), loc, 2, 40,
 *         frame -> Shapes.rotateY(Shapes.star(2, 0.8, 5, 6), frame * 0.1));
 * }</pre>
 *
 * @param count  particles per point (0 with an offset makes the offset a direction, speed its strength)
 * @param offset random spread per axis
 * @param speed  the particle's "extra" value: speed for most, size for some
 * @param data   the particle's data (DustOptions, BlockData, ItemStack...), or null
 * @param force  shown up to 512 blocks away and with reduced particles settings
 */
public record ParticleEffect(Particle particle, int count, Vec3 offset, double speed, Object data, boolean force) {
    public static ParticleEffect of(Particle particle) {
        return new ParticleEffect(particle, 1, Vec3.ZERO, 0, null, false);
    }

    /** Coloured dust, {@code size} 0.01 to 4. */
    public static ParticleEffect dust(Color color, float size) {
        return new ParticleEffect(Particle.DUST, 1, Vec3.ZERO, 0, new Particle.DustOptions(color, size), false);
    }

    public ParticleEffect count(int n) {
        return new ParticleEffect(particle, n, offset, speed, data, force);
    }

    public ParticleEffect offset(double x, double y, double z) {
        return new ParticleEffect(particle, count, new Vec3(x, y, z), speed, data, force);
    }

    public ParticleEffect speed(double s) {
        return new ParticleEffect(particle, count, offset, s, data, force);
    }

    public ParticleEffect data(Object d) {
        return new ParticleEffect(particle, count, offset, speed, d, force);
    }

    public ParticleEffect force(boolean f) {
        return new ParticleEffect(particle, count, offset, speed, data, f);
    }

    /** Shown to everyone near {@code at}. */
    public void spawn(Location at) {
        at.getWorld().spawnParticle(particle, at, count, offset.x(), offset.y(), offset.z(), speed, data, force);
    }

    /** Shown only to {@code viewer}. */
    public void spawn(Player viewer, Location at) {
        viewer.spawnParticle(particle, at, count, offset.x(), offset.y(), offset.z(), speed, data, force);
    }

    /** One particle burst at each point, as offsets from {@code origin}. */
    public void draw(Location origin, Collection<Vec3> points) {
        for (Vec3 p : points) {
            spawn(Locations.offset(origin, p));
        }
    }

    /** Like {@link #draw}, shown only to {@code viewers}. */
    public void draw(Collection<? extends Player> viewers, Location origin, Collection<Vec3> points) {
        for (Vec3 p : points) {
            Location at = Locations.offset(origin, p);
            viewers.forEach(v -> spawn(v, at));
        }
    }

    /**
     * Draws {@code frame.apply(0)}, {@code frame.apply(1)}... every {@code periodTicks}, {@code frames} times (0 for
     * until cancelled).
     */
    public Scheduler.Task play(Scheduler scheduler, Location origin, long periodTicks, int frames,
                               IntFunction<List<Vec3>> frame) {
        int[] i = {0};
        Scheduler.Task[] self = new Scheduler.Task[1];
        self[0] = scheduler.repeat(0, periodTicks, () -> {
            if (frames > 0 && i[0] >= frames) {
                self[0].cancel();
                return;
            }
            draw(origin, frame.apply(i[0]++));
        });
        return self[0];
    }
}
