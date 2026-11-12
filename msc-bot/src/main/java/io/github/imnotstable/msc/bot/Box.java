package io.github.imnotstable.msc.bot;

/** An axis-aligned box in world coordinates. */
record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    Box offset(double dx, double dy, double dz) {
        return new Box(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz);
    }

    /** Grown to cover a move by (dx, dy, dz). */
    Box expand(double dx, double dy, double dz) {
        return new Box(dx < 0 ? minX + dx : minX, dy < 0 ? minY + dy : minY, dz < 0 ? minZ + dz : minZ,
                dx > 0 ? maxX + dx : maxX, dy > 0 ? maxY + dy : maxY, dz > 0 ? maxZ + dz : maxZ);
    }

    boolean intersects(Box o) {
        return minX < o.maxX && maxX > o.minX && minY < o.maxY && maxY > o.minY && minZ < o.maxZ && maxZ > o.minZ;
    }

    /** How far {@code mover} can go along x before touching this box (vanilla VoxelShape.collide, one box). */
    double clipX(Box mover, double dx) {
        if (mover.maxY <= minY || mover.minY >= maxY || mover.maxZ <= minZ || mover.minZ >= maxZ) {
            return dx;
        }
        if (dx > 0 && mover.maxX <= minX) {
            return Math.min(dx, minX - mover.maxX);
        }
        if (dx < 0 && mover.minX >= maxX) {
            return Math.max(dx, maxX - mover.minX);
        }
        return dx;
    }

    double clipY(Box mover, double dy) {
        if (mover.maxX <= minX || mover.minX >= maxX || mover.maxZ <= minZ || mover.minZ >= maxZ) {
            return dy;
        }
        if (dy > 0 && mover.maxY <= minY) {
            return Math.min(dy, minY - mover.maxY);
        }
        if (dy < 0 && mover.minY >= maxY) {
            return Math.max(dy, maxY - mover.minY);
        }
        return dy;
    }

    double clipZ(Box mover, double dz) {
        if (mover.maxX <= minX || mover.minX >= maxX || mover.maxY <= minY || mover.minY >= maxY) {
            return dz;
        }
        if (dz > 0 && mover.maxZ <= minZ) {
            return Math.min(dz, minZ - mover.maxZ);
        }
        if (dz < 0 && mover.minZ >= maxZ) {
            return Math.max(dz, maxZ - mover.minZ);
        }
        return dz;
    }
}
