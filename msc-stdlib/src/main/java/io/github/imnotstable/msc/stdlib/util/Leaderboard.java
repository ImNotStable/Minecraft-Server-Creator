package io.github.imnotstable.msc.stdlib.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scores in memory for a round or a session: kills, points, laps. For lasting leaderboards use
 * {@code Repository.top(field, n)} on saved records.
 *
 * <pre>{@code
 * Leaderboard<UUID> kills = new Leaderboard<>();
 * kills.add(killer, 1);
 * kills.top(3).forEach(e -> ...e.key()... e.score()... e.rank());
 * }</pre>
 */
public final class Leaderboard<K> {
    /** One place: rank from 1, ties share a rank. */
    public record Entry<K>(K key, double score, int rank) {}

    private final Map<K, Double> scores = new ConcurrentHashMap<>();
    private final boolean lowestFirst;

    /** Highest score first. */
    public Leaderboard() {
        this(false);
    }

    /** {@code lowestFirst} for times and golf-style scores. */
    public Leaderboard(boolean lowestFirst) {
        this.lowestFirst = lowestFirst;
    }

    public double add(K key, double delta) {
        return scores.merge(key, delta, Double::sum);
    }

    public void set(K key, double score) {
        scores.put(key, score);
    }

    /** Keeps the better of the old and new score (a best lap). */
    public void best(K key, double score) {
        scores.merge(key, score, (a, b) -> lowestFirst ? Math.min(a, b) : Math.max(a, b));
    }

    public double score(K key) {
        return scores.getOrDefault(key, 0.0);
    }

    public void remove(K key) {
        scores.remove(key);
    }

    public void clear() {
        scores.clear();
    }

    public int size() {
        return scores.size();
    }

    /** Every entry in order. */
    public List<Entry<K>> ranking() {
        Comparator<Map.Entry<K, Double>> by = Map.Entry.comparingByValue();
        List<Map.Entry<K, Double>> sorted = new ArrayList<>(scores.entrySet());
        sorted.sort(lowestFirst ? by : by.reversed());
        List<Entry<K>> out = new ArrayList<>(sorted.size());
        int rank = 0;
        Double last = null;
        for (int i = 0; i < sorted.size(); i++) {
            double s = sorted.get(i).getValue();
            if (last == null || s != last) {
                rank = i + 1;
                last = s;
            }
            out.add(new Entry<>(sorted.get(i).getKey(), s, rank));
        }
        return out;
    }

    public List<Entry<K>> top(int n) {
        List<Entry<K>> r = ranking();
        return r.subList(0, Math.min(n, r.size()));
    }

    /** The key's rank from 1, or 0 when it has no score. */
    public int rank(K key) {
        return ranking().stream().filter(e -> e.key().equals(key)).mapToInt(Entry::rank).findFirst().orElse(0);
    }
}
