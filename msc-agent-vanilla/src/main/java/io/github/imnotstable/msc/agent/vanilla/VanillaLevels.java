package io.github.imnotstable.msc.agent.vanilla;

import io.github.imnotstable.msc.agent.common.AgentWorlds;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

/** The server's levels under Paper-style names (see {@link LevelNames}). Main thread only. */
final class VanillaLevels implements AgentWorlds<BlockState> {
    private final MinecraftServer server;

    VanillaLevels(MinecraftServer server) {
        this.server = server;
    }

    /** The server's {@code level-name}: the world folder's name. */
    private String levelName() {
        return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName().toString();
    }

    @Override
    public List<String> names() {
        String levelName = levelName();
        List<String> names = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().identifier().toString();
            String name = LevelNames.name(levelName, dimension);
            if (dimension.equals(LevelNames.OVERWORLD)) {
                names.addFirst(name);
            } else {
                names.add(name);
            }
        }
        return names;
    }

    @Override
    public Optional<VanillaLevel> byName(String name) {
        String levelName = levelName();
        for (ServerLevel level : server.getAllLevels()) {
            String dimension = level.dimension().identifier().toString();
            if (LevelNames.matches(name, levelName, dimension)) {
                return Optional.of(new VanillaLevel(level, LevelNames.name(levelName, dimension)));
            }
        }
        return Optional.empty();
    }

    @Override
    public BlockState parse(String state) {
        return BlockStates.parse(state);
    }

    @Override
    public Object parseData(BlockState state, String snbt) {
        return BlockStates.parseData(state, snbt);
    }
}
