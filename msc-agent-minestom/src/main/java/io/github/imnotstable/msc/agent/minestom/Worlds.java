package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.protocol.message.ErrorCodes;
import io.github.imnotstable.msc.protocol.transport.OpException;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.minestom.server.instance.Instance;
import net.minestom.server.tag.Tag;

/**
 * World names for Minestom instances, which have only UUIDs. A server names an instance with
 * {@link MscAgent#name(Instance, String)}, which stores the name in a tag on the instance. An
 * unnamed instance is known by its UUID.
 */
final class Worlds {
    static final Tag<String> NAME = Tag.String("msc:world");

    private final Supplier<? extends Iterable<Instance>> instances;

    Worlds(Supplier<? extends Iterable<Instance>> instances) {
        this.instances = instances;
    }

    static String nameOf(Instance instance) {
        String name = instance.getTag(NAME);
        return name == null ? instance.getUuid().toString() : name;
    }

    /** The instance with this name (or UUID). */
    Instance get(String name) {
        for (Instance instance : instances.get()) {
            if (name.equals(nameOf(instance))) {
                return instance;
            }
        }
        throw new OpException(ErrorCodes.NOT_FOUND, "no world named " + name + "; worlds: " + names());
    }

    /** Every world name, named instances first, each group sorted. */
    List<String> names() {
        List<Instance> all = new java.util.ArrayList<>();
        instances.get().forEach(all::add);
        return all.stream()
                .sorted(Comparator.comparing((Instance i) -> i.getTag(NAME) == null).thenComparing(Worlds::nameOf))
                .map(Worlds::nameOf)
                .toList();
    }
}
