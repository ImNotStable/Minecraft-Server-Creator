package io.github.imnotstable.msc.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;

/**
 * The one way msc-bench reads a number from a file a user writes: {@code bench.json} and each {@code task.json}
 * (#416). Gson's {@code getAsInt} cuts 0.5 to 0 and wraps 4294992872 to 25576, so a value that can never work passed
 * the range rules as a valid one, and a refusal named a number the file never held. Here a value is taken exactly as
 * written: anything but a JSON number (a string, {@code null}, a list), a decimal where a whole number is needed and a
 * number outside the key's {@link Range} are refused with an {@link IllegalArgumentException} that names the key and
 * shows the value as the file spells it.
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
    /** A share, such as a fraction of pixels. */
    public static final Range FRACTION = decimal(0, 1, "a number from 0 to 1");
    /** A hue in degrees. */
    public static final Range HUE = decimal(0, 360, "a hue in degrees, a number from 0 to 360");

    /**
     * The values a key accepts.
     *
     * @param min   lowest value
     * @param max   highest value
     * @param whole whether only whole numbers are allowed; then min and max lie within {@code int}
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

    /** The whole number {@code holder.field}, or {@code absent} when the key is missing; {@code key} names it. */
    public static int wholeNumber(JsonObject holder, String field, String key, Range range, int absent) {
        return holder.has(field) ? wholeNumber(holder.get(field), key, range) : absent;
    }

    /** The whole number {@code value} of {@code key}, refused unless it is a whole number within {@code range}. */
    public static int wholeNumber(JsonElement value, String key, Range range) {
        if (!range.whole()) {
            throw new IllegalArgumentException(key + ": " + range.text() + " is not a whole number range");
        }
        return exact(value, key, range).intValueExact();
    }

    /** The number {@code holder.field}, or {@code absent} when the key is missing; {@code key} names it. */
    public static double number(JsonObject holder, String field, String key, Range range, double absent) {
        return holder.has(field) ? number(holder.get(field), key, range) : absent;
    }

    /** The number {@code value} of {@code key}, refused unless it is a number within {@code range}. */
    public static double number(JsonElement value, String key, Range range) {
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
    private static String written(JsonElement value) {
        String text = value instanceof JsonPrimitive primitive && primitive.isNumber()
                ? primitive.getAsString()
                : String.valueOf(value);
        return text.length() <= 60 ? text : text.substring(0, 57) + "...";
    }

    private static Range whole(int min, int max, String text) {
        return new Range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), true, text);
    }

    private static Range decimal(int min, int max, String text) {
        return new Range(BigDecimal.valueOf(min), BigDecimal.valueOf(max), false, text);
    }
}
