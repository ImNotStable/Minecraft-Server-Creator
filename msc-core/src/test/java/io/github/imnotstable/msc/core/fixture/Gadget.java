package io.github.imnotstable.msc.core.fixture;

import java.util.List;
import java.util.Map;

/** A class with a bit of everything, indexed by {@code ApiIndexTest}. */
public class Gadget implements Widget {
    public static final int MAX_SIZE = 64;
    protected String label;
    int hidden;

    public Gadget(String label) {
        this.label = label;
    }

    @Override
    public String label() {
        return label;
    }

    public <T extends Comparable<T>> Map<String, List<? extends T>> sortInto(List<T> items, int[] order) {
        return Map.of();
    }

    @Deprecated
    public void oldWay() {
    }

    public static Gadget create(String label, Mode mode) {
        return new Gadget(label);
    }

    void packagePrivate() {
    }

    /** A nested enum. */
    public enum Mode {
        FAST, SAFE
    }
}
