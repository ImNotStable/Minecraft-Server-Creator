package io.github.imnotstable.msc.core.tools;

import io.github.imnotstable.msc.core.tool.Tool;
import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.core.world.TourStops;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.renderer.RenderOptions;
import io.github.imnotstable.msc.renderer.View;
import io.github.imnotstable.msc.renderer.WorldRenderer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * world_tour (ADR 0094): the views a player gets walking a build, on one contact sheet, so an agent judges what a
 * visitor sees (a door cut in half, a dark room, a tree through a wall) and not only the bird's-eye render.
 */
final class Tour {
    static final int MAX_STOPS = 9;
    static final int DEFAULT_STOPS = 6;
    static final int MARGIN = 6;
    static final int VIEW = 320;
    private static final int COLUMNS = 3;

    private Tour() {
    }

    static Tool.Output walk(WorldRenderer renderer, BlockGrid grid, String review, int stops) {
        List<TourStops.Stop> plan = TourStops.plan(grid, fixes(review), stops);
        if (plan.isEmpty()) {
            throw new ToolException("nothing to look at in this region");
        }
        RenderOptions options = RenderOptions.defaults().withMaxSize(VIEW);
        List<BufferedImage> views = new ArrayList<>();
        StringBuilder text = new StringBuilder("tour of " + plan.size() + " views (numbered left to right, top down):");
        for (int i = 0; i < plan.size(); i++) {
            TourStops.Stop s = plan.get(i);
            View view = View.Camera.lookingAt(s.ex(), s.ey(), s.ez(), s.tx(), s.ty(), s.tz(), 75);
            views.add(read(renderer.render(grid, view, options).png()));
            text.append('\n').append(i + 1).append(") ").append(s.label()).append(" [").append(s.camera()).append(']');
        }
        byte[] png = sheet(views);
        int rows = (views.size() + COLUMNS - 1) / COLUMNS;
        return new Tool.Output(text.append('\n').append(review).toString(), png,
                Math.max(1, Math.min(views.size(), COLUMNS) * VIEW * rows * VIEW / 750));
    }

    /** The review's fix lines, each with its "first at" when it has one. */
    static List<String> fixes(String review) {
        int at = review.indexOf("\nfix first: ");
        List<String> out = new ArrayList<>();
        if (at < 0) {
            return out;
        }
        String body = review.substring(at + "\nfix first: ".length());
        for (String part : body.split("(?:^|; )\\d+\\) ")) {
            if (!part.isBlank()) {
                out.add(part);
            }
        }
        return out;
    }

    private static BufferedImage read(byte[] png) {
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] sheet(List<BufferedImage> views) {
        int cols = Math.min(COLUMNS, views.size());
        int rows = (views.size() + COLUMNS - 1) / COLUMNS;
        int w = 0;
        int h = 0;
        for (BufferedImage v : views) {
            w = Math.max(w, v.getWidth());
            h = Math.max(h, v.getHeight());
        }
        BufferedImage out = new BufferedImage(cols * w, rows * h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            // No text: fonts are not on every headless machine; the result's text numbers the views in order.
            g.setColor(Color.WHITE);
            for (int i = 0; i < views.size(); i++) {
                int x = i % COLUMNS * w;
                int y = i / COLUMNS * h;
                g.drawImage(views.get(i), x, y, null);
                g.drawRect(x, y, w - 1, h - 1);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ImageIO.write(out, "png", bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }
}
