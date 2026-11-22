package io.github.imnotstable.msc.stdlib.util;

import io.github.imnotstable.msc.stdlib.time.Durations;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Numbers, money, bars and times as players should read them. Every method is plain text; colour it with MiniMessage. */
public final class Format {
    private static final String[] COMPACT = {"", "K", "M", "B", "T", "Q"};
    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private Format() {}

    /** {@code 1250} → {@code 1,250}; {@code 1250.5} → {@code 1,250.5} (at most 2 decimals). */
    public static String number(double n) {
        return new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(n);
    }

    /** {@code 1250} → {@code 1.25K}, {@code 3400000} → {@code 3.4M}. */
    public static String compact(double n) {
        double abs = Math.abs(n);
        int i = 0;
        while (abs >= 1000 && i < COMPACT.length - 1) {
            abs /= 1000;
            i++;
        }
        String digits = new DecimalFormat(abs >= 100 ? "0" : abs >= 10 ? "0.#" : "0.##",
                DecimalFormatSymbols.getInstance(Locale.ROOT)).format(abs);
        return (n < 0 ? "-" : "") + digits + COMPACT[i];
    }

    /** {@code money(12.5, "$")} → {@code $12.50}; {@code money(1500, "⛃")} → {@code ⛃1,500.00}. */
    public static String money(double amount, String symbol) {
        return (amount < 0 ? "-" : "") + symbol
                + new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(Math.abs(amount));
    }

    /** {@code 0.256} → {@code 25.6%}. */
    public static String percent(double fraction) {
        return new DecimalFormat("0.#", DecimalFormatSymbols.getInstance(Locale.ROOT)).format(fraction * 100) + "%";
    }

    /** {@code 1} → {@code 1st}, {@code 12} → {@code 12th}, {@code 23} → {@code 23rd}. */
    public static String ordinal(int n) {
        int mod100 = Math.abs(n) % 100;
        String suffix = mod100 >= 11 && mod100 <= 13 ? "th" : switch (Math.abs(n) % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
        return n + suffix;
    }

    /** {@code 14} → {@code XIV} (1 to 3999), for levels and tiers. */
    public static String roman(int n) {
        if (n < 1 || n > 3999) {
            return String.valueOf(n);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (n >= ROMAN_VALUES[i]) {
                out.append(ROMAN[i]);
                n -= ROMAN_VALUES[i];
            }
        }
        return out.toString();
    }

    /** {@code bar(3, 5, 5)} → {@code ▮▮▮▯▯}. */
    public static String bar(double current, double max, int width) {
        return bar(current, max, width, "▮", "▯");
    }

    /** A bar of {@code width} cells, {@code full} for the filled part: {@code bar(7, 10, 10, "|", "·")}. */
    public static String bar(double current, double max, int width, String full, String empty) {
        double f = max <= 0 ? 0 : Math.max(0, Math.min(1, current / max));
        int filled = (int) Math.round(f * width);
        return full.repeat(filled) + empty.repeat(width - filled);
    }

    /**
     * A MiniMessage bar, filled part in one colour and the rest in another:
     * {@code coloredBar(hp, 20, 10, "green", "dark_gray")} → {@code <green>▮▮▮▮▮▮▮</green><dark_gray>▮▮▮</dark_gray>}.
     */
    public static String coloredBar(double current, double max, int width, String fullColor, String emptyColor) {
        double f = max <= 0 ? 0 : Math.max(0, Math.min(1, current / max));
        int filled = (int) Math.round(f * width);
        return "<" + fullColor + ">" + "▮".repeat(filled) + "</" + fullColor + "><" + emptyColor + ">"
                + "▮".repeat(width - filled) + "</" + emptyColor + ">";
    }

    /** {@code 3m 20s}, {@code 1h 5m}, {@code 0s} (two largest units). */
    public static String duration(Duration d) {
        return Durations.format(d);
    }

    /** Clock style for timers: {@code 3:20}, {@code 1:05:09}. */
    public static String clock(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    /** {@code 5 minutes ago}, {@code just now}, {@code in 2 hours}. */
    public static String ago(Instant then, Instant now) {
        long s = Duration.between(then, now).toSeconds();
        boolean future = s < 0;
        s = Math.abs(s);
        if (s < 10) {
            return "just now";
        }
        String[] units = {"second", "minute", "hour", "day", "week", "year"};
        long[] secs = {1, 60, 3600, 86400, 604800, 31536000};
        int i = secs.length - 1;
        while (i > 0 && s < secs[i]) {
            i--;
        }
        long n = s / secs[i];
        String text = n + " " + units[i] + (n == 1 ? "" : "s");
        return future ? "in " + text : text + " ago";
    }

    /** {@code plural(1, "coin")} → {@code 1 coin}, {@code plural(3, "coin")} → {@code 3 coins}. */
    public static String plural(long n, String word) {
        return n + " " + (n == 1 ? word : word.endsWith("s") || word.endsWith("x") || word.endsWith("ch")
                ? word + "es" : word.endsWith("y") && word.length() > 1 && "aeiou".indexOf(word.charAt(word.length() - 2)) < 0
                ? word.substring(0, word.length() - 1) + "ies" : word + "s");
    }

    /** {@code DIAMOND_SWORD} or {@code diamond_sword} → {@code Diamond Sword}. */
    public static String title(String id) {
        String s = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        StringBuilder out = new StringBuilder();
        for (String w : s.toLowerCase(Locale.ROOT).split("[_\\s]+")) {
            if (!w.isEmpty()) {
                out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }
}
