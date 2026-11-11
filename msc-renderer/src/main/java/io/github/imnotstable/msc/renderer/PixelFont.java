package io.github.imnotstable.msc.renderer;

/** A 3x5 pixel font for ruler labels: digits and minus. Avoids AWT fonts, which headless servers may lack. */
final class PixelFont {
    static final int WIDTH = 3;
    static final int HEIGHT = 5;
    /** Rows top to bottom, three bits each, most significant bit on the left. */
    private static final int[][] DIGITS = {
            {7, 5, 5, 5, 7}, {2, 6, 2, 2, 7}, {7, 1, 7, 4, 7}, {7, 1, 7, 1, 7}, {5, 5, 7, 1, 1},
            {7, 4, 7, 1, 7}, {7, 4, 7, 5, 7}, {7, 1, 1, 1, 1}, {7, 5, 7, 5, 7}, {7, 5, 7, 1, 7}};
    private static final int[] MINUS = {0, 0, 7, 0, 0};

    private PixelFont() {
    }

    static int width(String text, int scale) {
        return text.isEmpty() ? 0 : (text.length() * (WIDTH + 1) - 1) * scale;
    }

    /** Draws text with its top-left corner at (x, y); pixels outside the image are skipped. */
    static void draw(int[] pixels, int imageWidth, int imageHeight, int x, int y, String text, int scale, int rgb) {
        for (int c = 0; c < text.length(); c++) {
            char ch = text.charAt(c);
            int[] rows = ch == '-' ? MINUS : ch >= '0' && ch <= '9' ? DIGITS[ch - '0'] : null;
            if (rows != null) {
                for (int row = 0; row < HEIGHT; row++) {
                    for (int col = 0; col < WIDTH; col++) {
                        if ((rows[row] >> (WIDTH - 1 - col) & 1) != 0) {
                            fill(pixels, imageWidth, imageHeight, x + col * scale, y + row * scale, scale, scale, rgb);
                        }
                    }
                }
            }
            x += (WIDTH + 1) * scale;
        }
    }

    static void fill(int[] pixels, int imageWidth, int imageHeight, int x, int y, int w, int h, int rgb) {
        for (int py = Math.max(0, y); py < Math.min(imageHeight, y + h); py++) {
            for (int px = Math.max(0, x); px < Math.min(imageWidth, x + w); px++) {
                pixels[py * imageWidth + px] = rgb;
            }
        }
    }
}
