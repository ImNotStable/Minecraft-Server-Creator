package io.github.imnotstable.msc.core.building;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.BlockPos;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;

/** One part of a build: a fill, a shape, a few blocks or a stamped structure. Steps draw onto a {@link Canvas}. */
public sealed interface Step {
    /** Every block the step may set lies inside this box. */
    Region bounds();

    void draw(Canvas canvas) throws InterruptedException;

    /** A box, solid or as a room: {@code hollow} (shell, air inside), {@code outline} (shell only), {@code walls}. */
    record Fill(Region region, BlockPattern block, String shape, List<String> replace) implements Step {
        public static final List<String> SHAPES = List.of("solid", "hollow", "outline", "walls");

        public Fill {
            if (!SHAPES.contains(shape)) {
                throw new ToolException("unknown fill shape " + shape + "; use one of " + SHAPES);
            }
        }

        @Override
        public Region bounds() {
            return region;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(region, replace);
            BlockPos lo = region.min();
            BlockPos hi = region.max();
            for (int y = lo.y(); y <= hi.y(); y++) {
                for (int z = lo.z(); z <= hi.z(); z++) {
                    for (int x = lo.x(); x <= hi.x(); x++) {
                        boolean side = x == lo.x() || x == hi.x() || z == lo.z() || z == hi.z();
                        boolean shell = side || y == lo.y() || y == hi.y();
                        String state = switch (shape) {
                            case "hollow" -> shell ? block.at(x, y, z) : "minecraft:air";
                            case "outline" -> shell ? block.at(x, y, z) : null;
                            case "walls" -> side ? block.at(x, y, z) : null;
                            default -> block.at(x, y, z);
                        };
                        if (state != null && mask.allows(x, y, z)) {
                            c.set(x, y, z, state);
                        }
                    }
                }
            }
        }
    }

