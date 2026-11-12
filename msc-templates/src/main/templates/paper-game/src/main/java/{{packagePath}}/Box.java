package {{packageName}};

import java.util.List;

/** An axis-aligned block box, corners inclusive, read from config as {@code [x1, y1, z1, x2, y2, z2]}. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    static Box parse(List<?> numbers, String where) {
        if (numbers.size() != 6) {
            throw new IllegalArgumentException(where + " needs [x1, y1, z1, x2, y2, z2], got " + numbers);
        }
        int[] v = new int[6];
        for (int i = 0; i < 6; i++) {
            if (!(numbers.get(i) instanceof Number n)) {
                throw new IllegalArgumentException(where + " has a non-number: " + numbers.get(i));
            }
            v[i] = n.intValue();
        }
        return new Box(Math.min(v[0], v[3]), Math.min(v[1], v[4]), Math.min(v[2], v[5]),
                Math.max(v[0], v[3]), Math.max(v[1], v[4]), Math.max(v[2], v[5]));
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public String toString() {
        return minX + " " + minY + " " + minZ + " to " + maxX + " " + maxY + " " + maxZ;
    }
}
