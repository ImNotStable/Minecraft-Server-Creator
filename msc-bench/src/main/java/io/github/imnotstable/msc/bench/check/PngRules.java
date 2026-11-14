package io.github.imnotstable.msc.bench.check;

import io.github.imnotstable.msc.bench.JsonNumbers;
import io.github.imnotstable.msc.bench.UserJson;
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

    /** The keys of the rules, which a {@code png} check and a zip's {@code pngEntries} entry hold (#502). */
    static final List<String> KEYS = List.of("width", "height", "maxColors", "minOpaqueFraction", "maxOpaqueFraction",
            "hue");

    /** The keys of {@code hue}. */
    static final UserJson.Keys HUE = UserJson.Keys.of("from", "to", "minFraction", "minSaturation");

    /** Reads the rules of a check or zip entry. */
    static PngRules parse(UserJson json) {
        HueRule hue = null;
        UserJson h = json.exactObject("hue", HUE);
        if (h != null) {
            for (String required : List.of("from", "to")) {
                if (!h.has(required)) {
                    throw new IllegalArgumentException(json.key("hue") + ": missing \"" + required + "\"");
                }
            }
            hue = new HueRule(
                    h.number("from", JsonNumbers.HUE, 0),
                    h.number("to", JsonNumbers.HUE, 0),
                    h.number("minFraction", JsonNumbers.FRACTION, 0.5),
                    h.number("minSaturation", JsonNumbers.FRACTION, 0.25));
        }
        return new PngRules(
                json.wholeNumber("width", JsonNumbers.POSITIVE, 0),
                json.wholeNumber("height", JsonNumbers.POSITIVE, 0),
                json.wholeNumber("maxColors", JsonNumbers.POSITIVE, 0),
                json.number("minOpaqueFraction", JsonNumbers.FRACTION, 0),
                json.number("maxOpaqueFraction", JsonNumbers.FRACTION, 0),
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
