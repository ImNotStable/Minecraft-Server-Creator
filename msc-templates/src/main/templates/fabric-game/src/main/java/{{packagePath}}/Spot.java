package {{packageName}};

/** A position with a view direction: {@code new Spot(0.5, -60, 0.5)} or with {@code yaw, pitch}. */
public record Spot(double x, double y, double z, float yaw, float pitch) {
    public Spot(double x, double y, double z) {
        this(x, y, z, 0, 0);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
