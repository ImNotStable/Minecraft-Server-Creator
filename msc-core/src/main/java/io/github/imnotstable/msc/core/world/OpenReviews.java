package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviews that failed and were not passed since, for this session (ADR 0082): msc_status and dev_cycle name them, so
 * an agent cannot end a run on a review it read and left failing. A passing review of a region clears every failed
 * one inside it.
 */
public final class OpenReviews {
    private static final Pattern FIX = Pattern.compile("(?:fix first: |; )\\d+\\) ([^:;]+):");
    private static final int SHOWN = 6;

    private final Map<Region, List<String>> open = new LinkedHashMap<>();

    /** Records a review's text for its region: failed when it has fixes, else passed. */
    public synchronized void record(Region region, String review) {
        open.keySet().removeIf(r -> inside(r, region));
        List<String> fixes = fixes(review);
        if (!fixes.isEmpty()) {
            open.put(region, fixes);
        }
    }

    /** The short names of a review's fixes ("bare base", "empty rooms"), in its order; empty when it passed. */
    static List<String> fixes(String review) {
        int at = review.indexOf("\nfix first: ");
        List<String> out = new ArrayList<>();
        if (at < 0) {
            return out;
        }
        Matcher m = FIX.matcher(review.substring(at + 1));
        while (m.find()) {
            out.add(m.group(1).trim());
        }
        return out;
    }

    /** One line per failing review, or empty when none is open. */
    public synchronized String line() {
        if (open.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("reviews still failing (" + open.size() + "; the work is not done until "
                + "each passes: fix, then world_summary review=true on the same region):");
        int shown = 0;
        for (Map.Entry<Region, List<String>> e : open.entrySet()) {
            if (shown++ == SHOWN) {
                out.append("\n- ").append(open.size() - SHOWN).append(" more");
                break;
            }
            Region r = e.getKey();
            out.append("\n- ").append(r.world()).append(' ').append(r.min().x()).append(',').append(r.min().y())
                    .append(',').append(r.min().z()).append(' ').append(r.max().x()).append(',').append(r.max().y())
                    .append(',').append(r.max().z()).append(": ").append(String.join(", ", e.getValue()));
        }
        return out.toString();
    }

    private static boolean inside(Region a, Region b) {
        return a.world().equals(b.world()) && b.contains(a.min().x(), a.min().y(), a.min().z())
                && b.contains(a.max().x(), a.max().y(), a.max().z());
    }
}
