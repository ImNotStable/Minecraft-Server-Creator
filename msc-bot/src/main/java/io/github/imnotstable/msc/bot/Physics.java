package io.github.imnotstable.msc.bot;

import java.util.List;

/**
 * Player movement for one tick, after vanilla's LivingEntity.travel: input acceleration scaled by ground friction,
 * collisions resolved one axis at a time with a 0.6 step-up, then gravity, drag and friction.
 */
final class Physics {
    // Vanilla stores player dimensions as floats; using the same values keeps the box identical to the
    // server's, so stopping at a wall never leaves the box a hair inside the block (which triggers a correction).
    static final double WIDTH = 0.6f;
    static final double HEIGHT = 1.8f;
    static final double STEP = 0.6;
    static final double GRAVITY = 0.08;
    static final double JUMP = 0.42;

    /** What the player presses this tick. {@code forward} and {@code strafe} are -1, 0 or 1; yaw in degrees. */
    record Input(double forward, double strafe, boolean jump, boolean sprint, float yaw) {
        static final Input NONE = new Input(0, 0, false, false, 0);
    }

    double x;
    double y;
    double z;
    double vx;
    double vy;
    double vz;
    boolean onGround;
    boolean horizontalCollision;

    Box box() {
        return new Box(x - WIDTH / 2, y, z - WIDTH / 2, x + WIDTH / 2, y + HEIGHT, z + WIDTH / 2);
    }

    void tick(Input in, WorldView world) throws Exception {
        world.ensure(box().expand(vx * 3, vy * 3 - 1, vz * 3));
        double yaw = Math.toRadians(in.yaw());
        if (in.jump() && onGround) {
            vy = JUMP;
            if (in.sprint() && in.forward() > 0) {
                vx += -Math.sin(yaw) * 0.2;
                vz += Math.cos(yaw) * 0.2;
            }
        }
        double slip = onGround ? BlockShapes.slipperiness(world.state((int) Math.floor(x),
                (int) Math.floor(y - 0.5000001), (int) Math.floor(z))) : 1.0;
        double friction = slip * 0.91;
        double speed = onGround ? (in.sprint() ? 0.13 : 0.1) * (0.21600002 / (slip * slip * slip))
                : in.sprint() ? 0.025999999 : 0.02;
        double ix = in.strafe() * 0.98;
        double iz = in.forward() * 0.98;
        double len = ix * ix + iz * iz;
        if (len >= 1.0E-7) {
            if (len > 1) {
                double n = Math.sqrt(len);
                ix /= n;
                iz /= n;
            }
            ix *= speed;
            iz *= speed;
            double sin = Math.sin(yaw);
            double cos = Math.cos(yaw);
            vx += ix * cos - iz * sin;
            vz += iz * cos + ix * sin;
        }
        move(world);
        vy = (vy - GRAVITY) * 0.98;
        vx *= friction;
        vz *= friction;
        if (Math.abs(vx) < 0.003) {
            vx = 0;
        }
        if (Math.abs(vz) < 0.003) {
            vz = 0;
        }
    }

    private void move(WorldView world) {
        Box start = box();
        List<Box> boxes = world.collisions(start.expand(vx, vy, vz).expand(0, -STEP, 0).expand(0, STEP, 0));
        double[] moved = collide(start, vx, vy, vz, boxes);
        boolean wasGroundish = onGround || vy < 0 && moved[1] != vy;
        if (wasGroundish && (moved[0] != vx || moved[2] != vz)) {
            // Try stepping up onto the obstacle, as vanilla does for blocks up to 0.6 high.
            double[] up = collide(start, 0, STEP, 0, boxes);
            double[] across = collide(start.offset(0, up[1], 0), vx, 0, vz, boxes);
            double[] down = collide(start.offset(across[0], up[1], across[2]), 0, -up[1] + Math.min(0, vy), 0, boxes);
            if (across[0] * across[0] + across[2] * across[2] > moved[0] * moved[0] + moved[2] * moved[2] + 1e-7) {
                moved = new double[] {across[0], up[1] + down[1], across[2]};
            }
        }
        boolean hitX = moved[0] != vx;
        boolean hitY = moved[1] != vy;
        boolean hitZ = moved[2] != vz;
        x += moved[0];
        y += moved[1];
        z += moved[2];
        onGround = hitY && vy < 0;
        horizontalCollision = hitX || hitZ;
        if (hitX) {
            vx = 0;
        }
        if (hitY) {
            vy = 0;
        }
        if (hitZ) {
            vz = 0;
        }
    }

    /** Clip a move against the boxes: y first, then the larger horizontal axis, as vanilla does. */
    static double[] collide(Box b, double dx, double dy, double dz, List<Box> boxes) {
        for (Box o : boxes) {
            dy = o.clipY(b, dy);
        }
        b = b.offset(0, dy, 0);
        boolean xFirst = Math.abs(dx) >= Math.abs(dz);
        if (xFirst) {
            for (Box o : boxes) {
                dx = o.clipX(b, dx);
            }
            b = b.offset(dx, 0, 0);
        }
        for (Box o : boxes) {
            dz = o.clipZ(b, dz);
        }
        b = b.offset(0, 0, dz);
        if (!xFirst) {
            for (Box o : boxes) {
                dx = o.clipX(b, dx);
            }
        }
        return new double[] {dx, dy, dz};
    }

    /** True when there is ground under the player's box at (px, pz): any collision box just below its feet. */
    boolean supportedAt(double px, double pz, WorldView world) {
        Box feet = new Box(px - WIDTH / 2, y - 0.1, pz - WIDTH / 2, px + WIDTH / 2, y, pz + WIDTH / 2);
        return !world.collisions(feet).isEmpty();
    }
}
