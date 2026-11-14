package {{packageName}};

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A position with a view direction, read from config as {@code [x, y, z]} or {@code [x, y, z, yaw, pitch]}. Every part
 * must be a finite number within the world ({@link Box#MAX_XZ}, {@link Box#MIN_Y} to {@link Box#MAX_Y}), yaw within
 * 360 and pitch from -90 to 90.
 */
public record Spot(double x, double y, double z, float yaw, float pitch) {
    public Spot {
        if (!Box.inWorld(x, y, z, yaw, pitch)) {
            throw new IllegalArgumentException("spot " + x + " " + y + " " + z + " " + yaw + " " + pitch
                    + " is not finite or outside the world (x and z within " + Box.MAX_XZ + ", y " + Box.MIN_Y + " to "
                    + Box.MAX_Y + ", yaw within 360, pitch within 90)");
        }
    }

    Location in(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
