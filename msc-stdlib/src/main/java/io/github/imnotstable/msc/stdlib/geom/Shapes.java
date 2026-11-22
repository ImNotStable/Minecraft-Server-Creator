package io.github.imnotstable.msc.stdlib.geom;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Points of common shapes, as offsets from a center, for particles, spawn spots or block placement. Turn them with
 * {@link #rotateY}/{@link #translate}; platform adapters draw them (Paper: {@code ParticleEffect.draw}).
 */
public final class Shapes {
    private Shapes() {}

    /** {@code points} evenly spaced from {@code a} to {@code b}, both ends included. */
    public static List<Vec3> line(Vec3 a, Vec3 b, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            out.add(a.lerp(b, points == 1 ? 0 : (double) i / (points - 1)));
        }
        return out;
    }

    /** Points spaced about {@code step} apart from {@code a} to {@code b}. */
    public static List<Vec3> lineSpaced(Vec3 a, Vec3 b, double step) {
        return line(a, b, Math.max(2, (int) Math.ceil(a.distance(b) / step) + 1));
    }

    /** A flat ring around the vertical axis. */
    public static List<Vec3> circle(double radius, int points) {
        return arc(radius, 0, 2 * Math.PI, points, false);
    }

    /** Part of a ring, from angle {@code from} through {@code sweep} radians. */
    public static List<Vec3> arc(double radius, double from, double sweep, int points, boolean includeEnd) {
        List<Vec3> out = new ArrayList<>(points);
        int div = includeEnd && points > 1 ? points - 1 : points;
        for (int i = 0; i < points; i++) {
            double a = from + sweep * i / div;
            out.add(new Vec3(Math.cos(a) * radius, 0, Math.sin(a) * radius));
        }
        return out;
    }

    /** A filled flat disc, rings {@code spacing} apart. */
    public static List<Vec3> disc(double radius, double spacing) {
        List<Vec3> out = new ArrayList<>();
        out.add(Vec3.ZERO);
        for (double r = spacing; r <= radius + 1e-9; r += spacing) {
            out.addAll(circle(r, Math.max(6, (int) (2 * Math.PI * r / spacing))));
        }
        return out;
    }

    /** About {@code points} evenly spread over a sphere's surface (Fibonacci lattice). */
    public static List<Vec3> sphere(double radius, int points) {
        List<Vec3> out = new ArrayList<>(points);
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < points; i++) {
            double y = points == 1 ? 0 : 1 - 2.0 * i / (points - 1);
            double r = Math.sqrt(1 - y * y);
            double a = golden * i;
            out.add(new Vec3(Math.cos(a) * r * radius, y * radius, Math.sin(a) * r * radius));
        }
        return out;
    }

    /** A spiral rising {@code height} over {@code turns} turns. */
    public static List<Vec3> helix(double radius, double height, double turns, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0 : (double) i / (points - 1);
            double a = t * turns * 2 * Math.PI;
            out.add(new Vec3(Math.cos(a) * radius, t * height, Math.sin(a) * radius));
        }
        return out;
    }

    /** The 12 edges of a box between two corners, points about {@code step} apart. */
    public static List<Vec3> cuboidEdges(Vec3 min, Vec3 max, double step) {
        double[] xs = {min.x(), max.x()}, ys = {min.y(), max.y()}, zs = {min.z(), max.z()};
        List<Vec3> out = new ArrayList<>();
        for (double y : ys) {
            for (double z : zs) {
                out.addAll(lineSpaced(new Vec3(xs[0], y, z), new Vec3(xs[1], y, z), step));
            }
        }
        for (double x : xs) {
            for (double z : zs) {
                out.addAll(lineSpaced(new Vec3(x, ys[0], z), new Vec3(x, ys[1], z), step));
            }
            for (double y : ys) {
                out.addAll(lineSpaced(new Vec3(x, y, zs[0]), new Vec3(x, y, zs[1]), step));
            }
        }
        return out;
    }

    /** A star outline with {@code tips} points on a flat plane. */
    public static List<Vec3> star(double outer, double inner, int tips, int pointsPerEdge) {
        List<Vec3> corners = new ArrayList<>();
        for (int i = 0; i < tips * 2; i++) {
            double a = Math.PI * i / tips - Math.PI / 2;
            double r = i % 2 == 0 ? outer : inner;
            corners.add(new Vec3(Math.cos(a) * r, 0, Math.sin(a) * r));
        }
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < corners.size(); i++) {
            List<Vec3> edge = line(corners.get(i), corners.get((i + 1) % corners.size()), pointsPerEdge + 1);
            out.addAll(edge.subList(0, edge.size() - 1));
        }
        return out;
    }

    /** {@code points} random points inside a sphere. */
    public static List<Vec3> randomInSphere(double radius, int points, RandomGenerator random) {
        List<Vec3> out = new ArrayList<>(points);
        while (out.size() < points) {
            Vec3 v = new Vec3(random.nextDouble(-1, 1), random.nextDouble(-1, 1), random.nextDouble(-1, 1));
            if (v.length() <= 1) {
                out.add(v.multiply(radius));
            }
        }
        return out;
    }

    /** A hollow cone, tip at the origin, opening upward to {@code radius} at {@code height}. */
    public static List<Vec3> cone(double radius, double height, int rings, int pointsPerRing) {
        List<Vec3> out = new ArrayList<>();
        for (int i = 1; i <= rings; i++) {
            double t = (double) i / rings;
            for (Vec3 p : circle(radius * t, pointsPerRing)) {
                out.add(p.add(new Vec3(0, height * t, 0)));
            }
        }
        return out;
    }

    /** A hollow upright cylinder from y 0 to {@code height}. */
    public static List<Vec3> cylinder(double radius, double height, int rings, int pointsPerRing) {
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < rings; i++) {
            double y = rings == 1 ? 0 : height * i / (rings - 1);
            for (Vec3 p : circle(radius, pointsPerRing)) {
                out.add(p.add(new Vec3(0, y, 0)));
            }
        }
        return out;
    }

    /** A ring doughnut lying flat: {@code major} to the tube's centre, {@code minor} the tube's radius. */
    public static List<Vec3> torus(double major, double minor, int around, int tube) {
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < around; i++) {
            double u = 2 * Math.PI * i / around;
            for (int j = 0; j < tube; j++) {
                double v = 2 * Math.PI * j / tube;
                double r = major + minor * Math.cos(v);
                out.add(new Vec3(r * Math.cos(u), minor * Math.sin(v), r * Math.sin(u)));
            }
        }
        return out;
    }

    /** A regular polygon outline (3 a triangle, 4 a square, 6 a hexagon) on a flat plane. */
    public static List<Vec3> polygon(int sides, double radius, int pointsPerEdge) {
        List<Vec3> corners = arc(radius, -Math.PI / 2, 2 * Math.PI, sides, false);
        List<Vec3> out = new ArrayList<>();
        for (int i = 0; i < sides; i++) {
            List<Vec3> edge = line(corners.get(i), corners.get((i + 1) % sides), pointsPerEdge + 1);
            out.addAll(edge.subList(0, edge.size() - 1));
        }
        return out;
    }

    /** A flat spiral outward from the centre ({@code turns} turns). */
    public static List<Vec3> spiral(double radius, double turns, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0 : (double) i / (points - 1);
            double a = t * turns * 2 * Math.PI;
            out.add(new Vec3(Math.cos(a) * radius * t, 0, Math.sin(a) * radius * t));
        }
        return out;
    }

    /** {@code strands} helixes around one axis, a vortex or DNA look. */
    public static List<Vec3> vortex(double radius, double height, double turns, int strands, int pointsPerStrand) {
        List<Vec3> out = new ArrayList<>();
        for (int s = 0; s < strands; s++) {
            out.addAll(rotateY(helix(radius, height, turns, pointsPerStrand), 2 * Math.PI * s / strands));
        }
        return out;
    }

    /** A sine wave along X, {@code amplitude} high, {@code waves} periods over {@code length}. */
    public static List<Vec3> wave(double length, double amplitude, double waves, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double x = points == 1 ? 0 : length * i / (points - 1);
            out.add(new Vec3(x, amplitude * Math.sin(2 * Math.PI * waves * x / length), 0));
        }
        return out;
    }

    /** A flat square grid of points {@code spacing} apart, {@code size} wide, centred. */
    public static List<Vec3> grid(double size, double spacing) {
        List<Vec3> out = new ArrayList<>();
        for (double x = -size / 2; x <= size / 2 + 1e-9; x += spacing) {
            for (double z = -size / 2; z <= size / 2 + 1e-9; z += spacing) {
                out.add(new Vec3(x, 0, z));
            }
        }
        return out;
    }

    /** A heart outline standing upright, {@code size} about its width. */
    public static List<Vec3> heart(double size, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = 2 * Math.PI * i / points;
            double x = 16 * Math.pow(Math.sin(t), 3);
            double y = 13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t);
            out.add(new Vec3(x * size / 32, y * size / 32, 0));
        }
        return out;
    }

    /** A curve from {@code a} to {@code b} pulled toward {@code control} (quadratic Bezier): arcs, thrown paths. */
    public static List<Vec3> curve(Vec3 a, Vec3 control, Vec3 b, int points) {
        List<Vec3> out = new ArrayList<>(points);
        for (int i = 0; i < points; i++) {
            double t = points == 1 ? 0 : (double) i / (points - 1);
            out.add(a.lerp(control, t).lerp(control.lerp(b, t), t));
        }
        return out;
    }

    /** A filled cube of points {@code spacing} apart, {@code size} wide, centred. */
    public static List<Vec3> cube(double size, double spacing) {
        List<Vec3> out = new ArrayList<>();
        for (double y = -size / 2; y <= size / 2 + 1e-9; y += spacing) {
            for (Vec3 p : grid(size, spacing)) {
                out.add(p.add(new Vec3(0, y, 0)));
            }
        }
        return out;
    }

    /** Points turned so the shape's up (+Y) points along {@code direction}: a flat circle facing where a player looks. */
    public static List<Vec3> orient(List<Vec3> points, Vec3 direction) {
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 d = direction.normalize();
        Vec3 axis = up.cross(d);
        double angle = Math.acos(Math.max(-1, Math.min(1, up.dot(d))));
        if (axis.length() < 1e-9) {
            return d.y() >= 0 ? points : rotateX(points, Math.PI);
        }
        return points.stream().map(p -> p.rotateAround(axis, angle)).toList();
    }

    public static List<Vec3> scale(List<Vec3> points, double factor) {
        return points.stream().map(p -> p.multiply(factor)).toList();
    }

    public static List<Vec3> rotateZ(List<Vec3> points, double radians) {
        return points.stream().map(p -> p.rotateZ(radians)).toList();
    }

    public static List<Vec3> rotateY(List<Vec3> points, double radians) {
        return points.stream().map(p -> p.rotateY(radians)).toList();
    }

    public static List<Vec3> rotateX(List<Vec3> points, double radians) {
        return points.stream().map(p -> p.rotateX(radians)).toList();
    }

    public static List<Vec3> translate(List<Vec3> points, Vec3 by) {
        return points.stream().map(p -> p.add(by)).toList();
    }
}
