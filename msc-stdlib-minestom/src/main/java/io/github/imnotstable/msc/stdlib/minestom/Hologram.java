package io.github.imnotstable.msc.stdlib.minestom;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.instance.Instance;

import java.util.List;

/**
 * Floating text (a text display entity): lobby leaderboards, NPC names, arena labels. Lines are MiniMessage.
 *
 * <pre>{@code
 * Hologram top = Hologram.spawn(instance, new Pos(0, 65, 0), "<gold><bold>Top killers", "<gray>1. Alex - 52");
 * top.lines(List.of("<gold><bold>Top killers", "<gray>1. Bo - 60")).scale(1.5f);
 * }</pre>
 */
public final class Hologram {
    private final Entity entity;
    private final TextDisplayMeta meta;

    private Hologram(Entity entity) {
        this.entity = entity;
        this.meta = (TextDisplayMeta) entity.getEntityMeta();
    }

    /** Spawns turning to face each viewer. */
    public static Hologram spawn(Instance instance, Pos at, String... lines) {
        Entity e = new Entity(EntityType.TEXT_DISPLAY);
        e.setNoGravity(true);
        Hologram h = new Hologram(e);
        h.meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
        h.lines(List.of(lines));
        e.setInstance(instance, at);
        return h;
    }

    public Hologram lines(List<String> lines) {
        meta.setText(Component.join(JoinConfiguration.newlines(), lines.stream().map(Text::mm).toList()));
        return this;
    }

    public Hologram text(Component text) {
        meta.setText(text);
        return this;
    }

    public Hologram scale(float scale) {
        meta.setScale(new Vec(scale, scale, scale));
        return this;
    }

    /** ARGB background, 0 for none. */
    public Hologram background(int argb) {
        meta.setBackgroundColor(argb);
        return this;
    }

    public Hologram shadow(boolean shadow) {
        meta.setShadow(shadow);
        return this;
    }

    public Hologram seeThrough(boolean see) {
        meta.setSeeThrough(see);
        return this;
    }

    public Hologram teleport(Pos to) {
        entity.teleport(to);
        return this;
    }

    public void remove() {
        entity.remove();
    }

    public Entity entity() {
        return entity;
    }
}
