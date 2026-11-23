package io.github.imnotstable.msc.core.world;

import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reviews that failed and were not passed since, for this session (ADR 0084): msc_status and dev_cycle name them, so
 * an agent cannot end a run on a review it read and left failing. Only a passing review of the same region clears
 * a failed one (ADR 0095), and an edit after a pass reopens it (ADR 0096).
 */
public final class OpenReviews {
    private static final Pattern FIX = Pattern.compile("(?:fix first: |; )\\d+\\) ([^:;]+):");
    private static final int SHOWN = 6;

    private final Map<Region, List<String>> open = new LinkedHashMap<>();
    private final java.util.Set<Region> passed = new java.util.LinkedHashSet<>();

    /** Records a review's text for its region: failed when it has fixes, else passed. */
    public synchronized void record(Region region, String review) {
        // Only a review of the same region settles it: run 8 widened failing regions a block at a time until a
        // wider one passed (ADR 0095).
        open.remove(region);
        passed.remove(region);
        List<String> fixes = fixes(review);
        if (!fixes.isEmpty()) {
            open.put(region, fixes);
        } else {
            passed.add(region);
        }
    }

    /**
     * A build edit in {@code box}: a passed review it touches no longer holds and is listed until reviewed again. Run
     * 9 landscaped its whole town after reviewing each house, and two houses no longer passed (ADR 0096).
     */
    public synchronized void edited(Region box) {
        for (java.util.Iterator<Region> it = passed.iterator(); it.hasNext(); ) {
            Region r = it.next();
            if (r.world().equals(box.world()) && r.min().x() <= box.max().x() && box.min().x() <= r.max().x()
                    && r.min().y() <= box.max().y() && box.min().y() <= r.max().y()
                    && r.min().z() <= box.max().z() && box.min().z() <= r.max().z()) {
                it.remove();
                open.put(r, List.of("edited since it passed"));
            }
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
}
