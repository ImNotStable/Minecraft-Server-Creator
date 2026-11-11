package io.github.imnotstable.msc.renderer;

/**
 * A rendered image.
 *
 * @param png             the image, PNG-encoded
 * @param width           image width in pixels
 * @param height          image height in pixels
 * @param caption         one or two lines for the AI: what the view shows, how pixels map to
 *                        coordinates, and which blocks are visible
 * @param estimatedTokens rough vision cost of the image (width x height / 750)
 */
public record RenderResult(byte[] png, int width, int height, String caption, int estimatedTokens) {
}
