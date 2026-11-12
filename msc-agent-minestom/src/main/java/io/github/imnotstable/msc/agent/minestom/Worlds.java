package io.github.imnotstable.msc.agent.minestom;

import io.github.imnotstable.msc.agent.common.AgentWorlds;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.tag.Tag;

/**
 * World names for Minestom instances, which have only UUIDs. A server names an instance with
 * {@link MscAgent#name(Instance, String)}, which stores the name in a tag on the instance. An
 * unnamed instance is known by its UUID.
 */
final class Worlds implements AgentWorlds<Block> {
    static final Tag<String> NAME = Tag.String("msc:world");

    private final Supplier<? extends Iterable<Instance>> instances;
    private final Duration chunkTimeout;

    /** @param chunkTimeout how long an operation waits for the chunks it touches to load */
    Worlds(Supplier<? extends Iterable<Instance>> instances, Duration chunkTimeout) {
        this.instances = instances;
        this.chunkTimeout = chunkTimeout;
    }

    static String nameOf(Instance instance) {
        String name = instance.getTag(NAME);
        return name == null ? instance.getUuid().toString() : name;
    }

    /** The instance with this name (or UUID). */
    @Override
    public Optional<MinestomWorld> byName(String name) {
        for (Instance instance : instances.get()) {
            if (name.equals(nameOf(instance))) {
                return Optional.of(new MinestomWorld(instance, name, chunkTimeout));
            }
        }
        return Optional.empty();
    }

    /** Every world name, named instances first, each group sorted. */
    @Override
    public List<String> names() {
        List<Instance> all = new ArrayList<>();
        instances.get().forEach(all::add);
        return all.stream()
                .sorted(Comparator.comparing((Instance i) -> i.getTag(NAME) == null).thenComparing(Worlds::nameOf))
                .map(Worlds::nameOf)
                .toList();
    }

    @Override
    public Block parse(String state) {
        return BlockStates.parse(state);
    }
}
