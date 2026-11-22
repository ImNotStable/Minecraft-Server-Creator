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
