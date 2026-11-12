package io.github.imnotstable.msc.renderer;

import io.github.imnotstable.msc.renderer.Look.Tex;

/**
 * Casts rays through a block grid (voxel walk, then ray-box tests inside each occupied cell) and
 * shades what they hit. One instance per thread: it keeps scratch state between calls.
 * All coordinates are grid-local: the region's lowest corner is (0, 0, 0).
 */
final class Tracer {
    static final int BACKGROUND = 0xd6e6f5;
    /** Light per face: -x, +x, -y, +y, -z, +z. The sun stands to the south-east. */
    private static final double[] FACE_LIGHT = {0.62, 0.8, 0.5, 1.0, 0.7, 0.9};
    private static final double[] SUN = unit(0.5, 0.7, 0.45);
    private static final double[][] NORMALS = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};
    private static final double SHADOW = 0.7;
    private static final double OUTLINE = 0.78;

    private final int sx;
    private final int sy;
    private final int sz;
    private final int[] blocks;
    private final Look[] looks;
    private final boolean shadows;
    private final boolean outlines;
    private final boolean heightCue;

    /** Palette index of the first thing the last ray hit, or -1 for background. */
    int firstHit;
    private double r;
    private double g;
    private double b;
    private double a;
    private int hitFace;

    Tracer(int sx, int sy, int sz, int[] blocks, Look[] looks, boolean shadows, boolean outlines, boolean heightCue) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.blocks = blocks;
        this.looks = looks;
        this.shadows = shadows;
        this.outlines = outlines;
        this.heightCue = heightCue;
    }

    static double[] unit(double x, double y, double z) {
        double len = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / len, y / len, z / len};
    }

    /**
     * Colour seen along a ray, as 0xRRGGBB.
     *
     * @param footprint      width of a pixel in blocks at distance 0 (orthographic views)
     * @param footprintPerT  growth of that width per block travelled (perspective views)
     */
    int trace(double ox, double oy, double oz, double dx, double dy, double dz, double footprint, double footprintPerT) {
        r = 0;
        g = 0;
        b = 0;
        a = 0;
        firstHit = -1;
        march(ox, oy, oz, dx, dy, dz, footprint, footprintPerT, false, Double.POSITIVE_INFINITY);
        double rest = 1 - a;
        int ri = (int) Math.round(r + rest * ((BACKGROUND >> 16) & 0xff));
        int gi = (int) Math.round(g + rest * ((BACKGROUND >> 8) & 0xff));
        int bi = (int) Math.round(b + rest * (BACKGROUND & 0xff));
        return (Math.clamp(ri, 0, 255) << 16) | (Math.clamp(gi, 0, 255) << 8) | Math.clamp(bi, 0, 255);
    }

    /**
     * True when something solid (not glass or another see-through block) lies on the ray within {@code maxT}
     * blocks of its start. {@code (dx, dy, dz)} must be a unit vector.
     */
    boolean blocked(double ox, double oy, double oz, double dx, double dy, double dz, double maxT) {
        return march(ox, oy, oz, dx, dy, dz, 0, 0, true, maxT);
    }

    /**
     * Walks the grid along a ray. In shadow mode it only answers "is something solid in the way before
     * {@code maxT}" (returns true); otherwise it accumulates colour front to back until the ray is opaque.
     */
    private boolean march(double ox, double oy, double oz, double dx, double dy, double dz, double footprint,
            double footprintPerT, boolean shadowMode, double maxT) {
        double tEnter = 0;
        double tLeave = Double.POSITIVE_INFINITY;
        int face = -1;
        double[] o = {ox, oy, oz};
        double[] d = {dx, dy, dz};
        int[] size = {sx, sy, sz};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-12) {
                if (o[axis] < 0 || o[axis] > size[axis]) {
                    return false;
                }
                continue;
            }
            double t1 = -o[axis] / d[axis];
            double t2 = (size[axis] - o[axis]) / d[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > tEnter) {
                tEnter = t1;
                face = axis * 2 + (d[axis] > 0 ? 0 : 1);
            }
            tLeave = Math.min(tLeave, t2);
        }
        if (tEnter > tLeave) {
            return false;
        }
        double t = tEnter;
        int[] cell = new int[3];
        int[] step = new int[3];
        double[] tNext = new double[3];
        double[] tDelta = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double p = o[axis] + d[axis] * (t + 1e-9);
            cell[axis] = Math.clamp((int) Math.floor(p), 0, size[axis] - 1);
            if (Math.abs(d[axis]) < 1e-12) {
                tNext[axis] = Double.POSITIVE_INFINITY;
                tDelta[axis] = Double.POSITIVE_INFINITY;
            } else if (d[axis] > 0) {
                step[axis] = 1;
                tNext[axis] = (cell[axis] + 1 - o[axis]) / d[axis];
                tDelta[axis] = 1 / d[axis];
            } else {
                step[axis] = -1;
                tNext[axis] = (cell[axis] - o[axis]) / d[axis];
                tDelta[axis] = -1 / d[axis];
            }
        }
        while (true) {
            int index = blocks[(cell[1] * sz + cell[2]) * sx + cell[0]];
            Look look = index < 0 ? null : looks[index];
            if (look != null && !(shadowMode && look.translucent)) {
                if (look.full) {
                    // A ray that starts inside a full block (face -1) looks out of it, not at it.
                    if (face >= 0) {
                        if (shadowMode) {
                            return t <= maxT;
                        }
                        hit(index, look, Box.FULL, face, t, cell, o, d, footprint + footprintPerT * t);
                    }
                } else {
                    double after = shadowMode ? 1e-4 : 1e-9;
                    while (true) {
                        Box nearest = null;
                        double nearestT = Double.POSITIVE_INFINITY;
                        int nearestFace = 0;
                        for (Box box : look.boxes) {
                            double bt = hitBox(box, cell, o, d);
                            if (bt > after && bt < nearestT) {
                                nearest = box;
                                nearestT = bt;
                                nearestFace = hitFace;
                            }
                        }
                        if (nearest == null) {
                            break;
                        }
                        if (shadowMode) {
                            return nearestT <= maxT;
                        }
                        hit(index, look, nearest, nearestFace, nearestT, cell, o, d, footprint + footprintPerT * nearestT);
                        if (a > 0.98) {
                            break;
                        }
                        after = nearestT;
                    }
                }
                if (a > 0.98) {
                    return true;
                }
            }
            int axis = tNext[0] <= tNext[1] ? (tNext[0] <= tNext[2] ? 0 : 2) : (tNext[1] <= tNext[2] ? 1 : 2);
            t = tNext[axis];
            if (t > maxT) {
                return false;
            }
            cell[axis] += step[axis];
            if (cell[axis] < 0 || cell[axis] >= size[axis]) {
                return false;
            }
            tNext[axis] += tDelta[axis];
            face = axis * 2 + (step[axis] > 0 ? 0 : 1);
        }
    }

    /** Distance at which the ray enters a box of a cell, or -1 if it misses; sets {@link #hitFace}. */
    private double hitBox(Box box, int[] cell, double[] o, double[] d) {
        double[] lo = {cell[0] + box.x0(), cell[1] + box.y0(), cell[2] + box.z0()};
        double[] hi = {cell[0] + box.x1(), cell[1] + box.y1(), cell[2] + box.z1()};
        double near = Double.NEGATIVE_INFINITY;
        double far = Double.POSITIVE_INFINITY;
        int face = 0;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(d[axis]) < 1e-12) {
                if (o[axis] < lo[axis] || o[axis] > hi[axis]) {
                    return -1;
                }
                continue;
            }
            double t1 = (lo[axis] - o[axis]) / d[axis];
            double t2 = (hi[axis] - o[axis]) / d[axis];
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            if (t1 > near) {
                near = t1;
                face = axis * 2 + (d[axis] > 0 ? 0 : 1);
            }
            far = Math.min(far, t2);
        }
        if (near > far) {
            return -1;
        }
        hitFace = face;
        return near;
    }

    /** Shades one surface hit and blends it under what the ray has already collected. */
    private void hit(int index, Look look, Box box, int face, double t, int[] cell, double[] o, double[] d,
            double footprint) {
        double wx = o[0] + d[0] * t;
        double wy = o[1] + d[1] * t;
        double wz = o[2] + d[2] * t;
        double lx = Math.clamp(wx - cell[0], 0, 1);
        double ly = Math.clamp(wy - cell[1], 0, 1);
        double lz = Math.clamp(wz - cell[2], 0, 1);
        int kind = look.faceKind[face];
        Tex tex = look.tex[kind];
        int argb;
        if (tex == null) {
            argb = look.argb[kind];
        } else if (footprint > 0.25) {
            argb = tex.average();
        } else {
            argb = switch (face >> 1) {
                case 0 -> tex.sample(lz, 1 - ly);
                case 1 -> tex.sample(lx, lz);
                default -> tex.sample(lx, 1 - ly);
            };
        }
        double alpha = (argb >>> 24) / 255.0;
        if (alpha < 0.1) {
            return;
        }
        if (firstHit < 0) {
            firstHit = index;
        }
        double light = FACE_LIGHT[face];
        if (heightCue) {
            light *= 0.72 + 0.28 * wy / sy;
        }
        if (outlines && footprint <= 1.0 / 6) {
            double edge = switch (face >> 1) {
                case 0 -> Math.min(Math.min(ly - box.y0(), box.y1() - ly), Math.min(lz - box.z0(), box.z1() - lz));
                case 1 -> Math.min(Math.min(lx - box.x0(), box.x1() - lx), Math.min(lz - box.z0(), box.z1() - lz));
                default -> Math.min(Math.min(lx - box.x0(), box.x1() - lx), Math.min(ly - box.y0(), box.y1() - ly));
            };
            if (edge < footprint * 0.6) {
                light *= OUTLINE;
            }
        }
        double[] n = NORMALS[face];
        if (shadows && n[0] * SUN[0] + n[1] * SUN[1] + n[2] * SUN[2] > 0) {
            // Shadow mode never touches the colour state, so the nested walk is safe here.
            boolean dark = march(wx + n[0] * 1e-3, wy + n[1] * 1e-3, wz + n[2] * 1e-3, SUN[0], SUN[1], SUN[2], 0, 0, true,
                    Double.POSITIVE_INFINITY);
            if (dark) {
                light *= SHADOW;
            }
        }
        double weight = (1 - a) * alpha * light;
        r += weight * ((argb >> 16) & 0xff);
        g += weight * ((argb >> 8) & 0xff);
        b += weight * (argb & 0xff);
        a += (1 - a) * alpha;
    }
}
