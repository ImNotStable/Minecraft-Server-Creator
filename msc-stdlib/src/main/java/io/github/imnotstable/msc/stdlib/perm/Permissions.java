package io.github.imnotstable.msc.stdlib.perm;

import io.github.imnotstable.msc.stdlib.db.Codec;
import io.github.imnotstable.msc.stdlib.db.Database;
import io.github.imnotstable.msc.stdlib.db.Store;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ranks and permissions for servers without a permission plugin, kept in a {@link Store} (SQL, JSON files or memory),
 * so several servers can share them. Nodes are dotted ({@code shop.buy}); {@code shop.*} grants every node under
 * {@code shop}, {@code *} grants all, and {@code -shop.sell} denies. Users have their own nodes (optionally for a
 * time) and groups; groups inherit from parents and have a weight: a user's own nodes beat their groups', a heavier
 * group beats a lighter one, a more specific node beats a wildcard. The {@code default} group applies to everyone.
 *
 * <pre>{@code
 * Permissions perms = Permissions.sql(db, "perms");
 * perms.group("default").node("spawn.use").save();
 * perms.group("vip").parent("default").weight(10).prefix("<gold>[VIP] ").node("kit.vip").save();
 * perms.addGroup(player, "vip");
 * perms.grant(player, "fly", Duration.ofHours(1));
 * if (perms.has(player, "kit.vip")) ...
 * }</pre>
 * The platform adapters (Paper's {@code PaperPermissions}) feed these into the server's own permission checks.
 */
public final class Permissions {
    public static final String DEFAULT = "default";

    /** A group: its nodes ({@code -node} denies), parents, weight and chat prefix and suffix. */
    public record Group(String name, List<String> nodes, List<String> parents, int weight, String prefix, String suffix) {
    }

    /** A user: groups and own nodes, each node with when it ends (epoch millis, 0 for never). */
    public record User(List<String> groups, Map<String, Long> nodes) {
    }

    private final Store<Group> groups;
    private final Store<User> users;
    private final Clock clock;
    private final Map<UUID, Map<String, Boolean>> cache = new ConcurrentHashMap<>();

    public Permissions(Store<Group> groups, Store<User> users, Clock clock) {
        this.groups = groups;
        this.users = users;
        this.clock = clock;
    }

    /** Groups and users in tables {@code <prefix>_groups} and {@code <prefix>_users}. */
    public static Permissions sql(Database db, String prefix) {
        return new Permissions(Store.sql(db, prefix + "_groups", Codec.json(Group.class)),
                Store.sql(db, prefix + "_users", Codec.json(User.class)), Clock.systemUTC());
    }

    /** In memory, for tests and single-run games. */
    public static Permissions memory() {
        return new Permissions(Store.memory(), Store.memory(), Clock.systemUTC());
    }

    // ---------------------------------------------------------------- groups

    /** Edit a group (made when missing); call {@link GroupEdit#save()} to keep it. */
    public GroupEdit group(String name) {
        return new GroupEdit(groups.get(name).orElse(new Group(name, List.of(), List.of(), 0, "", "")));
    }

    public Optional<Group> findGroup(String name) {
        return groups.get(name);
    }

    public void deleteGroup(String name) {
        groups.remove(name);
        cache.clear();
    }

    public List<String> groupNames() {
        return groups.keys();
    }

    public final class GroupEdit {
        private final String name;
        private final List<String> nodes;
        private final List<String> parents;
        private int weight;
        private String prefix;
        private String suffix;

        private GroupEdit(Group g) {
            name = g.name();
            nodes = new ArrayList<>(g.nodes());
            parents = new ArrayList<>(g.parents());
            weight = g.weight();
            prefix = g.prefix();
            suffix = g.suffix();
        }

        public GroupEdit node(String node) {
            if (!nodes.contains(node)) {
                nodes.add(node);
            }
            return this;
        }

        public GroupEdit deny(String node) {
            return node("-" + node);
        }

        public GroupEdit unset(String node) {
            nodes.remove(node);
            nodes.remove("-" + node);
            return this;
        }

        public GroupEdit parent(String group) {
            if (!parents.contains(group)) {
                parents.add(group);
            }
            return this;
        }

        public GroupEdit weight(int weight) {
            this.weight = weight;
            return this;
        }

        public GroupEdit prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        public GroupEdit suffix(String suffix) {
            this.suffix = suffix;
            return this;
        }

        public Group save() {
            Group g = new Group(name, List.copyOf(nodes), List.copyOf(parents), weight, prefix, suffix);
            groups.put(name, g);
            cache.clear();
            return g;
        }
    }

    // ---------------------------------------------------------------- users

    private User user(UUID id) {
        return users.get(id.toString()).orElse(new User(List.of(), Map.of()));
    }

    private void save(UUID id, User u) {
        users.put(id.toString(), u);
        cache.remove(id);
    }

    public void addGroup(UUID player, String group) {
        User u = user(player);
        if (!u.groups().contains(group)) {
            List<String> g = new ArrayList<>(u.groups());
            g.add(group);
            save(player, new User(g, u.nodes()));
        }
    }

    public void removeGroup(UUID player, String group) {
        User u = user(player);
        List<String> g = new ArrayList<>(u.groups());
        if (g.remove(group)) {
            save(player, new User(g, u.nodes()));
        }
    }

    /** The user's groups, without the default one. */
    public List<String> groups(UUID player) {
        return user(player).groups();
    }

    /** Give a node for good. */
    public void grant(UUID player, String node) {
        put(player, node, 0);
    }

    /** Give a node for a while. */
    public void grant(UUID player, String node, Duration time) {
        put(player, node, clock.millis() + time.toMillis());
    }

    /** Deny a node, even when a group grants it. */
    public void deny(UUID player, String node) {
        put(player, "-" + node, 0);
    }

    /** Take a node (granted or denied) away. */
    public void unset(UUID player, String node) {
        User u = user(player);
        Map<String, Long> n = new LinkedHashMap<>(u.nodes());
        if (n.remove(node) != null | n.remove("-" + node) != null) {
            save(player, new User(u.groups(), n));
        }
    }

    private void put(UUID player, String node, long until) {
        User u = user(player);
        Map<String, Long> n = new LinkedHashMap<>(u.nodes());
        n.put(node, until);
        save(player, new User(u.groups(), n));
    }

    // ---------------------------------------------------------------- checks

    /** Whether the player has {@code node}. */
    public boolean has(UUID player, String node) {
        Map<String, Boolean> effective = effective(player);
        for (String candidate : candidates(node)) {
            Boolean v = effective.get(candidate);
            if (v != null) {
                return v;
            }
        }
        return false;
    }

    /** {@code a.b.c}, then {@code a.b.*}, {@code a.*}, {@code *}: most specific first. */
    static List<String> candidates(String node) {
        List<String> out = new ArrayList<>();
        out.add(node);
        String n = node;
        int dot;
        while ((dot = n.lastIndexOf('.')) > 0) {
            n = n.substring(0, dot);
            out.add(n + ".*");
        }
        out.add("*");
        return out;
    }

    /** Every node the player has set, true or false, after the precedence rules; for platform adapters. */
    public Map<String, Boolean> effective(UUID player) {
        Map<String, Boolean> cached = cache.get(player);
        long now = clock.millis();
        User u = user(player);
        boolean expiring = u.nodes().values().stream().anyMatch(t -> t > 0);
        if (cached != null && !expiring) {
            return cached;
        }
        Map<String, Boolean> out = new LinkedHashMap<>();
        // Lightest group first, so heavier groups and then the user's own nodes overwrite.
        List<Group> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> start = new ArrayList<>();
        start.add(DEFAULT);
        start.addAll(u.groups());
        for (String g : start) {
            collect(g, chain, seen);
        }
        chain.sort(Comparator.comparingInt(Group::weight));
        for (Group g : chain) {
            apply(out, g.nodes());
        }
        List<String> own = new ArrayList<>();
        u.nodes().forEach((node, until) -> {
            if (until == 0 || until > now) {
                own.add(node);
            }
        });
        apply(out, own);
        if (!expiring) {
            cache.put(player, out);
        }
        return out;
    }

    private void collect(String name, List<Group> chain, Set<String> seen) {
        if (!seen.add(name)) {
            return;
        }
        groups.get(name).ifPresent(g -> {
            for (String parent : g.parents()) {
                collect(parent, chain, seen);
            }
            chain.add(g);
        });
    }

    private static void apply(Map<String, Boolean> out, List<String> nodes) {
        for (String n : nodes) {
            if (n.startsWith("-")) {
                out.put(n.substring(1), false);
            } else {
                out.put(n, true);
            }
        }
    }

    /** The prefix of the heaviest group that has one, or "". */
    public String prefix(UUID player) {
        return meta(player, true);
    }

    /** The suffix of the heaviest group that has one, or "". */
    public String suffix(UUID player) {
        return meta(player, false);
    }

    private String meta(UUID player, boolean prefix) {
        List<Group> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> start = new ArrayList<>();
        start.add(DEFAULT);
        start.addAll(user(player).groups());
        for (String g : start) {
            collect(g, chain, seen);
        }
        return chain.stream().sorted(Comparator.comparingInt(Group::weight).reversed())
                .map(g -> prefix ? g.prefix() : g.suffix()).filter(s -> s != null && !s.isEmpty()).findFirst().orElse("");
    }

    /** Forget cached results, after the stores were changed elsewhere (another server). */
    public void invalidate() {
        cache.clear();
    }
}
