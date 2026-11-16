package io.github.imnotstable.msc.textures;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * An animated texture: square frames stacked top to bottom in one PNG, plus a {@code .png.mcmeta}
 * file that tells the client how fast to play them.
 *
 * @param frametime   game ticks per frame (20 ticks is one second)
 * @param interpolate blend between frames on the client
 */
public record Animation(List<Texture> frames, int frametime, boolean interpolate) {
    public static final int MAX_FRAMES = 64;

    public Animation {
        frames = List.copyOf(frames);
        checkCounts(frames.size(), frametime);
        int w = frames.getFirst().width();
        for (Texture f : frames) {
            if (f.width() != w || f.height() != w) {
                throw new TextureException("every frame must be square and " + w + "x" + w);
            }
        }
    }

    /** Checked before any frame is built, so a huge count fails fast instead of filling the heap. */
    private static void checkCounts(int frames, int frametime) {
        if (frames < 1 || frames > MAX_FRAMES) {
            throw new TextureException("an animation needs 1 to " + MAX_FRAMES + " frames, not " + frames);
        }
        if (frametime < 1) {
            throw new TextureException("frametime must be at least 1 tick");
        }
    }

    /** Split a vertical strip into square frames. */
    public static Animation fromStrip(Texture strip, int frametime, boolean interpolate) {
        int w = strip.width();
        if (strip.height() % w != 0) {
            throw new TextureException("strip height " + strip.height() + " is not a multiple of width " + w);
        }
        checkCounts(strip.height() / w, frametime);
        List<Texture> frames = new ArrayList<>();
        for (int f = 0; f < strip.height() / w; f++) {
            Texture t = Texture.square(w);
            for (int y = 0; y < w; y++) {
                for (int x = 0; x < w; x++) {
                    t.set(x, y, strip.get(x, f * w + y));
                }
            }
            frames.add(t);
        }
        return new Animation(frames, frametime, interpolate);
    }

    /** The frames stacked top to bottom, as the game expects. */
    public Texture strip() {
        int w = frames.getFirst().width();
        Texture out = new Texture(w, w * frames.size());
        for (int f = 0; f < frames.size(); f++) {
            out.draw(frames.get(f), 0, f * w);
        }
        return out;
    }

    /** The {@code .png.mcmeta} content. */
    public JsonObject mcmeta() {
        JsonObject animation = new JsonObject();
        animation.addProperty("frametime", frametime);
        if (interpolate) {
            animation.addProperty("interpolate", true);
        }
        JsonObject root = new JsonObject();
        root.add("animation", animation);
        return root;
    }

    /**
     * Frames of one full scroll loop on a {@code size}-pixel square: the fewest steps of (dx, dy) after which both
     * axes are back at the start, the lcm over the axes of {@code size / gcd(size, |step| mod size)}. 1 means the
     * step moves nothing.
     */
    public static int scrollLoop(int size, int dx, int dy) {
        if (size < 1) {
            throw new TextureException("size must be at least 1");
        }
        long a = axisLoop(size, dx);
        long b = axisLoop(size, dy);
        return Math.toIntExact(a / gcd(a, b) * b);
    }

    private static long axisLoop(int size, int step) {
        return size / gcd(size, Math.floorMod(step, size));
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    /**
     * {@link #scroll(Texture, int, int, int, int)} with one full loop of frames ({@link #scrollLoop}), so the last
     * frame plus one step is the first. Refuses a loop longer than {@link #MAX_FRAMES}, saying what to give instead.
     */
    public static Animation scroll(Texture base, int dx, int dy, int frametime) {
        int size = checkScroll(base, dx, dy);
        int loop = scrollLoop(size, dx, dy);
        if (loop > MAX_FRAMES) {
            throw new TextureException("scroll " + dx + "," + dy + " loops after " + loop + " frames on " + size + "x"
                    + size + ", over the " + MAX_FRAMES + " limit: give frames (at most " + MAX_FRAMES
                    + ") or a step that loops within " + MAX_FRAMES + " frames");
        }
        return scroll(base, dx, dy, loop, frametime);
    }

    /** Slide the texture by (dx, dy) pixels per frame, wrapping, for flowing liquids and conveyors. */
    public static Animation scroll(Texture base, int dx, int dy, int frames, int frametime) {
        checkScroll(base, dx, dy);
        checkCounts(frames, frametime);
        List<Texture> out = new ArrayList<>();
        for (int f = 0; f < frames; f++) {
            Texture t = Texture.square(base.width());
            for (int y = 0; y < base.height(); y++) {
                for (int x = 0; x < base.width(); x++) {
                    t.set(x, y, base.getWrapped(x - f * dx, y - f * dy));
                }
            }
            out.add(t);
        }
        return new Animation(out, frametime, false);
    }

    /** Square texture and a step that moves it; returns the size. */
    private static int checkScroll(Texture base, int dx, int dy) {
        if (base.width() != base.height()) {
            throw new TextureException("scroll needs a square texture");
        }
        int size = base.width();
        if (scrollLoop(size, dx, dy) == 1) {
            throw new TextureException("scroll " + dx + "," + dy + " moves nothing on " + size + "x" + size
                    + ": give a step that is not a multiple of " + size);
        }
        return size;
    }

    /**
     * Make the ramp's colours brighten and dim in a wave across the texture, for glowing ores and magic
     * items. Pixels whose colour is not in the ramp stay still.
     */
    public static Animation pulse(Texture base, Ramp ramp, int frames, int frametime) {
        if (base.width() != base.height()) {
            throw new TextureException("pulse needs a square texture");
        }
        checkCounts(frames, frametime);
        List<Texture> out = new ArrayList<>();
        boolean changed = false;
        for (int f = 0; f < frames; f++) {
            Texture t = base.copy();
            for (int y = 0; y < base.height(); y++) {
                for (int x = 0; x < base.width(); x++) {
                    int i = ramp.colors().indexOf(base.get(x, y));
                    if (i < 0) {
                        continue;
                    }
                    double phase = 2 * Math.PI * ((double) f / frames + (x + y) / (2.0 * base.width()));
                    int shade = ramp.get(i + (int) Math.round(Math.sin(phase)));
                    changed |= shade != base.get(x, y);
                    t.set(x, y, shade);
                }
            }
            out.add(t);
        }
        if (!changed) {
            // Every frame would equal the base: an animation that shows nothing (#545), as a scroll step of 0 (#534).
            throw new TextureException("pulse changes no pixel: the texture needs pixels in at least two shades of "
                    + "the ramp (one flat colour cannot glow)");
        }
        return new Animation(out, frametime, true);
    }
}
