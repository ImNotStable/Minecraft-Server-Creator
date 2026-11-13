package io.github.imnotstable.msc.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackTexturesTest {
    private static byte[] png(int rgb) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 256; i++) {
            image.setRGB(i % 16, i / 16, 0xff000000 | rgb);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void readsFoldersAndZipsAndEarlierRootsWin(@TempDir Path dir) throws IOException {
        Path folder = dir.resolve("pack");
        Path file = folder.resolve("assets/minecraft/textures/block/stone.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png(0x00ff00));
        Path zip = dir.resolve("client.jar");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String name : new String[] {"stone", "dirt"}) {
                zos.putNextEntry(new ZipEntry("assets/minecraft/textures/block/" + name + ".png"));
                zos.write(png(0x0000ff));
                zos.closeEntry();
            }
        }
        Files.writeString(folder.resolve("assets/minecraft/textures/block/broken.png"), "not a png");

        PackTextures textures = new PackTextures(List.of(folder, zip, dir.resolve("missing.zip")));
        assertThat(textures.texture("minecraft", "block/stone").orElseThrow().getRGB(0, 0)).isEqualTo(0xff00ff00);
        assertThat(textures.texture("minecraft", "block/dirt").orElseThrow().getRGB(0, 0)).isEqualTo(0xff0000ff);
        assertThat(textures.texture("minecraft", "block/sand")).isEmpty();
        assertThat(textures.texture("minecraft", "block/broken")).isEmpty();
        assertThat(textures.texture("minecraft", "../../../secret")).isEmpty();

        // End to end: stone is drawn green from the folder pack, stairs made of it too.
        Scenes s = new Scenes(0, 0, 0, 1, 0, 0).set(0, 0, 0, "stone").set(1, 0, 0, "stone_slab[type=double]");
        RenderResult result = new WorldRenderer(textures).render(s.grid(), View.parse("top"),
                new RenderOptions(64, false, false, false, false));
        BufferedImage image = Scenes.decode(result);
        assertThat(Scenes.green(image.getRGB(16, 16))).isGreaterThan(150);
        assertThat(Scenes.green(image.getRGB(48, 16))).isGreaterThan(150);
    }

    @Test
    void oneRendererSeesTexturesWrittenAfterItsFirstRender(@TempDir Path dir) throws IOException {
        WorldRenderer renderer = new WorldRenderer(new PackTextures(List.of(dir)));
        Scenes s = new Scenes(0, 0, 0, 1, 0, 0).fill(0, 0, 0, 1, 0, 0, "myplugin:ruby_block");
        RenderOptions options = new RenderOptions(64, false, false, false, false);
        assertThat(renderer.render(s.grid(), View.parse("top"), options).caption()).contains("No colour known");

        Path file = dir.resolve("assets/myplugin/textures/block/ruby_block.png");
        Files.createDirectories(file.getParent());
        Files.write(file, png(0x00ff00));
        RenderResult added = renderer.render(s.grid(), View.parse("top"), options);
        assertThat(added.caption()).doesNotContain("No colour known");
        assertThat(Scenes.green(Scenes.decode(added).getRGB(16, 16))).isGreaterThan(150);

        Files.write(file, png(0x0000ff));
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 5000));
        RenderResult changed = renderer.render(s.grid(), View.parse("top"), options);
        assertThat(Scenes.blue(Scenes.decode(changed).getRGB(16, 16))).isGreaterThan(150);
    }
}
