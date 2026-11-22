package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import net.minestom.server.coordinate.Point;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;

import java.util.List;

/** Between {@link Vec3} and Minestom points. */
public final class Locations {
    private Locations() {}

    public static Vec3 vec(Point p) {
        return new Vec3(p.x(), p.y(), p.z());
    }

    public static Vec point(Vec3 v) {
        return new Vec(v.x(), v.y(), v.z());
    }

    public static Point offset(Point origin, Vec3 by) {
        return origin.add(by.x(), by.y(), by.z());
    }

    /** Points turned to where {@code facing} looks (yaw only). */
    public static List<Vec3> facing(Pos facing, List<Vec3> points) {
        double yaw = Math.toRadians(-facing.yaw());
        return points.stream().map(p -> p.rotateY(yaw)).toList();
    }
}
