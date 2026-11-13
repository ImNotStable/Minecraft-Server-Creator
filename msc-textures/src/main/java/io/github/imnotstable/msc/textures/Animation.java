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

    /** Slide the texture by (dx, dy) pixels per frame, wrapping, for flowing liquids and conveyors. */
    public static Animation scroll(Texture base, int dx, int dy, int frames, int frametime) {
        if (base.width() != base.height()) {
            throw new TextureException("scroll needs a square texture");
        }
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
        for (int f = 0; f < frames; f++) {
            Texture t = base.copy();
            for (int y = 0; y < base.height(); y++) {
                for (int x = 0; x < base.width(); x++) {
                    int i = ramp.colors().indexOf(base.get(x, y));
                    if (i < 0) {
                        continue;
                    }
                    double phase = 2 * Math.PI * ((double) f / frames + (x + y) / (2.0 * base.width()));
                    t.set(x, y, ramp.get(i + (int) Math.round(Math.sin(phase))));
                }
            }
            out.add(t);
        }
        return new Animation(out, frametime, true);
    }
}
