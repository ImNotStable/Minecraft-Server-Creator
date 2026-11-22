package io.github.imnotstable.msc.stdlib.fabric;

import io.github.imnotstable.msc.stdlib.geom.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** Between {@link Vec3} and Minecraft positions. */
public final class Locations {
    private Locations() {}

    public static Vec3 vec(net.minecraft.world.phys.Vec3 v) {
        return new Vec3(v.x, v.y, v.z);
    }

    /** The centre of the block's bottom face. */
    public static Vec3 vec(BlockPos p) {
        return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }

    public static net.minecraft.world.phys.Vec3 minecraft(Vec3 v) {
        return new net.minecraft.world.phys.Vec3(v.x(), v.y(), v.z());
    }

    /** Points turned to where {@code entity} looks (yaw only). */
    public static List<Vec3> facing(Entity entity, List<Vec3> points) {
        double yaw = Math.toRadians(-entity.getYRot());
        return points.stream().map(p -> p.rotateY(yaw)).toList();
    }
}
