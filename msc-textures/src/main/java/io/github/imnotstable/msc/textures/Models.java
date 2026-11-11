package io.github.imnotstable.msc.textures;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Block and item model JSON built on vanilla parents, so no element geometry is ever hand-written. */
public final class Models {
    /** A vanilla parent model and the texture slots it needs. */
    public enum Kind {
        /** One texture on all six faces. */
        CUBE_ALL("minecraft:block/cube_all", List.of("all")),
        /** Logs and pillars: {@code end} on top and bottom, {@code side} around. */
        CUBE_COLUMN("minecraft:block/cube_column", List.of("end", "side")),
        /** Grass-like: different top, bottom and side. */
        CUBE_BOTTOM_TOP("minecraft:block/cube_bottom_top", List.of("top", "bottom", "side")),
        /** Furnace-like: a front face, sides and a top. */
        ORIENTABLE("minecraft:block/orientable", List.of("front", "side", "top")),
        /** Plants and flowers: two crossed planes. */
        CROSS("minecraft:block/cross", List.of("cross")),
        /** Flat items such as gems and ingots. */
        ITEM("minecraft:item/generated", List.of("layer0")),
        /** Tools and weapons, held like a sword. */
        HANDHELD("minecraft:item/handheld", List.of("layer0"));

        private final String parent;
        private final List<String> slots;

        Kind(String parent, List<String> slots) {
            this.parent = parent;
            this.slots = slots;
        }

        public String parent() {
            return parent;
        }

        public List<String> slots() {
            return slots;
        }

        public boolean isItem() {
            return this == ITEM || this == HANDHELD;
        }

        /** {@code cube_all}, {@code cube_column}, {@code item} and so on. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind parse(String text) {
            for (Kind k : values()) {
                if (k.id().equals(text.trim().toLowerCase(Locale.ROOT))) {
                    return k;
                }
            }
            StringBuilder names = new StringBuilder();
            for (Kind k : values()) {
                names.append(names.isEmpty() ? "" : ", ").append(k.id());
            }
            throw new TextureException("unknown model kind \"" + text + "\"; use one of " + names);
        }
    }

    private Models() {
    }

    /**
     * @param textures texture for each slot of {@code kind}; a single texture may be given under any
     *                 key and then fills every slot
     */
    public static JsonObject model(Kind kind, Map<String, ResourceId> textures) {
        JsonObject json = new JsonObject();
        json.addProperty("parent", kind.parent());
        JsonObject tex = new JsonObject();
        ResourceId only = textures.size() == 1 ? textures.values().iterator().next() : null;
        for (String slot : kind.slots()) {
            ResourceId id = textures.containsKey(slot) ? textures.get(slot) : only;
            if (id == null) {
                throw new TextureException(kind.id() + " needs textures for " + String.join(", ", kind.slots())
                        + "; missing " + slot);
            }
            tex.addProperty(slot, id.toString());
        }
        for (String key : textures.keySet()) {
            if (only == null && !kind.slots().contains(key)) {
                throw new TextureException(kind.id() + " has no texture slot \"" + key + "\"; slots are "
                        + String.join(", ", kind.slots()));
            }
        }
        json.add("textures", tex);
        return json;
    }

    /** The {@code assets/<ns>/items/<name>.json} item definition that points at a model. */
    public static JsonObject itemDefinition(ResourceId model) {
        JsonObject ref = new JsonObject();
        ref.addProperty("type", "minecraft:model");
        ref.addProperty("model", model.toString());
        JsonObject json = new JsonObject();
        json.add("model", ref);
        return json;
    }
}
