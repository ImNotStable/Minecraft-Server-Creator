package {{packageName}};

/**
 * A position with a view direction: {@code new Spot(0.5, -60, 0.5)} or with {@code yaw, pitch}. Every part must be
 * finite and inside the world (see {@link Box#inWorld}).
 */
public record Spot(double x, double y, double z, float yaw, float pitch) {
    public Spot {
        if (!Box.inWorld(x, y, z, yaw, pitch)) {
            throw new IllegalArgumentException("spot " + x + " " + y + " " + z + " " + yaw + " " + pitch
                    + " is not finite or outside the world (x and z within " + Box.MAX_XZ + ", y " + Box.MIN_Y + " to "
                    + Box.MAX_Y + ", yaw within 360, pitch within 90)");
        }
    }

    public Spot(double x, double y, double z) {
        this(x, y, z, 0, 0);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
