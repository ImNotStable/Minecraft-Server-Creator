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
    /** Built areas no review has looked at yet, with the blocks placed in each (ADR 0109). */
    private final Map<Region, Integer> unreviewed = new LinkedHashMap<>();

    /** Blocks placed in one area before it counts as a build that needs a review (a sign or a lamp does not). */
    static final int UNREVIEWED_BLOCKS = 150;

    /** Failing reviews of one region in a row, with the same findings and no better numbers, that make a stall. */
    public static final int STALL = 3;
    private static final Pattern PERCENT = Pattern.compile("(?:fix first: |; )\\d+\\) [^:;]+: (\\d+)%");
    private final Map<Region, int[]> streaks = new java.util.HashMap<>();
    private final Map<Region, List<String>> lastFixes = new java.util.HashMap<>();

    /**
     * Records a review (as {@link #record}) and returns a stall warning, or empty: the region failed {@link #STALL}
     * times in a row with the same findings and no finding's percentage lower. Run 34 got "bare base" 45 times and
     * rebuilt the same walls each time; nothing said it was going in circles (ADR 0117).
     */
    public synchronized String recordAndCheck(Region region, String review) {
        record(region, review);
        List<String> fixes = fixes(review);
        if (fixes.isEmpty()) {
            streaks.remove(region);
            lastFixes.remove(region);
            return "";
        }
        int score = 0;
        Matcher m = PERCENT.matcher(review.substring(Math.max(0, review.indexOf("\nfix first: ") + 1)));
        while (m.find()) {
            score += Integer.parseInt(m.group(1));
        }
        int[] s = streaks.get(region);
        boolean same = s != null && fixes.equals(lastFixes.get(region)) && score >= s[1];
        s = same ? new int[] {s[0] + 1, score} : new int[] {1, score};
        streaks.put(region, s);
        lastFixes.put(region, fixes);
        if (s[0] < STALL) {
            return "";
        }
        return "STALLED: this region has failed " + s[0] + " reviews in a row on the same findings ("
                + String.join(", ", fixes) + ") with no number better. Repeating the same edits will not change it: "
                + "do exactly what the fix names, at the place it gives, with a different step than before; if that "
                + "cannot work, stop, say so in your report, and move on.";
    }

    /** Records a review's text for its region: failed when it has fixes, else passed. */
    public synchronized void record(Region region, String review) {
        // Only a review of the same region settles it: run 8 widened failing regions a block at a time until a
        // wider one passed (ADR 0095).
        open.remove(region);
        passed.remove(region);
        unreviewed.keySet().removeIf(b -> b.world().equals(region.world())
                && region.contains((b.min().x() + b.max().x()) / 2, (b.min().y() + b.max().y()) / 2,
                (b.min().z() + b.max().z()) / 2));
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
        edited(box, 0);
    }

    /**
     * The same, with the blocks the edit changed: areas built nearby merge, and one with {@link #UNREVIEWED_BLOCKS}+
     * blocks that no later review covers is listed as never reviewed. Sonnet 5.5 built a dock and reported done
     * without ever reviewing it (ADR 0109).
     */
    public synchronized void edited(Region box, int changed) {
        // An edit inside a region already reviewed is tracked there (failing, or edited since it passed): listing it
        // again as never reviewed told Sonnet 5.5 its own fixes were unreviewed, 42 times in one town (ADR 0109).
        if (reviewed(box)) {
            changed = 0;
        }
        Region area = box;
        int blocks = changed;
        for (boolean merged = true; merged; ) {
            merged = false;
            for (Map.Entry<Region, Integer> e : unreviewed.entrySet()) {
                Region u = e.getKey();
                // Touching edits are one build; a gap between them makes separate builds, each reviewed on its own.
                if (near(u, area, 1)) {
                    area = Region.of(area.world(), Math.min(u.min().x(), area.min().x()), Math.min(u.min().y(),
                            area.min().y()), Math.min(u.min().z(), area.min().z()), Math.max(u.max().x(), area.max().x()),
                            Math.max(u.max().y(), area.max().y()), Math.max(u.max().z(), area.max().z()));
                    blocks += e.getValue();
                    unreviewed.remove(u);
                    merged = true;
                    break;
                }
            }
        }
        if (blocks > 0) {
            unreviewed.put(area, blocks);
        }
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

    private boolean reviewed(Region box) {
        int x = (box.min().x() + box.max().x()) / 2;
        int y = (box.min().y() + box.max().y()) / 2;
        int z = (box.min().z() + box.max().z()) / 2;
        return java.util.stream.Stream.concat(open.keySet().stream(), passed.stream())
                .anyMatch(r -> r.world().equals(box.world()) && r.contains(x, y, z));
    }

    private static boolean near(Region a, Region b, int gap) {
        return a.world().equals(b.world()) && a.min().x() <= b.max().x() + gap && b.min().x() <= a.max().x() + gap
                && a.min().y() <= b.max().y() + gap && b.min().y() <= a.max().y() + gap
                && a.min().z() <= b.max().z() + gap && b.min().z() <= a.max().z() + gap;
    }

    private List<Region> neverReviewed() {
        return unreviewed.entrySet().stream().filter(e -> e.getValue() >= UNREVIEWED_BLOCKS).map(Map.Entry::getKey)
                .toList();
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

    /**
     * One short line for results of other tools ("2 reviews still failing (msc_status lists them)"), or empty: run 13's
     * agent read the full list at the end of dev_cycle four times and reported done without it (ADR 0105).
     */
    public synchronized String count() {
        int never = neverReviewed().size();
        if (open.isEmpty() && never == 0) {
            return "";
        }
        String failing = open.size() + (open.size() == 1 ? " review" : " reviews") + " still failing";
        String unseen = never + (never == 1 ? " build" : " builds") + " never reviewed";
        return (never == 0 ? failing : open.isEmpty() ? unseen : failing + ", " + unseen)
                + " (msc_status lists them): the work is not done";
    }

    /** Failing reviews at which building elsewhere stops until one is fixed (ADR 0108). */
    public static final int GATE = 3;

    /**
     * Why a build in {@code box} may not go ahead, or null: with {@link #GATE} or more reviews failing, only builds
     * that touch a failing region (fixing it) are allowed. Codex runs 13-18 read every failing review and moved on to
     * new builds; Gemini runs fixed theirs (ADR 0108).
     */
    public synchronized String gate(Region box) {
        if (open.size() < GATE) {
            return null;
        }
        for (Region r : open.keySet()) {
            if (r.world().equals(box.world()) && r.min().x() <= box.max().x() && box.min().x() <= r.max().x()
                    && r.min().y() <= box.max().y() && box.min().y() <= r.max().y()
                    && r.min().z() <= box.max().z() && box.min().z() <= r.max().z()) {
                return null;
            }
        }
        return "nothing placed: " + open.size() + " reviews still fail, so no new build starts until they are fixed. "
                + "build_batch inside a failing region works (that is the fix), then world_summary review=true on "
                + "the same region:\n" + line().substring(line().indexOf('\n') + 1);
    }

    /** One line per failing review, or empty when none is open. */
    public synchronized String line() {
        List<Region> never = neverReviewed();
        if (open.isEmpty() && never.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(open.isEmpty() ? "builds never reviewed (" + never.size() + "; the work "
                + "is not done until each is reviewed and passes):" : "reviews still failing (" + open.size() + "; the "
                + "work is not done until each passes: fix, then world_summary review=true on the same region):");
        for (Region r : never) {
            out.append("\n- ").append(show(r)).append(": never reviewed (").append(unreviewed.get(r))
                    .append(" blocks placed): world_summary review=true on it");
        }
        int shown = 0;
        for (Map.Entry<Region, List<String>> e : open.entrySet()) {
            if (shown++ == SHOWN) {
                out.append("\n- ").append(open.size() - SHOWN).append(" more");
                break;
            }
            out.append("\n- ").append(show(e.getKey())).append(": ").append(String.join(", ", e.getValue()));
        }
        return out.toString();
    }

    private static String show(Region r) {
        return r.world() + " " + r.min().x() + "," + r.min().y() + "," + r.min().z() + " " + r.max().x() + ","
                + r.max().y() + "," + r.max().z();
    }
}
