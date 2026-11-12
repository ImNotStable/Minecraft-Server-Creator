package io.github.imnotstable.msc.agent.paper;

import io.github.imnotstable.msc.agent.common.AgentWorld;
import io.github.imnotstable.msc.protocol.op.Ops;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Sign;
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
    private final World world;

    PaperWorld(World world) {
        this.world = world;
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
        for (Entity e : world.getNearbyEntities(box(region))) {
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
        for (Entity e : world.getNearbyEntities(box(region))) {
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
