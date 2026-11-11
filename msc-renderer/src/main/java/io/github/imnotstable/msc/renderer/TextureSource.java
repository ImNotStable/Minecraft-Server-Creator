package io.github.imnotstable.msc.renderer;

import java.awt.image.BufferedImage;
import java.util.Optional;

/** Where block textures come from. The renderer falls back to its colour table when one is missing. */
@FunctionalInterface
public interface TextureSource {
    /**
     * Looks up a texture.
     *
     * @param namespace for example {@code minecraft}
     * @param path      path below {@code textures/} without extension, for example {@code block/stone}
     */
    Optional<BufferedImage> texture(String namespace, String path);

    /** No textures: every block is drawn with its table colour. */
    static TextureSource none() {
        return (namespace, path) -> Optional.empty();
    }
}
