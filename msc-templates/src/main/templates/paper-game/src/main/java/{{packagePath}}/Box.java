package {{packageName}};

/**
 * An axis-aligned block box, corners inclusive, read from config as {@code [x1, y1, z1, x2, y2, z2]}. Corners must lie
 * within Minecraft's world limits ({@link #MAX_XZ}, {@link #MIN_Y} to {@link #MAX_Y}).
 */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    /** How far from 0 a world reaches on x and z. */
    static final int MAX_XZ = 30_000_000;
    /** The lowest and highest y any dimension can have. */
    static final int MIN_Y = -2032;
    static final int MAX_Y = 2031;

    public Box {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("box min corner is past its max corner: " + minX + " " + minY + " "
                    + minZ + " to " + maxX + " " + maxY + " " + maxZ + "; use Box.of for corners in any order");
        }
        if (minX < -MAX_XZ || maxX > MAX_XZ || minZ < -MAX_XZ || maxZ > MAX_XZ || minY < MIN_Y || maxY > MAX_Y) {
            throw new IllegalArgumentException("box " + minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " "
                    + maxZ + " is outside the world (x and z within " + MAX_XZ + ", y " + MIN_Y + " to " + MAX_Y + ")");
        }
    }

    /** {@code true} when the position is finite and inside the world's limits, yaw within 360 and pitch within 90. */
    static boolean inWorld(double x, double y, double z, float yaw, float pitch) {
        return Math.abs(x) <= MAX_XZ && Math.abs(z) <= MAX_XZ && y >= MIN_Y && y <= MAX_Y
                && Math.abs(yaw) <= 360 && Math.abs(pitch) <= 90;
    }

    /** The box between two corners given in any order. */
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public String toString() {
        return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ;
    }
}
