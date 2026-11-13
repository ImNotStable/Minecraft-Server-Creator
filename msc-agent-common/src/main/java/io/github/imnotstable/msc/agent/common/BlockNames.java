package io.github.imnotstable.msc.agent.common;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** "Did you mean" for block ids: AIs guess names like chain (now iron_chain) or polished_diorite_wall. */
public final class BlockNames {
    private BlockNames() {
    }

    /** Up to three known ids sharing the most words with {@code wanted}; fewer extra words and closer length win ties. */
    public static List<String> suggest(String wanted, Iterable<String> known) {
        String bare = wanted.contains(":") ? wanted.substring(wanted.indexOf(':') + 1) : wanted;
        Set<String> words = Set.of(bare.split("_"));
        record Match(String id, int shared, int extra) {
        }
        List<Match> matches = new ArrayList<>();
        for (String id : known) {
            int shared = 0;
            int extra = 0;
            for (String w : id.split("_")) {
                if (words.contains(w)) {
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
}
