package io.github.imnotstable.msc.agent.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** "Did you mean" for block ids: AIs guess names like chain (now iron_chain) or polished_diorite_wall. */
public final class BlockNames {
    private BlockNames() {
    }

    /**
     * Up to three known ids sharing the most words with {@code wanted}; fewer extra words and closer length win ties. A
     * word matches with a plural s or one typo (stne_brick finds stone_bricks), which a word-for-word match missed.
     */
    public static List<String> suggest(String wanted, Iterable<String> known) {
        String bare = wanted.contains(":") ? wanted.substring(wanted.indexOf(':') + 1) : wanted;
        List<String> words = Arrays.asList(bare.split("_"));
        record Match(String id, int shared, int extra) {
        }
        List<Match> matches = new ArrayList<>();
        for (String id : known) {
            int shared = 0;
            int extra = 0;
            for (String w : id.split("_")) {
                if (words.stream().anyMatch(v -> close(v, w))) {
                    shared++;
                } else {
                    extra++;
                }
            }
            if (shared > 0) matches.add(new Match(id, shared, extra));
        }
        return matches.stream()
                .sorted(Comparator.comparingInt(Match::shared).reversed()
                        .thenComparingInt(Match::extra)
                        .thenComparingInt(m -> Math.abs(m.id().length() - bare.length()))
                        .thenComparing(Match::id))
                .limit(3).map(Match::id).toList();
    }

    /** Same word, give or take a trailing s, or one edit apart when both are at least four letters. */
    static boolean close(String a, String b) {
        if (a.equals(b) || a.equals(b + "s") || b.equals(a + "s")) {
            return true;
        }
        return a.length() >= 4 && b.length() >= 4 && oneEdit(a, b);
    }

    private static boolean oneEdit(String a, String b) {
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        // Past the first difference the rest must match: after a substitution, an insertion or a deletion.
        return a.substring(i + (a.length() >= b.length() ? 1 : 0)).equals(b.substring(i + (b.length() >= a.length() ? 1 : 0)));
    }
}
