package io.github.imnotstable.msc.bench.check;

import com.google.gson.JsonObject;
import io.github.imnotstable.msc.bench.JsonNumbers;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.imageio.ImageIO;

/**
 * Rules a pixel-art PNG must meet. Every field is optional; 0 means "not checked", and a size or count given in
 * {@code task.json} must be above 0.
 *
 * @param width             exact width in pixels
 * @param height            exact height in pixels
 * @param maxColors         most distinct opaque colours allowed (palette discipline)
 * @param minOpaqueFraction share of pixels with alpha above 0 that must be present
 * @param maxOpaqueFraction share of opaque pixels allowed, so a filled square does not pass as an item
 * @param hue               optional dominant-hue rule
 */
public record PngRules(
        int width, int height, int maxColors, double minOpaqueFraction, double maxOpaqueFraction, HueRule hue) {

    /**
     * At least {@code minFraction} of opaque pixels must have a hue in [from, to] degrees (wrapping
     * past 360 when from > to) and saturation of at least {@code minSaturation}.
     */
    public record HueRule(double from, double to, double minFraction, double minSaturation) {

        boolean matches(float hueDegrees, float saturation) {
            if (saturation < minSaturation) {
                return false;
            }
            return from <= to ? hueDegrees >= from && hueDegrees <= to : hueDegrees >= from || hueDegrees <= to;
        }
    }

    /** Reads the rules of a check or zip entry; {@code at} names it in errors, such as {@code task.json checks[0]}. */
    static PngRules parse(JsonObject json, String at) {
        HueRule hue = null;
        if (json.has("hue")) {
            JsonObject h = json.getAsJsonObject("hue");
            String key = at + ".hue";
            for (String required : List.of("from", "to")) {
                if (!h.has(required)) {
                    throw new IllegalArgumentException(key + ": missing \"" + required + "\"");
                }
            }
            hue = new HueRule(
                    JsonNumbers.number(h, "from", key + ".from", JsonNumbers.HUE, 0),
                    JsonNumbers.number(h, "to", key + ".to", JsonNumbers.HUE, 0),
                    JsonNumbers.number(h, "minFraction", key + ".minFraction", JsonNumbers.FRACTION, 0.5),
                    JsonNumbers.number(h, "minSaturation", key + ".minSaturation", JsonNumbers.FRACTION, 0.25));
        }
        return new PngRules(
                JsonNumbers.wholeNumber(json, "width", at + ".width", JsonNumbers.POSITIVE, 0),
                JsonNumbers.wholeNumber(json, "height", at + ".height", JsonNumbers.POSITIVE, 0),
                JsonNumbers.wholeNumber(json, "maxColors", at + ".maxColors", JsonNumbers.POSITIVE, 0),
                JsonNumbers.number(json, "minOpaqueFraction", at + ".minOpaqueFraction", JsonNumbers.FRACTION, 0),
                JsonNumbers.number(json, "maxOpaqueFraction", at + ".maxOpaqueFraction", JsonNumbers.FRACTION, 0),
                hue);
    }

    /** Returns the list of broken rules; empty means the image passes. */
    public List<String> problems(byte[] png) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return List.of("not a readable image: " + e.getMessage());
        }
        if (image == null) {
            return List.of("not a PNG image");
        }
        List<String> problems = new ArrayList<>();
        if (width > 0 && image.getWidth() != width || height > 0 && image.getHeight() != height) {
            problems.add("size " + image.getWidth() + "x" + image.getHeight() + ", expected " + width + "x" + height);
        }
        Set<Integer> colours = new HashSet<>();
        int opaque = 0;
        int hueHits = 0;
        float[] hsb = new float[3];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                if (argb >>> 24 == 0) {
                    continue;
                }
                opaque++;
                colours.add(argb);
                if (hue != null) {
                    Color.RGBtoHSB((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, hsb);
                    if (hue.matches(hsb[0] * 360f, hsb[1])) {
                        hueHits++;
                    }
                }
            }
        }
        int total = image.getWidth() * image.getHeight();
        if (maxColors > 0 && colours.size() > maxColors) {
            problems.add(colours.size() + " colours, at most " + maxColors + " allowed");
        }
        if (minOpaqueFraction > 0 && (double) opaque / total < minOpaqueFraction) {
            problems.add(String.format("only %d of %d pixels are opaque", opaque, total));
        }
        if (maxOpaqueFraction > 0 && (double) opaque / total > maxOpaqueFraction) {
            problems.add(String.format("%d of %d pixels are opaque; leave a transparent background", opaque, total));
        }
        if (hue != null && (opaque == 0 || (double) hueHits / opaque < hue.minFraction())) {
            problems.add(String.format(
                    "only %d of %d opaque pixels have hue %.0f-%.0f", hueHits, opaque, hue.from(), hue.to()));
        }
        return problems;
    }
}