    /** Single blocks. */
    record Set(String world, List<BlockPos> positions, List<String> states, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Shapes.box(world, positions);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            for (int i = 0; i < positions.size(); i++) {
                BlockPos p = positions.get(i);
                if (mask.allows(p.x(), p.y(), p.z())) {
                    c.set(p.x(), p.y(), p.z(), states.get(i));
                }
            }
        }
    }

    /**
     * An upright cylinder standing on {@code base} (its bottom centre block). {@code inner >= 0} cuts a
     * hole of that radius; {@code thickness > 0} keeps only a wall that thick (a ring or tube).
     */
    record Cylinder(String world, BlockPos base, int radius, int height, int inner, int thickness, BlockPattern block,
                    List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int top = base.y() + Math.max(height, 1) - 1;
            return Region.of(world, base.x() - radius, base.y(), base.z() - radius, base.x() + radius, top, base.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            boolean[] disc = Shapes.disc(radius);
            if (thickness > 0) {
                disc = Shapes.shell2d(disc, radius, thickness);
            }
            if (inner >= 0) {
                boolean[] hole = Shapes.disc(inner);
                int n = 2 * radius + 1;
                int m = 2 * inner + 1;
                for (int dz = -inner; dz <= inner; dz++) {
                    for (int dx = -inner; dx <= inner; dx++) {
                        if (hole[(dz + inner) * m + dx + inner]) {
                            disc[(dz + radius) * n + dx + radius] = false;
                        }
                    }
                }
            }
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int n = 2 * radius + 1;
            for (int y = b.min().y(); y <= b.max().y(); y++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = base.x() + dx;
                        int z = base.z() + dz;
                        if (disc[(dz + radius) * n + dx + radius] && mask.allows(x, y, z)) {
                            c.set(x, y, z, block.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /** A ball around {@code center}, or with {@code dome} its upper half standing on {@code center}'s layer. */
    record Sphere(String world, BlockPos center, int radius, int thickness, boolean dome, BlockPattern block,
                  List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, center.x() - radius, dome ? center.y() : center.y() - radius, center.z() - radius,
                    center.x() + radius, center.y() + radius, center.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            boolean[] ball = Shapes.ball(radius);
            if (thickness > 0) {
                ball = Shapes.shell3d(ball, radius, thickness);
            }
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int n = 2 * radius + 1;
            for (int dy = dome ? 0 : -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        int x = center.x() + dx;
                        int y = center.y() + dy;
                        int z = center.z() + dz;
                        if (ball[((dy + radius) * n + dz + radius) * n + dx + radius] && mask.allows(x, y, z)) {
                            c.set(x, y, z, block.at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * A straight line from {@code from} to {@code to}, {@code width} blocks across (a round brush). An even width has
     * no middle block, so its brush reaches one block further toward +x, +y and +z than toward -x, -y and -z.
     */
    record Line(String world, BlockPos from, BlockPos to, int width, BlockPattern block, List<String> replace)
            implements Step {
        @Override
        public Region bounds() {
            int lo = Shapes.brushLow(width);
            int hi = width - 1 - lo;
            return Region.of(world, Math.min(from.x(), to.x()) - lo, Math.min(from.y(), to.y()) - lo,
                    Math.min(from.z(), to.z()) - lo, Math.max(from.x(), to.x()) + hi, Math.max(from.y(), to.y()) + hi,
                    Math.max(from.z(), to.z()) + hi);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            // A block reached twice gets the same answer: the pattern depends only on the position, and after the
            // first visit the mask sees either this step's own block there or what it already refused.
            Shapes.sweep(Shapes.line(from, to), width, (x, y, z) -> {
                if (mask.allows(x, y, z)) {
                    c.set(x, y, z, block.at(x, y, z));
                }
            });
        }
    }

    /**
     * A spiral rising from {@code base} (its bottom centre block): {@code turns} full turns over {@code height}
     * layers, clockwise seen from above (counter-clockwise when negative), starting at {@code start} degrees
     * (0 = north, 90 = east). {@code width} blocks across, from the radius inward: a spiral stair's treads.
     */
    record Helix(String world, BlockPos base, int radius, int height, double turns, int width, int start,
                 BlockPattern block, List<String> replace) implements Step {
        @Override
        public Region bounds() {
            return Region.of(world, base.x() - radius, base.y(), base.z() - radius, base.x() + radius,
                    base.y() + height - 1, base.z() + radius);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            double arc = Math.abs(turns) * 2 * Math.PI * radius;
            int samples = (int) Math.ceil(Math.max(arc, height) * 4) + 1;
            for (int i = 0; i <= samples; i++) {
                double t = (double) i / samples;
                double angle = Math.toRadians(start) + turns * 2 * Math.PI * t;
                int y = base.y() + Math.min(height - 1, (int) Math.floor(t * height));
                for (int r = radius - width + 1; r <= radius; r++) {
                    int x = base.x() + (int) Math.round(r * Math.sin(angle));
                    int z = base.z() - (int) Math.round(r * Math.cos(angle));
                    if (mask.allows(x, y, z)) {
                        c.set(x, y, z, block.at(x, y, z));
                    }
                }
            }
        }
    }

    /** Several steps drawn in order, such as the copies of one step made by {@code repeat=}. */
    record Group(List<Step> steps) implements Step {
        @Override
        public Region bounds() {
            Region out = null;
            for (Step s : steps) {
                Region b = s.bounds();
                out = out == null ? b : Region.of(b.world(), Math.min(out.min().x(), b.min().x()),
                        Math.min(out.min().y(), b.min().y()), Math.min(out.min().z(), b.min().z()),
                        Math.max(out.max().x(), b.max().x()), Math.max(out.max().y(), b.max().y()),
                        Math.max(out.max().z(), b.max().z()));
            }
            return out;
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            c.readAhead(b);
            try {
                for (Step s : steps) {
                    s.draw(c);
                }
            } finally {
                c.endReadAhead(b);
            }
        }
    }

    /**
     * Rings around {@code base} out to each radius in turn, {@code height} blocks tall: an island's
     * grass, then beach, then water. {@code jitter} wobbles every edge by up to that many blocks.
     */
    record Bands(String world, BlockPos base, int height, int[] radii, List<BlockPattern> blocks, int jitter, long seed,
                 List<String> replace) implements Step {
        @Override
        public Region bounds() {
            int r = radii[radii.length - 1] + jitter;
            return Region.of(world, base.x() - r, base.y(), base.z() - r, base.x() + r, base.y() + Math.max(height, 1) - 1,
                    base.z() + r);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Region b = bounds();
            Canvas.Mask mask = c.mask(b, replace);
            int r = radii[radii.length - 1] + jitter;
            for (int dz = -r; dz <= r; dz++) {
                for (int dx = -r; dx <= r; dx++) {
                    double d = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double angle = Math.atan2(dz, dx);
                    int band = -1;
                    for (int i = 0; i < radii.length; i++) {
                        double edge = radii[i] + 0.5 + jitter * Shapes.wobble(angle, seed + i);
                        if (d < edge) {
                            band = i;
                            break;
                        }
                    }
                    if (band < 0) {
                        continue;
                    }
                    int x = base.x() + dx;
                    int z = base.z() + dz;
                    for (int y = b.min().y(); y <= b.max().y(); y++) {
                        if (mask.allows(x, y, z)) {
                            c.set(x, y, z, blocks.get(band).at(x, y, z));
                        }
                    }
                }
            }
        }
    }

    /**
     * A structure placed once per position. {@code structure} starts at 0,0,0 and is already turned;
     * {@code -1} cells and, without {@code air}, air cells leave the world as it is.
     */
    record Stamp(String world, List<Placement> placements, boolean air, List<String> replace) implements Step {
        /** One copy: the structure, already turned, with its lowest corner at {@code at}. */
        public record Placement(BlockGrid structure, BlockPos at) {
        }

        public Stamp {
            if (placements.isEmpty()) {
                throw new ToolException("structure has no position");
            }
        }

        @Override
        public Region bounds() {
            int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
            int[] hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (Placement p : placements) {
                Region r = p.structure().region();
                lo[0] = Math.min(lo[0], p.at().x());
                lo[1] = Math.min(lo[1], p.at().y());
                lo[2] = Math.min(lo[2], p.at().z());
                hi[0] = Math.max(hi[0], p.at().x() + r.sizeX() - 1);
                hi[1] = Math.max(hi[1], p.at().y() + r.sizeY() - 1);
                hi[2] = Math.max(hi[2], p.at().z() + r.sizeZ() - 1);
            }
            return Region.of(world, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
        }

        @Override
        public void draw(Canvas c) throws InterruptedException {
            Canvas.Mask mask = c.mask(bounds(), replace);
            for (Placement p : placements) {
                BlockGrid g = p.structure();
                Region r = g.region();
                int i = 0;
                for (int y = 0; y < r.sizeY(); y++) {
                    for (int z = 0; z < r.sizeZ(); z++) {
                        for (int x = 0; x < r.sizeX(); x++) {
                            int b = g.blocks()[i++];
                            if (b < 0) {
                                continue;
                            }
                            String state = g.palette().get(b);
                            if (!air && io.github.imnotstable.msc.core.world.BlockNames.isAir(state)) {
                                continue;
                            }
                            int wx = p.at().x() + x;
                            int wy = p.at().y() + y;
                            int wz = p.at().z() + z;
                            if (mask.allows(wx, wy, wz)) {
                                c.set(wx, wy, wz, state);
                            }
                        }
                    }
                }
            }
        }
    }
}
