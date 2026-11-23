package io.github.imnotstable.msc.stdlib.npc;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * A conversation with an NPC: pages of lines, each ending in choices that lead to another page, run an action, or
 * close; choices can be hidden by a condition (a quest stage, an item). It only keeps where each player is; the
 * platform's {@code Npcs} shows a page as chat lines with clickable choices.
 *
 * <pre>{@code
 * Dialogue smith = Dialogue.builder("start")
 *         .page("start", List.of("Need a blade sharpened?"),
 *               choice("Yes, please", "pay"), choice("Just looking", null))
 *         .page("pay", List.of("That'll be 10 coins."),
 *               Dialogue.action("Pay", "sharpen"))
 *         .onAction((player, action) -> { if (action.equals("sharpen")) sharpen(player); })
 *         .build();
 * }</pre>
 */
public final class Dialogue {
    /** A choice: its label, the page it opens (null closes), an action id to run (or null), and when it shows. */
    public record Choice(String label, String next, String action, Predicate<UUID> when) {
    }

    public record Page(String id, List<String> lines, List<Choice> choices) {
    }

    private final String start;
    private final Map<String, Page> pages;
    private final BiConsumer<UUID, String> onAction;
    private final Map<UUID, String> at = new ConcurrentHashMap<>();

    private Dialogue(String start, Map<String, Page> pages, BiConsumer<UUID, String> onAction) {
        for (Page p : pages.values()) {
            for (Choice c : p.choices()) {
                if (c.next() != null && !pages.containsKey(c.next())) {
                    throw new IllegalArgumentException("page " + p.id() + " leads to missing page " + c.next());
                }
            }
        }
        if (!pages.containsKey(start)) {
            throw new IllegalArgumentException("no start page " + start);
        }
        this.start = start;
        this.pages = Map.copyOf(pages);
        this.onAction = onAction;
    }

    /** A choice that opens a page, or closes when {@code next} is null. */
    public static Choice choice(String label, String next) {
        return new Choice(label, next, null, null);
    }

    /** A choice that runs an action and closes. */
    public static Choice action(String label, String action) {
        return new Choice(label, null, action, null);
    }

    /** A choice shown only when {@code when} holds for the player. */
    public static Choice choice(String label, String next, String action, Predicate<UUID> when) {
        return new Choice(label, next, action, when);
    }

    public static Builder builder(String start) {
        return new Builder(start);
    }

    /** Start (or restart) a player's conversation; returns the first page. */
    public Page open(UUID player) {
        at.put(player, start);
        return pages.get(start);
    }

    /** The page a player is on, if talking. */
    public Optional<Page> current(UUID player) {
        String id = at.get(player);
        return id == null ? Optional.empty() : Optional.of(pages.get(id));
    }

    /** The choices of the player's page they may see. */
    public List<Choice> choices(UUID player) {
        return current(player).map(p -> p.choices().stream().filter(c -> c.when() == null || c.when().test(player))
                .toList()).orElse(List.of());
    }

    /**
     * Pick the {@code index}-th visible choice: runs its action, moves to its page, or closes. Returns the new page,
     * empty when the conversation closed or the index is not a choice.
     */
    public Optional<Page> choose(UUID player, int index) {
        List<Choice> visible = choices(player);
        if (index < 0 || index >= visible.size()) {
            return current(player);
        }
        Choice c = visible.get(index);
        if (c.action() != null) {
            onAction.accept(player, c.action());
        }
        if (c.next() == null) {
            at.remove(player);
            return Optional.empty();
        }
        at.put(player, c.next());
        return Optional.of(pages.get(c.next()));
    }

    public void close(UUID player) {
        at.remove(player);
    }

    public static final class Builder {
        private final String start;
        private final Map<String, Page> pages = new LinkedHashMap<>();
        private BiConsumer<UUID, String> onAction = (p, a) -> { };

        private Builder(String start) {
            this.start = start;
        }

        public Builder page(String id, List<String> lines, Choice... choices) {
            pages.put(id, new Page(id, List.copyOf(lines), List.of(choices)));
            return this;
        }

        public Builder onAction(BiConsumer<UUID, String> action) {
            this.onAction = action;
            return this;
        }

        public Dialogue build() {
            return new Dialogue(start, new LinkedHashMap<>(pages), onAction);
        }
    }

    /** Lines with {@code {player}} filled in, for showing. */
    public static List<String> fill(List<String> lines, String playerName) {
        List<String> out = new ArrayList<>(lines.size());
        for (String l : lines) {
            out.add(l.replace("{player}", playerName));
        }
        return out;
    }
}
