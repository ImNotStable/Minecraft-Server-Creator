package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.AgentWorld;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.BoundingBox;

/** {@link AgentWorld} on a Bukkit {@link World}. Main thread only. */
final class PaperWorld implements AgentWorld<BlockData> {
    /** Whether blocks of a material are block entities, found once per material. */
    private static final Map<Material, Boolean> HOLDS = new ConcurrentHashMap<>();

    private final World world;
    private final DataCommands data;

    PaperWorld(World world, DataCommands data) {
        this.world = world;
        this.data = data;
    }

    /** Whether blocks in this state are block entities, which keep data. */
    static boolean holds(BlockData state) {
        return HOLDS.computeIfAbsent(state.getMaterial(), m -> state.createBlockState() instanceof TileState);
    }

    @Override
    public boolean holdsData(BlockData state) {
        return holds(state);
    }

    @Override
    public String blockData(int x, int y, int z) {
        return data.get(world.getKey().asString(), x, y, z);
    }

    @Override
    public void mergeBlockData(int x, int y, int z, Object snbt) {
        data.merge(world.getKey().asString(), x, y, z, (String) snbt);
    }

    @Override
    public String name() {
        return world.getName();
    }

    @Override
    public int minY() {
        return world.getMinHeight();
    }

    @Override
    public int maxY() {
        return world.getMaxHeight();
    }

    @Override
    public BlockData get(int x, int y, int z) {
        return world.getBlockData(x, y, z);
    }

    @Override
    public void set(int x, int y, int z, BlockData state, boolean physics) {
        world.getBlockAt(x, y, z).setBlockData(state, physics);
    }

    /** A snapshot of a block entity (sign text, container items), which {@link BlockData} leaves out. */
    @Override
    public Object blockEntity(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        return block.getState(false) instanceof TileState ? block.getState(true) : null;
    }

    @Override
    public void loadBlockEntity(int x, int y, int z, Object data) {
        ((BlockState) data).update(true, false);
    }

    @Override
    public String serialize(BlockData state) {
        return state.getAsString();
    }

    @Override
    public int surfaceY(int x, int z) {
        return world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
    }

    @Override
    public List<Ops.EntitySummary> entities(Region region, int max) {
        List<Ops.EntitySummary> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box(region), e -> inside(region, e))) {
            if (out.size() == max) {
                break;
            }
            out.add(new Ops.EntitySummary(e.getType().key().asString(), e.getX(), e.getY(), e.getZ(), name(e)));
        }
        return out;
    }

    @Override
    public List<Ops.EntityInfo> entityInfos(Region region, String type) {
        List<Ops.EntityInfo> out = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box(region), e -> inside(region, e))) {
            String key = e.getType().key().asString();
            if (type == null || type.equals(key)) {
                out.add(info(e, key));
            }
        }
        return out;
    }

    @Override
    public Ops.SignText sign(int x, int y, int z) {
        if (!(world.getBlockAt(x, y, z).getState() instanceof Sign sign)) {
            return null;
        }
        return Ops.SignText.of(x, y, z, lines(sign.getSide(Side.FRONT)), lines(sign.getSide(Side.BACK)));
    }

    /** An entity is in a region by its position, as on every platform, so split reads count it once. */
    private static boolean inside(Region region, Entity e) {
        return region.containsPoint(e.getX(), e.getY(), e.getZ());
    }

    /** Candidates whose hitbox meets the region; {@link #inside} then keeps those whose position is in it. */
    private static BoundingBox box(Region region) {
        return new BoundingBox(region.min().x(), region.min().y(), region.min().z(),
                region.max().x() + 1, region.max().y() + 1, region.max().z() + 1);
    }

    private static List<String> lines(SignSide side) {
        return side.lines().stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
    }

    private static Ops.EntityInfo info(Entity e, String type) {
        Double health = null;
        Double maxHealth = null;
        if (e instanceof LivingEntity living) {
            health = living.getHealth();
            AttributeInstance max = living.getAttribute(Attribute.MAX_HEALTH);
            maxHealth = max == null ? null : max.getValue();
        }
        return new Ops.EntityInfo(type, e.getUniqueId().toString(), e.getX(), e.getY(), e.getZ(), name(e),
                List.copyOf(new TreeSet<>(e.getScoreboardTags())), health, maxHealth);
    }

    private static String name(Entity e) {
        if (e instanceof Player p) {
            return p.getName();
        }
        Component custom = e.customName();
        if (custom == null && e instanceof TextDisplay display) {
            // A hologram's words are its text, not a name: report them so a render or summary can show them.
            custom = display.text();
        }
        return custom == null ? null : PlainTextComponentSerializer.plainText().serialize(custom);
    }
}
