package io.github.imnotstable.msc.renderer;

/**
 * A 3x5 pixel font: digits, capital letters and common punctuation. Lower case is drawn as capitals and any other
 * character as {@code ?}. Avoids AWT fonts, which headless servers may lack.
 */
final class PixelFont {
    static final int WIDTH = 3;
    static final int HEIGHT = 5;
    /** Rows top to bottom, three bits each, most significant bit on the left; indexed by ASCII code. */
    private static final int[][] GLYPHS = new int[128][];

    static {
        String digits = "75557 26227 71747 71717 55711 74717 74757 71111 75757 75717";
        String letters = "25755 65656 34443 65556 74647 74644 34553 55755 72227 11152 55655 44447 57755 65555 25552 "
                + "65644 25563 65655 34216 72222 55557 55552 55775 55255 55222 71247";
        String[] d = digits.split(" ");
        for (int i = 0; i < 10; i++) {
            GLYPHS['0' + i] = rows(d[i]);
        }
        String[] l = letters.split(" ");
        for (int i = 0; i < 26; i++) {
            GLYPHS['A' + i] = rows(l[i]);
        }
        String punctuation = " 00000 .00002 ,00024 :02020 ;02024 !22202 ?61202 '22000 \"55000 -00700 +02720 /11244 "
                + "(12221 )42224 [32223 ]62226 #57575 %51245 *05250 =07070 _00007 <12421 >42124 &25253 $36236 |22222 "
                + "@75747";
        for (int i = 0; i < punctuation.length(); i += 7) {
            GLYPHS[punctuation.charAt(i)] = rows(punctuation.substring(i + 1, i + 6));
        }
    }

    private PixelFont() {
    }

    private static int[] rows(String code) {
        int[] rows = new int[HEIGHT];
        for (int i = 0; i < HEIGHT; i++) {
            rows[i] = code.charAt(i) - '0';
        }
        return rows;
    }

    /** True when {@code ch} has its own glyph (after upper-casing). */
    static boolean known(char ch) {
        char up = Character.toUpperCase(ch);
        return up < 128 && GLYPHS[up] != null;
    }

    static int width(String text, int scale) {
        return text.isEmpty() ? 0 : (text.length() * (WIDTH + 1) - 1) * scale;
    }

    /** Draws text with its top-left corner at (x, y); pixels outside the image are skipped. */
    static void draw(int[] pixels, int imageWidth, int imageHeight, int x, int y, String text, int scale, int rgb) {
        for (int c = 0; c < text.length(); c++) {
            char ch = Character.toUpperCase(text.charAt(c));
            int[] rows = known(ch) ? GLYPHS[ch] : GLYPHS['?'];
            for (int row = 0; row < HEIGHT; row++) {
                for (int col = 0; col < WIDTH; col++) {
                    if ((rows[row] >> (WIDTH - 1 - col) & 1) != 0) {
                        fill(pixels, imageWidth, imageHeight, x + col * scale, y + row * scale, scale, scale, rgb);
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
