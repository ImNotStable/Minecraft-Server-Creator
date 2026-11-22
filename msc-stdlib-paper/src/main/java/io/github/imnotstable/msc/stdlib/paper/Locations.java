package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.List;

/** Between {@link Vec3} and Bukkit locations. */
public final class Locations {
    private Locations() {}

    public static Vec3 vec(Location l) {
        return new Vec3(l.getX(), l.getY(), l.getZ());
    }

    public static Vec3 vec(Vector v) {
        return new Vec3(v.getX(), v.getY(), v.getZ());
    }

    /** {@code origin} moved by {@code by} (same world, yaw, pitch). */
    public static Location offset(Location origin, Vec3 by) {
        return origin.clone().add(by.x(), by.y(), by.z());
    }

    /** Each point as a location around {@code origin}. */
    public static List<Location> around(Location origin, List<Vec3> points) {
        return points.stream().map(p -> offset(origin, p)).toList();
    }

    /** Points turned to face where {@code facing} looks (yaw only), so shapes follow a player's direction. */
    public static List<Vec3> facing(Location facing, List<Vec3> points) {
        double yaw = Math.toRadians(-facing.getYaw());
        return points.stream().map(p -> p.rotateY(yaw)).toList();
    }
}
