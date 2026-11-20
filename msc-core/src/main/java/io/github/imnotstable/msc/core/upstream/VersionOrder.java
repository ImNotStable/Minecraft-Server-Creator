package io.github.imnotstable.msc.core.upstream;

import java.util.Comparator;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Orders the version strings the upstreams publish ({@code 26.3}, {@code 26.3.0.102}, {@code 26.3-66.1.5},
 * {@code 0.166.0+26.3}, {@code 2026.10.07-26.2}) and tells a stable one from a preview.
 */
public final class VersionOrder {
    /** Compares part by part: the leading number of each part, then the rest as text; a missing part sorts first. */
    static final Comparator<String> NEWEST_LAST = VersionOrder::compare;

    private static final Pattern PARTS = Pattern.compile("[.+\\-_]");
    /** Mojang's old weekly snapshot ids, such as {@code 24w14a}. */
    private static final Pattern WEEKLY = Pattern.compile("\\d+w\\d+[a-z]");

    private VersionOrder() {
    }

    /** False for alphas, betas, pre-releases, release candidates and snapshots. */
    static boolean isStable(String version) {
        String v = version.toLowerCase(Locale.ROOT);
        return !(v.contains("alpha") || v.contains("beta") || v.contains("pre") || v.contains("rc")
                || v.contains("snapshot") || WEEKLY.matcher(v).find());
    }

    public static int compare(String a, String b) {
        String[] x = PARTS.split(a);
        String[] y = PARTS.split(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            if (i >= x.length) {
                return -1;
            }
            if (i >= y.length) {
                return 1;
            }
            int c = comparePart(x[i], y[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    private static int comparePart(String a, String b) {
        int da = digits(a);
        int db = digits(b);
        if (da > 0 && db > 0) {
            int c = Long.compare(Long.parseLong(a.substring(0, Math.min(da, 18))),
                    Long.parseLong(b.substring(0, Math.min(db, 18))));
            if (c != 0) {
                return c;
            }
        } else if (da > 0 || db > 0) {
            return da > 0 ? 1 : -1;
        }
        return a.substring(da).compareTo(b.substring(db));
    }

    private static int digits(String s) {
        int n = 0;
        while (n < s.length() && Character.isDigit(s.charAt(n))) {
            n++;
        }
        return n;
    }
}
