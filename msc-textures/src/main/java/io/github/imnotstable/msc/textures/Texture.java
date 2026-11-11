package io.github.imnotstable.msc.textures;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.imageio.ImageIO;

/** A mutable ARGB pixel grid. Small and plain on purpose: textures are 16 to 64 pixels wide. */
public final class Texture {
    public static final int MAX_SIZE = 512;

    private final int width;
    private final int height;
    private final int[] pixels;

    public Texture(int width, int height) {
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE * 64) {
            throw new TextureException("texture size " + width + "x" + height + " is out of range");
        }
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
    }

    public static Texture square(int size) {
        return new Texture(size, size);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }

    /** Pixel with coordinates wrapped, for tiling patterns. */
    public int getWrapped(int x, int y) {
        return get(Math.floorMod(x, width), Math.floorMod(y, height));
    }

    public void set(int x, int y, int argb) {
        pixels[y * width + x] = argb;
    }

    public boolean inside(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    public void fill(int argb) {
        Arrays.fill(pixels, argb);
    }

    public Texture copy() {
        Texture t = new Texture(width, height);
        System.arraycopy(pixels, 0, t.pixels, 0, pixels.length);
        return t;
    }

    /** Draw {@code other} on top at (x, y); transparent pixels of {@code other} are skipped. */
    public void draw(Texture other, int x, int y) {
        for (int j = 0; j < other.height; j++) {
            for (int i = 0; i < other.width; i++) {
                int c = other.get(i, j);
                if (Colors.opaque(c) && inside(x + i, y + j)) {
                    set(x + i, y + j, c);
                }
            }
        }
    }

    /** Distinct opaque colours in first-seen order. */
    public Set<Integer> colors() {
        Set<Integer> out = new LinkedHashSet<>();
        for (int c : pixels) {
            if (Colors.opaque(c)) {
                out.add(c);
            }
        }
        return out;
    }

    public int opaqueCount() {
        int n = 0;
        for (int c : pixels) {
            if (Colors.opaque(c)) {
                n++;
            }
        }
        return n;
    }

    public static Texture of(BufferedImage image) {
        Texture t = new Texture(image.getWidth(), image.getHeight());
        image.getRGB(0, 0, t.width, t.height, t.pixels, 0, t.width);
        return t;
    }

    public BufferedImage toImage() {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    public static Texture readPng(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new TextureException("not a PNG image");
            }
            return of(image);
        } catch (IOException e) {
            throw new TextureException("not a readable PNG: " + e.getMessage());
        }
    }

    public byte[] toPng() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(toImage(), "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Texture t && t.width == width && Arrays.equals(t.pixels, pixels);
    }

    @Override
    public int hashCode() {
        return 31 * width + Arrays.hashCode(pixels);
    }

    @Override
    public String toString() {
        return "Texture[" + width + "x" + height + "]";
    }
}
