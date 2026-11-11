package io.github.imnotstable.msc.renderer;

import java.util.Locale;

/** Where the camera is. Parse one from text with {@link #parse(String)}. */
public sealed interface View {
    /** Straight-on view with no perspective: a map ({@code TOP}) or an elevation of one side. */
    record Ortho(Side from) implements View {
    }

    /** Which side an {@link Ortho} view looks from. {@code SOUTH} stands south of the build and looks north. */
    enum Side {
        TOP, NORTH, SOUTH, EAST, WEST
    }

    /** Three-quarter view from above with no perspective; the camera sits beyond the named corner. */
    record Isometric(Corner from) implements View {
    }

    enum Corner {
        NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST
    }

    /**
     * A first-person camera, in world coordinates and Minecraft angles: yaw 0 looks south (+z),
     * 90 west, 180 north, -90 east; pitch 90 looks straight down.
     */
    record Camera(double x, double y, double z, double yaw, double pitch, double fov) implements View {
        public Camera {
            if (!(fov >= 10 && fov <= 140)) {
                throw new IllegalArgumentException("fov must be between 10 and 140 degrees");
            }
        }

        /** A camera at one point looking at another. */
        public static Camera lookingAt(double x, double y, double z, double tx, double ty, double tz, double fov) {
            double dx = tx - x;
            double dy = ty - y;
            double dz = tz - z;
            double flat = Math.hypot(dx, dz);
            if (flat == 0 && dy == 0) {
                throw new IllegalArgumentException("eye and look are the same point");
            }
            return new Camera(x, y, z, Math.toDegrees(Math.atan2(-dx, dz)), Math.toDegrees(Math.atan2(-dy, flat)), fov);
        }
    }

    /**
     * Parses the text form a tool argument uses:
     * {@code top}, {@code north}, {@code south}, {@code east}, {@code west},
     * {@code iso} (same as {@code iso-se}), {@code iso-ne}, {@code iso-nw}, {@code iso-sw},
     * {@code eye x,y,z look x,y,z [fov N]} or {@code eye x,y,z yaw N pitch N [fov N]}.
     *
     * @throws IllegalArgumentException with a message that lists the accepted forms
     */
    static View parse(String text) {
        String s = text.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "top":
                return new Ortho(Side.TOP);
            case "north":
                return new Ortho(Side.NORTH);
            case "south":
                return new Ortho(Side.SOUTH);
            case "east":
                return new Ortho(Side.EAST);
            case "west":
                return new Ortho(Side.WEST);
            case "iso", "iso-se":
                return new Isometric(Corner.SOUTH_EAST);
            case "iso-ne":
                return new Isometric(Corner.NORTH_EAST);
            case "iso-nw":
                return new Isometric(Corner.NORTH_WEST);
            case "iso-sw":
                return new Isometric(Corner.SOUTH_WEST);
            default:
                break;
        }
        String[] words = s.split("\\s+");
        try {
            if (words[0].equals("eye") && words.length % 2 == 0) {
                double[] eye = null;
                double[] look = null;
                double yaw = 0;
                double pitch = 0;
                double fov = 70;
                for (int i = 0; i < words.length; i += 2) {
                    String value = words[i + 1];
                    switch (words[i]) {
                        case "eye" -> eye = point(value);
                        case "look" -> look = point(value);
                        case "yaw" -> yaw = Double.parseDouble(value);
                        case "pitch" -> pitch = Double.parseDouble(value);
                        case "fov" -> fov = Double.parseDouble(value);
                        default -> throw new IllegalArgumentException(words[i]);
                    }
                }
                if (eye != null) {
                    return look != null
                            ? Camera.lookingAt(eye[0], eye[1], eye[2], look[0], look[1], look[2], fov)
                            : new Camera(eye[0], eye[1], eye[2], yaw, pitch, fov);
                }
            }
        } catch (RuntimeException e) {
            // fall through to the one message that lists every form
        }
        throw new IllegalArgumentException("bad view \"" + text + "\"; use top, north, south, east, west, iso, iso-ne, "
                + "iso-nw, iso-sw, \"eye x,y,z look x,y,z\" or \"eye x,y,z yaw N pitch N\" (optional \"fov N\", 10-140)");
    }

    private static double[] point(String text) {
        String[] parts = text.split(",");
        if (parts.length != 3) {
            throw new IllegalArgumentException(text);
        }
        return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
    }
}
