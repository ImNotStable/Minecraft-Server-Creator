package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackAssetsTest {
    @TempDir
    Path root;

    private PackSource pack() {
        return new PackSource(root);
    }

    private PackAssets.Plan write(PackAssets.Type type, String name, String json) throws IOException {
        return write(type, ResourceId.parse(name), new PackAssets.Content(PackAssets.parseObject(json), null, null, null));
    }

    private PackAssets.Plan write(PackAssets.Type type, ResourceId name, PackAssets.Content content) throws IOException {
        PackAssets.Plan plan = PackAssets.plan(pack(), type, name, content);
        PackAssets.write(pack(), plan);
        return plan;
    }

    private JsonObject read(String path) throws IOException {
        return JsonParser.parseString(Files.readString(root.resolve(path))).getAsJsonObject();
    }

    /** A minimal Ogg Vorbis identification page: what the lint reads, nothing more. */
    static byte[] ogg(int channels) {
        byte[] b = new byte[27 + 1 + 30];
        System.arraycopy("OggS".getBytes(StandardCharsets.ISO_8859_1), 0, b, 0, 4);
        b[26] = 1;
        b[27] = 30;
        b[28] = 1;
        System.arraycopy("vorbis".getBytes(StandardCharsets.ISO_8859_1), 0, b, 29, 6);
        b[28 + 11] = (byte) channels;
        return b;
    }

    @Test
    void glyphsGetTheNextFreePrivateUseChar() throws IOException {
        PackAssets.Plan first = write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/coin.png\"}");
        assertThat(first.note()).contains("\\uE000").contains("\"font\":\"msc:icons\"");
        PackAssets.Plan second = write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/gem.png\",\"height\":9}");
        assertThat(second.note()).contains("\\uE001");
        JsonObject font = read("assets/msc/font/icons.json");
        assertThat(font.getAsJsonArray("providers")).hasSize(2);
        JsonObject gem = font.getAsJsonArray("providers").get(1).getAsJsonObject();
        assertThat(gem.get("type").getAsString()).isEqualTo("bitmap");
        assertThat(gem.get("ascent").getAsInt()).isEqualTo(7);
        assertThat(gem.get("height").getAsInt()).isEqualTo(9);
        assertThat(gem.getAsJsonArray("chars").get(0).getAsString()).isEqualTo("\uE001");
    }

    @Test
    void aRepeatedCallAddsNothingTwice() throws IOException {
        write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/coin.png\"}");
        PackAssets.Plan again = write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/coin.png\"}");
        assertThat(again.note()).contains("\\uE000");
        write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/a.png\",\"chars\":[\"A\"]}");
        write(PackAssets.Type.GLYPH, "msc:icons", "{\"file\":\"msc:font/a.png\",\"chars\":[\"A\"]}");
        assertThat(read("assets/msc/font/icons.json").getAsJsonArray("providers")).hasSize(2);
        write(PackAssets.Type.SOUND, "msc:boss.roar", "{\"sounds\":[\"msc:roar\"]}");
        write(PackAssets.Type.SOUND, "msc:boss.roar", "{\"sounds\":[\"msc:roar\"]}");
        assertThat(read("assets/msc/sounds.json").getAsJsonObject("boss.roar").getAsJsonArray("sounds").toString())
                .isEqualTo("[\"msc:roar\"]");
    }

    @Test
    void langKeysMergeAndAtlasSourcesAppendOnce() throws IOException {
        write(PackAssets.Type.LANG, "msc:en_us", "{\"a\":\"A\",\"b\":\"B\"}");
        write(PackAssets.Type.LANG, "msc:en_us", "{\"b\":\"Bee\",\"c\":\"C\"}");
        assertThat(read("assets/msc/lang/en_us.json").toString()).isEqualTo("{\"a\":\"A\",\"b\":\"Bee\",\"c\":\"C\"}");
        String source = "{\"sources\":[{\"type\":\"directory\",\"source\":\"extra\",\"prefix\":\"extra/\"}]}";
        write(PackAssets.Type.ATLAS, "minecraft:blocks", source);
        write(PackAssets.Type.ATLAS, "minecraft:blocks", source);
        assertThat(read("assets/minecraft/atlases/blocks.json").getAsJsonArray("sources")).hasSize(1);
        assertThatThrownBy(() -> write(PackAssets.Type.LANG, "msc:English", "{}")).hasMessageContaining("bad resource name");
    }

    @Test
    void soundsCopyEachFileOnceAndAddVariants() throws IOException {
        ResourceId roar = ResourceId.parse("msc:boss.roar");
        write(PackAssets.Type.SOUND, roar, new PackAssets.Content(
                PackAssets.parseObject("{\"subtitle\":\"subtitles.msc.roar\"}"), null, ogg(1), "roar.ogg"));
        write(PackAssets.Type.SOUND, roar, new PackAssets.Content(null, null, ogg(1), "roar.ogg"));
        PackAssets.Plan other = write(PackAssets.Type.SOUND, roar, new PackAssets.Content(null, null, ogg(2), "roar2.ogg"));
        assertThat(other.note()).contains("sounds/boss/roar_2.ogg").contains("/playsound msc:boss.roar");
        JsonObject event = read("assets/msc/sounds.json").getAsJsonObject("boss.roar");
        assertThat(event.get("subtitle").getAsString()).isEqualTo("subtitles.msc.roar");
        assertThat(event.getAsJsonArray("sounds").toString()).isEqualTo("[\"msc:boss/roar\",\"msc:boss/roar_2\"]");
        assertThatThrownBy(() -> write(PackAssets.Type.SOUND, roar, new PackAssets.Content(null, null, ogg(1), "r.wav")))
                .hasMessageContaining(".ogg");
    }

    @Test
    void mcmetaSectionsSurviveARedrawAndAnAnimation() throws IOException {
        ResourceId button = ResourceId.parse("msc:gui/sprites/button");
        pack().writeTexture(button, Texture.square(16));
        assertThatThrownBy(() -> write(PackAssets.Type.MCMETA, "msc:gui/sprites/none", "{\"gui\":{}}"))
                .hasMessageContaining("draw it first");
        write(PackAssets.Type.MCMETA, "msc:gui/sprites/button",
                "{\"gui\":{\"scaling\":{\"type\":\"nine_slice\",\"width\":16,\"height\":16,\"border\":4}}}");
        pack().writeAnimation(button, Animation.fromStrip(new Texture(16, 32), 2, false));
        Path meta = root.resolve("assets/msc/textures/gui/sprites/button.png.mcmeta");
        assertThat(read(root.relativize(meta).toString()).keySet()).containsExactly("gui", "animation");
        pack().writeTexture(button, Texture.square(16));
        assertThat(read(root.relativize(meta).toString()).keySet()).containsExactly("gui");
    }

    @Test
    void inputsAndNamesAreCheckedBeforeAnythingIsPlanned() {
        assertThatThrownBy(() -> PackAssets.plan(pack(), PackAssets.Type.SHADER, ResourceId.parseExact("minecraft:core/x.fsh"),
                new PackAssets.Content(new JsonObject(), null, null, null))).hasMessage("shader takes text, not json");
        assertThatThrownBy(() -> PackAssets.plan(pack(), PackAssets.Type.FONT, ResourceId.parse("msc:x"),
                new PackAssets.Content(null, null, null, null))).hasMessage("font needs json");
        assertThatThrownBy(() -> PackAssets.plan(pack(), PackAssets.Type.SHADER, ResourceId.parseExact("minecraft:core/x"),
                new PackAssets.Content(null, "x", null, null))).hasMessageContaining(".vsh, .fsh or .glsl");
        assertThatThrownBy(() -> PackAssets.plan(pack(), PackAssets.Type.FILE, ResourceId.parseExact("msc:font/x.ttf"),
                new PackAssets.Content(null, null, new byte[4], "x.otf"))).hasMessageContaining("is a .otf file");
        assertThatThrownBy(() -> PackAssets.plan(pack(), PackAssets.Type.FILE, ResourceId.parseExact("msc:models/x.json"),
                new PackAssets.Content(null, null, new byte[4], "x.json"))).hasMessageContaining("file copies a font");
        assertThatThrownBy(() -> PackAssets.parseObject("[1]")).hasMessage("json must be a JSON object { ... }");
        assertThatThrownBy(() -> PackAssets.parseObject("{nope")).hasMessageStartingWith("json is not valid JSON");
        assertThat(PackAssets.Type.parse("Post_Effect")).isEqualTo(PackAssets.Type.POST_EFFECT);
        assertThat(ResourceId.parseExact("minecraft:credits.json").path()).isEqualTo("credits.json");
    }
}
