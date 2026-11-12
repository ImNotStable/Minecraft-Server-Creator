package io.github.imnotstable.msc.renderer;

/**
 * Something that is not a block, drawn as a numbered dot on top of the image: an entity, an NPC, a text display.
 * The caption lists each number with its label, so the image itself needs no text.
 *
 * @param x     world x of the entity's feet
 * @param y     world y of the entity's feet
 * @param z     world z of the entity's feet
 * @param label short text for the caption, such as {@code villager "Shopkeeper"}
 */
public record Marker(double x, double y, double z, String label) {
}
