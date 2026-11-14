package io.github.imnotstable.msc.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;

/**
 * The one way msc-bench reads a number from a file a user writes: {@code bench.json} and each {@code task.json}
 * (#416). Gson's {@code getAsInt} cuts 0.5 to 0 and wraps 4294992872 to 25576, so a value that can never work passed
 * the range rules as a valid one, and a refusal named a number the file never held. Here a value is taken exactly as
 * written: anything but a JSON number (a string, {@code null}, a list), a decimal where a whole number is needed and a
 * number outside the key's {@link Range} are refused with an {@link IllegalArgumentException} that names the key and
 * shows the value as the file spells it.
 *
 * <p>Readers reach these reads only through {@link UserJson}, which holds the file's JSON and offers no other number
 * read (#423).
 */
public final class JsonNumbers {

    /** Seconds a step may take. */
    public static final Range SECONDS = whole(1, Integer.MAX_VALUE, "a whole number of seconds above 0");
    /** A TCP port. */
    public static final Range PORT = whole(1, 65535, "a TCP port, a whole number from 1 to 65535");
    /** A size or count that must be there when given, such as a width in pixels. */
    public static final Range POSITIVE = whole(1, Integer.MAX_VALUE, "a whole number above 0");
    /** A process exit code, which may be negative on Windows. */
    public static final Range EXIT_CODE = whole(Integer.MIN_VALUE, Integer.MAX_VALUE, "a whole number (an exit code)");
    /** A block x or z: a Minecraft world ends 30,000,000 blocks from its centre. */
    public static final Range BLOCK_XZ = whole(-30_000_000, 29_999_999,
            "a block x or z inside a Minecraft world, a whole number from -30000000 to 29999999");
    /** A block y: a Minecraft dimension spans at most y -2032 to 2031. */
    public static final Range BLOCK_Y = whole(-2032, 2031,
            "a block y inside a Minecraft world, a whole number from -2032 to 2031");
    /** A count in a run record, such as agent turns or tool calls. */
    public static final Range COUNT = whole(0, 1_000_000, "a count, a whole number from 0 to 1000000");
    /** A per-tool call count in a run record, which the report divides by. */
    public static final Range CALLS = whole(1, 1_000_000, "a count of calls, a whole number from 1 to 1000000");
    /** Tokens, characters or milliseconds in a run record: large, but small enough that totals stay exact. */
    public static final Range AMOUNT = new Range(BigDecimal.ZERO, BigDecimal.valueOf(1_000_000_000_000_000L), true,
            "a whole number from 0 to 1000000000000000");
    /** A cost in US dollars. */
    public static final Range COST = decimal(0, 1_000_000, "an amount in US dollars from 0 to 1000000");
    /** A share, such as a fraction of pixels. */
    public static final Range FRACTION = decimal(0, 1, "a number from 0 to 1");
    /** A hue in degrees. */
    public static final Range HUE = decimal(0, 360, "a hue in degrees, a number from 0 to 360");

    /**
     * The values a key accepts.
     *
     * @param min   lowest value
     * @param max   highest value
     * @param whole whether only whole numbers are allowed; then min and max lie within {@code long}, and within
     *              {@code int} for an {@code int} read
     * @param text  what the key takes, as the refusal says it: "is not {@code text}"
     */
    public record Range(BigDecimal min, BigDecimal max, boolean whole, String text) {

        public Range {
            if (min.compareTo(max) > 0) {
                throw new IllegalArgumentException("empty range " + min + " to " + max);
            }
        }
    }

    private JsonNumbers() {
    }

    /** The whole number {@code value} of {@code key}, refused unless it is a whole number within {@code range}. */
    static int wholeNumber(JsonElement value, String key, Range range) {
        if (!range.whole()) {
            throw new IllegalArgumentException(key + ": " + range.text() + " is not a whole number range");
        }
        if (range.min().compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0
                || range.max().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(key + ": " + range.text() + " does not fit an int");
        }
        return exact(value, key, range).intValueExact();
    }

    /** The whole number {@code value} of {@code key} as a {@code long}, refused unless it is within {@code range}. */
    static long wholeLong(JsonElement value, String key, Range range) {
        if (!range.whole()) {
            throw new IllegalArgumentException(key + ": " + range.text() + " is not a whole number range");
        }
        return exact(value, key, range).longValueExact();
    }

    /** The number {@code value} of {@code key}, refused unless it is a number within {@code range}. */
    static double number(JsonElement value, String key, Range range) {
        return exact(value, key, range).doubleValue();
    }

    /** The value exactly as written, once it is a number of the range's kind inside the range. */
    private static BigDecimal exact(JsonElement value, String key, Range range) {
        BigDecimal number = null;
        if (value instanceof JsonPrimitive primitive && primitive.isNumber()) {
            try {
                number = new BigDecimal(primitive.getAsString());
            } catch (NumberFormatException e) {
                // an exponent too large for BigDecimal: refused below
            }
        }
        if (number == null
                || range.whole() && number.signum() != 0 && number.stripTrailingZeros().scale() > 0
                || number.compareTo(range.min()) < 0 || number.compareTo(range.max()) > 0) {
            throw new IllegalArgumentException(key + ": " + written(value) + " is not " + range.text());
        }
        return number;
    }

    /** The value as the file spells it: a number's own text, anything else as JSON, cut when long. */
    static String written(JsonElement value) {
        String text = value instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsString()
                : String.valueOf(value);
        return Text.headWithEllipsis(text, 60);
    }

    private static Range whole(int min, int max, String text) {
        return new Range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), true, text);
    }

    private static Range decimal(int min, int max, String text) {
        return new Range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), false, text);
    }
}
