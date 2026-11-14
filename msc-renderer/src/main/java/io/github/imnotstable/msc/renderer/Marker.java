package io.github.imnotstable.msc.renderer;

import io.github.imnotstable.msc.protocol.Limits;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Something that is not a block, drawn on top of the image. Entities are numbered dots coloured by {@link Kind};
 * holograms and signs show their words in a text box. The caption lists each drawn marker by number with its label
 * and counts the rest by type.
 * Build one with {@link #entity}, {@link #sign} or {@link #text}.
 *
 * @param x     world x: an entity's feet, a hologram's anchor, or a sign block's centre
 * @param y     world y, same reference as {@code x}
 * @param z     world z, same reference as {@code x}
 * @param label short text for the caption, such as {@code villager "Shopkeeper"}
 * @param kind  how it is drawn
 * @param text  lines drawn in the image for {@link Kind#TEXT} and {@link Kind#SIGN}; empty for dots
 */
public record Marker(double x, double y, double z, String label, Kind kind, List<String> text) {
    /** Most characters of a name kept in a caption label. */
    static final int MAX_NAME = 40;

    /** What a marker stands for, which sets its colour and shape. */
    public enum Kind {
        /** Blue dot. */
        PLAYER,
        /** Red dot: monsters. */
        HOSTILE,
        /** Green dot: animals, villagers, NPCs and anything else alive. */
        CREATURE,
        /** Yellow dot: items, minecarts, boats, frames, displays and other non-living entities. */
        OBJECT,
        /** Magenta dot: not classified. */
        OTHER,
        /** Floating text in a dark box, as a text display or named armor-stand hologram shows it. */
        TEXT,
        /** A sign's words in a light box at the sign. */
        SIGN
    }

    private static final Set<String> HOSTILE = Set.of("zombie", "husk", "drowned", "zombie_villager", "skeleton",
            "stray", "bogged", "wither_skeleton", "creeper", "spider", "cave_spider", "enderman", "endermite",
            "silverfish", "witch", "slime", "magma_cube", "blaze", "ghast", "guardian", "elder_guardian", "shulker",
            "phantom", "vex", "vindicator", "evoker", "pillager", "ravager", "illusioner", "piglin", "piglin_brute",
            "hoglin", "zoglin", "warden", "wither", "ender_dragon", "breeze", "creaking", "giant");
    private static final Set<String> OBJECTS = Set.of("item", "experience_orb", "armor_stand", "item_frame",
            "glow_item_frame", "painting", "falling_block", "tnt", "end_crystal", "block_display", "item_display",
            "interaction", "marker", "firework_rocket", "snowball", "egg", "ender_pearl", "potion", "splash_potion",
            "lingering_potion", "experience_bottle", "eye_of_ender", "fishing_bobber", "leash_knot",
            "area_effect_cloud", "lightning_bolt", "evoker_fangs", "llama_spit", "shulker_bullet", "fireball",
            "small_fireball", "dragon_fireball", "wither_skull", "wind_charge", "breeze_wind_charge", "ominous_item_spawner");

    public Marker {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        text = List.copyOf(text);
    }

    /** A plain magenta dot with a caption label. */
    public Marker(double x, double y, double z, String label) {
        this(x, y, z, label, Kind.OTHER, List.of());
    }

    /**
     * An entity as {@code world.read} reports it. Players, monsters, other living things and objects get their own
     * dot colour. A text display, or an armor stand with a name (the usual hologram), shows its words as floating
     * text instead.
     *
     * @param type entity type id, such as {@code minecraft:zombie}
     * @param name custom name, player name or a text display's text; null or blank for none
     */
    public static Marker entity(String type, String name, double x, double y, double z) {
        String brief = type.startsWith("minecraft:") ? type.substring("minecraft:".length()) : type;
        String words = name == null ? "" : name.strip();
        String label = brief + (words.isEmpty() ? "" : " \"" + shorten(words.replaceAll("\\s+", " ")) + "\"");
        boolean hologram = brief.equals("text_display") || brief.equals("armor_stand") && !words.isEmpty();
        if (hologram && !words.isEmpty()) {
            return new Marker(x, y, z, label, Kind.TEXT, words.lines().toList());
        }
        return new Marker(x, y, z, label, kind(brief), List.of());
    }

    /** A sign at a block position; its non-blank lines are drawn on the image at the block's centre. */
    public static Marker sign(int x, int y, int z, List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                kept.add(line.strip());
            }
        }
        String label = "sign" + (kept.isEmpty() ? " (blank)" : " \"" + shorten(String.join(" / ", kept)) + "\"");
        return new Marker(x + 0.5, y + 0.5, z + 0.5, label, Kind.SIGN, kept);
    }

    /** Floating text at a point, such as a hologram a plugin draws with packets. */
    public static Marker text(double x, double y, double z, String text) {
        String words = text.strip();
        return new Marker(x, y, z, "text \"" + shorten(words.replaceAll("\\s+", " ")) + "\"", Kind.TEXT,
                words.lines().toList());
    }

    private static Kind kind(String brief) {
        String id = brief.toLowerCase(Locale.ROOT);
        if (id.equals("player")) {
            return Kind.PLAYER;
        }
        if (HOSTILE.contains(id)) {
            return Kind.HOSTILE;
        }
        if (OBJECTS.contains(id) || id.endsWith("minecart") || id.endsWith("boat") || id.endsWith("raft")
                || id.endsWith("arrow") || id.endsWith("trident") || id.endsWith("_display")) {
            return Kind.OBJECT;
        }
        return id.contains(":") ? Kind.OTHER : Kind.CREATURE;
    }

    /** World text (a name, sign lines, a hologram) cut for the legend, never through an emoji (#479). */
    private static String shorten(String s) {
        return s.length() > MAX_NAME ? Limits.head(s, MAX_NAME - 1) + "..." : s;
    }
}
