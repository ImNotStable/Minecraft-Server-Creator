package io.github.imnotstable.msc.stdlib.geom;

import java.util.random.RandomGenerator;

/** An area in the world: {@link Cuboid}, {@link #sphere}, {@link #cylinder}. Platform adapters track who is inside. */
public interface Region {
    boolean contains(Vec3 point);

    Vec3 center();

    /** A random point inside. */
    Vec3 randomPoint(RandomGenerator random);

    static Region sphere(Vec3 center, double radius) {
        return new Region() {
            @Override
            public boolean contains(Vec3 p) {
                return p.distance(center) <= radius;
            }

            @Override
            public Vec3 center() {
                return center;
            }

            @Override
            public Vec3 randomPoint(RandomGenerator random) {
                return center.add(Shapes.randomInSphere(radius, 1, random).getFirst());
            }
        };
    }

    /** An upright cylinder from {@code base} up {@code height}. */
    static Region cylinder(Vec3 base, double radius, double height) {
        return new Region() {
            @Override
            public boolean contains(Vec3 p) {
                double dx = p.x() - base.x(), dz = p.z() - base.z();
                return p.y() >= base.y() && p.y() <= base.y() + height && dx * dx + dz * dz <= radius * radius;
            }

            @Override
            public Vec3 center() {
                return base.add(new Vec3(0, height / 2, 0));
            }

            @Override
            public Vec3 randomPoint(RandomGenerator random) {
                double a = random.nextDouble(2 * Math.PI), r = radius * Math.sqrt(random.nextDouble());
                return base.add(new Vec3(Math.cos(a) * r, random.nextDouble(height), Math.sin(a) * r));
            }
        };
    }
}
