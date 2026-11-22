package io.github.imnotstable.msc.stdlib.geom;

/** A point or offset in block units. Platform adapters turn it into their own location type. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    public Vec3 subtract(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    public Vec3 multiply(double f) {
        return new Vec3(x * f, y * f, z * f);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double distance(Vec3 o) {
        return subtract(o).length();
    }

    /** Same direction, length 1 (ZERO stays ZERO). */
    public Vec3 normalize() {
        double l = length();
        return l == 0 ? ZERO : multiply(1 / l);
    }

    /** Turned around the vertical axis by {@code radians} (counter-clockwise seen from above). */
    public Vec3 rotateY(double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return new Vec3(x * c + z * s, y, -x * s + z * c);
    }

    /** Turned around the X axis by {@code radians}. */
    public Vec3 rotateX(double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return new Vec3(x, y * c - z * s, y * s + z * c);
    }

    public Vec3 lerp(Vec3 to, double t) {
        return add(to.subtract(this).multiply(t));
    }
}
