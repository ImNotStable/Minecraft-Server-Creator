package {{packageName}};

import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;

/** A position with a view direction, read from config as {@code [x, y, z]} or {@code [x, y, z, yaw, pitch]}. */
public record Spot(double x, double y, double z, float yaw, float pitch) {
    static Spot parse(List<?> numbers, String where) {
        if (numbers.size() != 3 && numbers.size() != 5) {
            throw new IllegalArgumentException(
                    where + " needs [x, y, z] or [x, y, z, yaw, pitch], got " + GameConfig.got(numbers));
        }
        double[] v = new double[5];
        for (int i = 0; i < numbers.size(); i++) {
            if (!(numbers.get(i) instanceof Number n)) {
                throw new IllegalArgumentException(
                        where + " has a non-number: " + GameConfig.got(numbers.get(i), GameConfig.NUMBER));
            }
            v[i] = n.doubleValue();
        }
        return new Spot(v[0], v[1], v[2], (float) v[3], (float) v[4]);
    }

    Location in(World world) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }
}
