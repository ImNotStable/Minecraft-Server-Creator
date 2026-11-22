package io.github.imnotstable.msc.stdlib.paper;

import io.github.imnotstable.msc.stdlib.text.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.List;

/**
 * Floating text (a text display entity): leaderboards in the lobby, NPC names, shop signs, arena labels. Not saved with
 * the world: recreate holograms in {@code onEnable}. Lines are MiniMessage.
 *
 * <pre>{@code
 * Hologram top = Hologram.spawn(loc, "<gold><bold>Top killers", "<gray>1. Alex - 52");
 * top.lines(List.of("<gold><bold>Top killers", "<gray>1. Bo - 60"));
 * top.scale(1.5f).background(Color.fromARGB(100, 0, 0, 0));
 * top.remove();
 * }</pre>
 */
public final class Hologram {
    private final TextDisplay display;

    private Hologram(TextDisplay display) {
        this.display = display;
    }

    /** Spawns facing every viewer (billboard center), with the default dark background. */
    public static Hologram spawn(Location at, String... lines) {
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, e -> {
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.text(join(List.of(lines)));
        });
        return new Hologram(d);
    }

    public Hologram lines(List<String> lines) {
        display.text(join(lines));
        return this;
    }

    public Hologram text(Component text) {
        display.text(text);
        return this;
    }

    /** Size: 1 normal, 2 double. */
    public Hologram scale(float scale) {
        Transformation t = display.getTransformation();
        display.setTransformation(new Transformation(t.getTranslation(), new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
        return this;
    }

    /** Background colour with alpha ({@code Color.fromARGB(0, 0, 0, 0)} for none). */
    public Hologram background(Color argb) {
        display.setBackgroundColor(argb);
        return this;
    }

    public Hologram shadow(boolean shadowed) {
        display.setShadowed(shadowed);
        return this;
    }

    /** Visible through blocks. */
    public Hologram seeThrough(boolean see) {
        display.setSeeThrough(see);
        return this;
    }

    /** {@code FIXED} keeps it facing one way (a sign on a wall); {@code CENTER} turns to each viewer. */
    public Hologram billboard(Display.Billboard billboard) {
        display.setBillboard(billboard);
        return this;
    }

    public Hologram teleport(Location to) {
        display.teleport(to);
        return this;
    }

    public void remove() {
        display.remove();
    }

    public boolean valid() {
        return display.isValid();
    }

    public TextDisplay entity() {
        return display;
    }

    private static Component join(List<String> lines) {
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(),
                lines.stream().map(Text::mm).toList());
    }
}
