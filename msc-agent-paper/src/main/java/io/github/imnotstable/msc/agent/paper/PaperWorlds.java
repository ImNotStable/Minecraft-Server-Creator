package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.AgentWorlds;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/** The server's worlds by their Bukkit names, and Bukkit block data parsing. */
final class PaperWorlds implements AgentWorlds<BlockData> {
    private final Server server;

    PaperWorlds(Server server) {
        this.server = server;
    }

    @Override
    public List<String> names() {
        return server.getWorlds().stream().map(World::getName).toList();
    }

    /** The world with this Bukkit name, or with this key ({@code minecraft:arena}), as clients know it. */
    @Override
    public Optional<PaperWorld> byName(String name) {
        World world = server.getWorld(name);
        if (world == null && name.indexOf(':') > 0) {
            NamespacedKey key = NamespacedKey.fromString(name);
            world = key == null ? null : server.getWorld(key);
        }
        return Optional.ofNullable(world).map(PaperWorld::new);
    }

    @Override
    public BlockData parse(String state) {
        try {
            return server.createBlockData(state);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(explain(state, e), e);
        }
    }

    /** Why {@code state} failed: bad properties on a real block, or an unknown id with close names. */
    private static String explain(String state, IllegalArgumentException cause) {
        String id = (state.contains("[") ? state.substring(0, state.indexOf('[')) : state).trim()
                .toLowerCase(Locale.ROOT);
        try {
            List<String> blocks = Arrays.stream(Material.values())
                    .filter(m -> !m.name().startsWith("LEGACY_") && m.isBlock())
                    .map(m -> m.getKey().getKey()).toList();
            String bare = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
            if (blocks.contains(bare)) {
                return "bad block properties in " + state + ": " + cause.getMessage();
            }
            List<String> close = BlockNames.suggest(bare, blocks);
            return "unknown block state: " + state
                    + (close.isEmpty() ? "" : "; did you mean " + String.join(", ", close) + "?");
        } catch (RuntimeException e) {
            return "unknown block state: " + state; // suggestions are a nicety; never hide the real error
        }
    }
}
