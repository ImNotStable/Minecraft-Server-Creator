package io.github.imnotstable.msc.textures;

import java.util.regex.Pattern;

/**
 * A resource location such as {@code msc:item/ruby}. Without a namespace, {@code msc} is used, matching
 * {@code msc-core}'s texture tools; vanilla overrides use {@code minecraft:item/diamond}.
 */
public record ResourceId(String namespace, String path) {
    public static final String DEFAULT_NAMESPACE = "msc";
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_.-]+(/[a-z0-9_.-]+)*");

    public ResourceId {
        if (!NAMESPACE.matcher(namespace).matches() || !PATH.matcher(path).matches() || path.contains("..")) {
            throw new TextureException("bad resource name \"" + namespace + ":" + path
                    + "\"; use lowercase like block/ruby_ore or myplugin:item/ruby");
        }
    }

    public static ResourceId parse(String text) {
        return parse(text, DEFAULT_NAMESPACE);
    }

    /** Parse with a given default namespace; a trailing {@code .png} or {@code .json} is dropped. */
    public static ResourceId parse(String text, String defaultNamespace) {
        String t = text.trim();
        for (String ext : new String[] {".png", ".json"}) {
            if (t.endsWith(ext)) {
                t = t.substring(0, t.length() - ext.length());
            }
        }
        int colon = t.indexOf(':');
        return colon < 0 ? new ResourceId(defaultNamespace, t) : new ResourceId(t.substring(0, colon), t.substring(colon + 1));
    }

    /** Parse keeping the whole path, extension included, such as {@code minecraft:core/terrain.fsh}. */
    public static ResourceId parseExact(String text) {
        String t = text.trim();
        int colon = t.indexOf(':');
        return colon < 0 ? new ResourceId(DEFAULT_NAMESPACE, t) : new ResourceId(t.substring(0, colon), t.substring(colon + 1));
    }

    /** The last path segment, such as {@code ruby} for {@code item/ruby}. */
    public String name() {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
