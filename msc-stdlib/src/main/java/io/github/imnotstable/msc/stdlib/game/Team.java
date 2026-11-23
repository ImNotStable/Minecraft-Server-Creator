package io.github.imnotstable.msc.stdlib.game;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A side in a game: a name, a colour (a MiniMessage or dye colour name such as {@code red}, for the platform to show)
 * and its members, at most {@code maxSize} of them.
 */
public final class Team {
    private final String name;
    private final String color;
    private final int maxSize;
    private final Set<UUID> members = new LinkedHashSet<>();
    private long score;

    public Team(String name, String color, int maxSize) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("team " + name + " needs room for at least one player");
        }
        this.name = name;
        this.color = color;
        this.maxSize = maxSize;
    }

    public String name() {
        return name;
    }

    public String color() {
        return color;
    }

    public int maxSize() {
        return maxSize;
    }

    public Set<UUID> members() {
        return Collections.unmodifiableSet(members);
    }

    public int size() {
        return members.size();
    }

    public boolean full() {
        return members.size() >= maxSize;
    }

    public boolean contains(UUID player) {
        return members.contains(player);
    }

    /** False when the team is full or already had the player. */
    boolean add(UUID player) {
        return !full() && members.add(player);
    }

    boolean remove(UUID player) {
        return members.remove(player);
    }

    public long score() {
        return score;
    }

    void addScore(long points) {
        score += points;
    }

    void reset() {
        members.clear();
        score = 0;
    }

    @Override
    public String toString() {
        return name + members;
    }
}
