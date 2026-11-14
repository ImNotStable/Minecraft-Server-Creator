package io.github.imnotstable.msc.renderer;

import java.util.ArrayList;
import java.util.List;

/**
 * The one placement step for every piece of text the renderer writes on an image: ruler labels, isometric corner
 * labels, hologram and sign boxes and marker numbers. A label goes to the first of its candidate spots where it lies
 * whole inside the image and covers no label placed before it (and, when asked, no marker dot). A label with no such
 * spot is dropped, never cut, so a clipped "100" can not read as "10". Labels are drawn last, after every dot, so no
 * dot covers one.
 */
final class Labels {
    enum Kind { RULER, CORNER, TEXT_BOX, NUMBER }

    record Rect(int x, int y, int w, int h) {
        boolean overlaps(Rect o) {
            return x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h;
        }

        boolean inside(int width, int height) {
            return x >= 0 && y >= 0 && x + w <= width && y + h <= height;
        }
    }

    /**
     * Text in a box: lines centred across the box, the first {@code pad} pixels below its top, on {@code fill} with a
     * one-font-pixel {@code edge} (-1 for no fill or no edge). {@code marker} is the marker's number, 0 for none.
     */
    record Label(Kind kind, int marker, Rect box, List<String> lines, int font, int pad, int edge, int fill, int ink) {
        Label {
            lines = List.copyOf(lines);
        }

        /** One line of text with {@code pad} pixels of {@code fill} (-1 for none) around it, at 0,0. */
        static Label line(Kind kind, int marker, String text, int font, int pad, int fill, int ink) {
            Rect box = new Rect(0, 0, PixelFont.width(text, font) + 2 * pad, PixelFont.HEIGHT * font + 2 * pad);
            return new Label(kind, marker, box, List.of(text), font, pad, -1, fill, ink);
        }

        /** Lines in an edged box, as holograms and signs show them, at 0,0. */
        static Label box(int marker, List<String> lines, int font, int edge, int fill, int ink) {
            int pad = 2 * font;
            int w = lines.stream().mapToInt(text -> PixelFont.width(text, font)).max().orElse(0) + 2 * pad;
            int h = lines.size() * lineHeight(font) - font + 2 * pad;
            return new Label(Kind.TEXT_BOX, marker, new Rect(0, 0, w, h), lines, font, pad, edge, fill, ink);
        }

        Label at(int x, int y) {
            return new Label(kind, marker, new Rect(x, y, box.w, box.h), lines, font, pad, edge, fill, ink);
        }

        String text() {
            return String.join("\n", lines);
        }

        void draw(int[] pixels, int width, int height) {
            if (edge >= 0) {
                PixelFont.fill(pixels, width, height, box.x, box.y, box.w, box.h, edge);
                PixelFont.fill(pixels, width, height, box.x + font, box.y + font, box.w - 2 * font, box.h - 2 * font,
                        fill);
            } else if (fill >= 0) {
                PixelFont.fill(pixels, width, height, box.x, box.y, box.w, box.h, fill);
            }
            for (int i = 0; i < lines.size(); i++) {
                String text = lines.get(i);
                PixelFont.draw(pixels, width, height, box.x + (box.w - PixelFont.width(text, font)) / 2,
                        box.y + pad + i * lineHeight(font), text, font, ink);
            }
        }

        private static int lineHeight(int font) {
            return (PixelFont.HEIGHT + 1) * font;
        }
    }

    private final int width;
    private final int height;
    private final List<Rect> dots = new ArrayList<>();
    private final List<Label> placed = new ArrayList<>();

    Labels(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /** A marker dot that later labels with {@code avoidDots} keep off. */
    void dot(Rect rect) {
        dots.add(rect);
    }

    /**
     * Places a label at the first free spot (top-left corners, in order of preference) and returns it there, or
     * returns null and places nothing when no spot is free.
     */
    Label place(Label label, boolean avoidDots, List<int[]> spots) {
        for (int[] spot : spots) {
            Label there = label.at(spot[0], spot[1]);
            Rect box = there.box();
            if (box.inside(width, height) && placed.stream().noneMatch(other -> other.box().overlaps(box))
                    && (!avoidDots || dots.stream().noneMatch(box::overlaps))) {
                placed.add(there);
                return there;
            }
        }
        return null;
    }

    /** How many labels are placed; {@link #reset} goes back to this point. */
    int mark() {
        return placed.size();
    }

    /** Takes back the labels placed since {@code mark}; dots stay. */
    void reset(int mark) {
        placed.subList(mark, placed.size()).clear();
    }

    List<Label> labels() {
        return List.copyOf(placed);
    }

    List<Rect> dots() {
        return List.copyOf(dots);
    }

    void drawAll(int[] pixels) {
        for (Label label : placed) {
            label.draw(pixels, width, height);
        }
    }
}
