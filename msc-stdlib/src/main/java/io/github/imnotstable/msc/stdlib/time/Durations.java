package io.github.imnotstable.msc.stdlib.time;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Durations as players type and read them: {@code 1d2h30m}, {@code 90s}, {@code 1w}, {@code 500ms}. */
public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)(ms|w|d|h|m|s|t)");
    private static final Pattern WHOLE = Pattern.compile("(?:\\d+(?:ms|w|d|h|m|s|t))+");

    private Durations() {}

    /**
     * Parses {@code 1h30m}, {@code 2d}, {@code 45s}, {@code 20t} (game ticks, 50 ms) and {@code 500ms}; spaces and
     * case are ignored. A bare number is seconds.
     *
     * @throws IllegalArgumentException for anything else
     */
    public static Duration parse(String text) {
        String s = text.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        if (s.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(s));
        }
        if (!WHOLE.matcher(s).matches()) {
            throw new IllegalArgumentException("Not a duration: '" + text + "' (use like 1h30m, 90s, 2d)");
        }
        Duration total = Duration.ZERO;
        Matcher m = PART.matcher(s);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            total = total.plus(switch (m.group(2)) {
                case "ms" -> Duration.ofMillis(n);
                case "t" -> Duration.ofMillis(n * 50);
                case "s" -> Duration.ofSeconds(n);
                case "m" -> Duration.ofMinutes(n);
                case "h" -> Duration.ofHours(n);
                case "d" -> Duration.ofDays(n);
                default -> Duration.ofDays(n * 7);
            });
        }
        return total;
    }

    /** Short form, largest units first, at most two: {@code 1h 30m}, {@code 45s}, {@code 0s}. Rounds up to seconds. */
    public static String format(Duration d) {
        long secs = d.isNegative() ? 0 : d.plusMillis(999).toSeconds();
        long[] amounts = {secs / 86400, secs / 3600 % 24, secs / 60 % 60, secs % 60};
        String[] units = {"d", "h", "m", "s"};
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < amounts.length && shown < 2; i++) {
            if (amounts[i] > 0) {
                out.append(out.isEmpty() ? "" : " ").append(amounts[i]).append(units[i]);
                shown++;
            }
        }
        return out.isEmpty() ? "0s" : out.toString();
    }

    /** Game ticks (20 a second) in {@code d}, rounded down. */
    public static long ticks(Duration d) {
        return d.toMillis() / 50;
    }
}
