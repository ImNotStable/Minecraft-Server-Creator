package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import io.github.imnotstable.msc.stdlib.sched.Scheduler;
import net.kyori.adventure.util.RGBLike;
import net.minestom.server.adventure.audience.PacketGroupingAudience;
import net.minestom.server.coordinate.Point;
import net.minestom.server.entity.Player;
import net.minestom.server.network.packet.server.play.ParticlePacket;
import net.minestom.server.particle.Particle;

import java.util.Collection;
import java.util.List;
import java.util.function.IntFunction;

/**
 * A particle with its settings, sent at a point, over a shape from {@link io.github.imnotstable.msc.stdlib.geom.Shapes},
 * or animated. The audience is an {@code Instance} (everyone in it) or any group of players.
 *
 * <pre>{@code
 * ParticleEffect.of(Particle.FLAME).draw(instance, pos, Shapes.circle(1.5, 24));
 * }</pre>
 */
public record ParticleEffect(Particle particle, int count, Vec3 offset, float speed, boolean force) {
    public static ParticleEffect of(Particle particle) {
        return new ParticleEffect(particle, 1, Vec3.ZERO, 0, false);
    }

    /** Coloured dust, {@code size} 0.01 to 4. */
    public static ParticleEffect dust(RGBLike color, float size) {
        return of(Particle.DUST.withProperties(color, size));
    }

    public ParticleEffect count(int n) {
        return new ParticleEffect(particle, n, offset, speed, force);
    }

    public ParticleEffect offset(double x, double y, double z) {
        return new ParticleEffect(particle, count, new Vec3(x, y, z), speed, force);
    }

    public ParticleEffect speed(float s) {
        return new ParticleEffect(particle, count, offset, s, force);
    }

    public ParticleEffect force(boolean f) {
        return new ParticleEffect(particle, count, offset, speed, f);
    }

    public ParticlePacket packet(Point at) {
        return new ParticlePacket(particle, force, false, at.x(), at.y(), at.z(), (float) offset.x(),
                (float) offset.y(), (float) offset.z(), speed, count);
    }

    public void spawn(PacketGroupingAudience viewers, Point at) {
        viewers.sendGroupedPacket(packet(at));
    }

    public void spawn(Player viewer, Point at) {
        viewer.sendPacket(packet(at));
    }

    public void draw(PacketGroupingAudience viewers, Point origin, Collection<Vec3> points) {
        for (Vec3 p : points) {
            spawn(viewers, Locations.offset(origin, p));
        }
    }

    /** Draws {@code frame.apply(0)}, {@code frame.apply(1)}... every {@code periodTicks}, {@code frames} times (0: until cancelled). */
    public Scheduler.Task play(Scheduler scheduler, PacketGroupingAudience viewers, Point origin, long periodTicks,
                               int frames, IntFunction<List<Vec3>> frame) {
        int[] i = {0};
        Scheduler.Task[] self = new Scheduler.Task[1];
        self[0] = scheduler.repeat(0, periodTicks, () -> {
            if (frames > 0 && i[0] >= frames) {
                self[0].cancel();
                return;
            }
            draw(viewers, origin, frame.apply(i[0]++));
        });
        return self[0];
    }
}
