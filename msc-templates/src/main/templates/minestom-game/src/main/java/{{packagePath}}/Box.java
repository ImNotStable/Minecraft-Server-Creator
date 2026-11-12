package {{packageName}};

import net.minestom.server.coordinate.Point;

/** An axis-aligned block box, corners inclusive. {@code Box.of(-2, -61, 10, 2, -58, 12)} takes corners in any order. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True when the block the point is in lies inside the box. */
    public boolean contains(Point p) {
        return contains(p.blockX(), p.blockY(), p.blockZ());
    }

    @Override
    public String toString() {
        return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ;
    }
}
