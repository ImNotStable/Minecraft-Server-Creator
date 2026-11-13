package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class PackLintTest {
    private static final String META = "{\"pack\":{\"pack_format\":97,\"description\":\"x\"}}";

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> lint(Map<String, byte[]> files) {
        return PackLint.check(files).findings().stream().map(PackLint.Finding::toString).toList();
    }

    @Test
    void missingMetaIsAnError() {
        assertThat(lint(Map.of())).containsExactly("ERROR pack.mcmeta: missing; the game will not load the pack");
        assertThat(lint(Map.of("pack.mcmeta", text("{\"pack\":{}}"))))
                .contains("ERROR pack.mcmeta: no pack_format or min_format", "WARNING pack.mcmeta: no description");
        assertThat(lint(Map.of("pack.mcmeta", text("{nope")))).singleElement().asString().startsWith("ERROR pack.mcmeta: invalid JSON");
    }

    @Test
    void findsBrokenReferences() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("pack.mcmeta", text(META));
        files.put("assets/msc/models/item/ruby.json",
                text("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"msc:item/rubyy\"}}"));
        files.put("assets/msc/models/block/odd.json", text("{\"parent\":\"msc:block/base\"}"));
        files.put("assets/msc/items/ruby.json",
                text("{\"model\":{\"type\":\"minecraft:select\",\"cases\":[{\"model\":{\"type\":\"minecraft:model\",\"model\":\"msc:item/gone\"}}],"
                        + "\"fallback\":{\"type\":\"minecraft:model\",\"model\":\"msc:item/ruby\"}}}"));
        files.put("assets/msc/textures/item/ruby.png", Shapes.draw("gem", Ramp.from(0xFFD4263A, 5), null).toPng());
        files.put("assets/minecraft/models/item/stick.json",
                text("{\"parent\":\"item/generated\",\"textures\":{\"layer0\":\"item/stick\"}}"));
        assertThat(lint(files)).containsExactly(
                "ERROR assets/msc/items/ruby.json: model msc:item/gone is not in the pack",
                "ERROR assets/msc/models/block/odd.json: parent msc:block/base is not in the pack",
                "ERROR assets/msc/models/item/ruby.json: texture layer0 = msc:item/rubyy is not in the pack",
                "INFO assets/msc/textures/item/ruby.png: not used by any model in the pack");
    }

    @Test
    void nonStringValuesAreFindingsNotCrashes() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("pack.mcmeta", text(META));
        files.put("assets/msc/models/item/a.json", text("{\"parent\":null}"));
        files.put("assets/msc/models/item/b.json",
                text("{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":{\"a\":1}}}"));
        files.put("assets/msc/items/c.json", text("{\"model\":{\"type\":[\"a\",\"b\"]}}"));
        assertThat(lint(files)).containsExactly(
                "ERROR assets/msc/items/c.json: type must be a string",
                "ERROR assets/msc/models/item/a.json: parent must be a string",
                "ERROR assets/msc/models/item/b.json: texture layer0 must be a string");
    }

    @Test
    void readsTexturesWiderThanTheCreationLimit() {
        BufferedImage wide = new BufferedImage(1024, 256, BufferedImage.TYPE_INT_ARGB);
        wide.setRGB(0, 0, 0xFF808080);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            ImageIO.write(wide, "png", png);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, byte[]> files = Map.of("pack.mcmeta", text(META),
                "assets/minecraft/textures/gui/title/minecraft.png", png.toByteArray());
        assertThat(PackLint.check(files).ok()).isTrue();
    }

    @Test
    void findsTextureProblems() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("pack.mcmeta", text(META));
        files.put("assets/minecraft/textures/block/strip.png", new Texture(16, 48).toPng());
        Texture full = Texture.square(16);
        full.fill(0xFF808080);
        files.put("assets/minecraft/textures/item/full.png", full.toPng());
        Texture glass = Texture.square(32);
        glass.fill(0x80FFFFFF);
        files.put("assets/minecraft/textures/block/glass.png", glass.toPng());
        files.put("assets/minecraft/textures/block/stone.png", Patterns.noise(16, Ramp.from(0xFF7F7F7F, 4), 1).toPng());
        files.put("assets/minecraft/textures/block/dirt.png", Patterns.noise(16, Ramp.from(0xFF7F5F3F, 4), 1).toPng());
        files.put("assets/minecraft/textures/block/Bad.png", full.toPng());
        assertThat(lint(files)).containsExactly(
                "ERROR assets/minecraft/textures/block/Bad.png: file names must be lowercase a-z, 0-9, _ . - only; the game ignores this file",
                "ERROR assets/minecraft/textures/block/strip.png: 3 stacked frames but no .png.mcmeta; it will show as missing",
                "WARNING assets/minecraft/textures/block/glass.png: 32 pixels wide; most block/ textures in this pack are 16",
                "WARNING assets/minecraft/textures/block/glass.png: semi-transparent pixels; most blocks render them fully opaque or not at all",
                "WARNING assets/minecraft/textures/block/strip.png: fully transparent",
                "WARNING assets/minecraft/textures/item/full.png: item texture has no transparent background");
    }

    @Test
    void reportTextIsCapped() {
        Map<String, byte[]> files = new HashMap<>();
        files.put("pack.mcmeta", text(META));
        for (int i = 0; i < 5; i++) {
            files.put("assets/msc/textures/item/t" + i + ".png", Shapes.draw("orb", Ramp.from(0xFF3060E0, 4), null).toPng());
        }
        String report = PackLint.check(files).text(2);
        assertThat(report).startsWith("pack check: 6 files, 0 errors, 0 warnings, 5 notes").endsWith("... 3 more");
    }
}
