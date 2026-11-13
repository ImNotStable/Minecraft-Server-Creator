package io.github.imnotstable.msc.textures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackTest {
    private static final Ramp RUBY = Ramp.from(0xFFD4263A, 5);

    @TempDir
    Path dir;

    @Test
    void rubyPackMeetsTheBenchZipCheck() throws IOException {
        PackSource pack = new PackSource(dir.resolve("src"));
        pack.writeTexture(ResourceId.parse("minecraft:item/diamond"), Shapes.draw("gem", RUBY, null));
        pack.writePackMeta(97, "Rubies instead of diamonds");
        PackSource.Zipped zipped = pack.zip(dir.resolve("ruby-pack.zip"));
        try (ZipFile zip = new ZipFile(zipped.zip().toFile())) {
            String meta = new String(zip.getInputStream(zip.getEntry("pack.mcmeta")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(meta).containsPattern("\"(pack_format|min_format)\"\\s*:").containsPattern("\"description\"\\s*:");
            byte[] png = zip.getInputStream(zip.getEntry("assets/minecraft/textures/item/diamond.png")).readAllBytes();
            assertThat(BenchRules.rubyProblems(Texture.readPng(png))).isEmpty();
        }
        try (InputStream in = Files.newInputStream(zipped.zip())) {
            PackLint.Report report = PackLint.checkZip(in);
            assertThat(report.findings()).isEmpty();
            assertThat(report.text(10)).isEqualTo("pack check: 2 files, 0 errors, 0 warnings, 0 notes");
        }
    }

    @Test
    void zipIsDeterministic() throws IOException {
        PackSource pack = new PackSource(dir.resolve("src"));
        pack.writeTexture(ResourceId.parse("block/ruby_ore"), Patterns.ore(16, Ramp.from(0xFF7F7F7F, 4), RUBY, 1));
        pack.writePackMeta(97, "x");
        String a = pack.zip(dir.resolve("a.zip")).sha1();
        String b = pack.zip(dir.resolve("b.zip")).sha1();
        assertThat(a).isEqualTo(b).hasSize(40);
    }

    @Test
    void modelsAndItemDefinitionsCheckClean() throws IOException {
        PackSource pack = new PackSource(dir);
        ResourceId log = ResourceId.parse("block/ruby_log");
        ResourceId top = ResourceId.parse("block/ruby_log_top");
        pack.writeTexture(log, Patterns.planks(16, Ramp.from(0xFF8A5A2B, 4), 1));
        pack.writeTexture(top, Patterns.noise(16, Ramp.from(0xFF8A5A2B, 4), 1));
        String block = pack.writeModel(log, Models.Kind.CUBE_COLUMN, Map.of("side", log, "end", top), true);
        assertThat(block).isEqualTo("msc:ruby_log");
        ResourceId gem = ResourceId.parse("item/ruby");
        pack.writeTexture(gem, Shapes.draw("gem", RUBY, null));
        assertThat(pack.writeModel(gem, Models.Kind.ITEM, Map.of("layer0", gem), false)).isEqualTo("msc:ruby");
        assertThat(Files.readString(pack.itemPath(ResourceId.parse("ruby"))))
                .contains("\"model\":\"msc:item/ruby\"");
        pack.writePackMeta(97, "x");
        assertThat(PackLint.checkDirectory(dir).findings()).isEmpty();
    }

    @Test
    void textureModelsKeepSubFoldersAndNeverRepointAnItem() throws IOException {
        PackSource pack = new PackSource(dir);
        ResourceId red = ResourceId.parse("item/red/gem");
        ResourceId blue = ResourceId.parse("item/blue/gem");
        assertThat(pack.writeTextureModel(red, Models.Kind.ITEM)).isEqualTo("msc:red/gem");
        assertThat(pack.writeTextureModel(blue, Models.Kind.ITEM)).isEqualTo("msc:blue/gem");
        assertThat(pack.modelPath(red)).content().contains("\"layer0\":\"msc:item/red/gem\"");
        assertThat(pack.itemPath(ResourceId.parse("blue/gem"))).content().contains("\"model\":\"msc:item/blue/gem\"");
        assertThat(pack.writeTextureModel(ResourceId.parse("item/ruby"), Models.Kind.ITEM)).isEqualTo("msc:ruby");
        assertThat(pack.writeTextureModel(ResourceId.parse("item/ruby"), Models.Kind.HANDHELD)).isEqualTo("msc:ruby");
        assertThatThrownBy(() -> pack.writeTextureModel(ResourceId.parse("block/ruby"), Models.Kind.CUBE_ALL))
                .hasMessage("item msc:ruby already shows model msc:item/ruby; give this one another name");
        assertThat(pack.modelPath(ResourceId.parse("block/ruby"))).doesNotExist();
    }

    @Test
    void textureModelNeverRepointsAModelOfAnotherFolder() throws IOException {
        PackSource pack = new PackSource(dir);
        ResourceId model = ResourceId.parse("item/ruby");
        assertThat(pack.writeTextureModel(ResourceId.parse("block/ruby"), Models.Kind.ITEM)).isEqualTo("msc:ruby");
        assertThat(pack.writeTextureModel(ResourceId.parse("block/ruby"), Models.Kind.HANDHELD)).isEqualTo("msc:ruby");
        assertThatThrownBy(() -> pack.writeTextureModel(ResourceId.parse("item/ruby"), Models.Kind.ITEM))
                .hasMessage("model msc:item/ruby already shows texture msc:block/ruby; give this one another name");
        assertThat(pack.modelPath(model)).content().contains("\"layer0\":\"msc:block/ruby\"");
    }

    @Test
    void animationWritesMcmetaAndStillTextureRemovesIt() throws IOException {
        PackSource pack = new PackSource(dir);
        ResourceId id = ResourceId.parse("block/glow");
        Texture base = Patterns.ore(16, Ramp.from(0xFF7F7F7F, 4), RUBY, 2);
        Path png = pack.writeAnimation(id, Animation.pulse(base, RUBY, 4, 4));
        assertThat(png.resolveSibling("glow.png.mcmeta")).exists();
        pack.writeTexture(id, base);
        assertThat(png.resolveSibling("glow.png.mcmeta")).doesNotExist();
    }

    @Test
    void modelKindsNeedTheirSlots() {
        ResourceId t = ResourceId.parse("block/a");
        assertThat(Models.model(Models.Kind.CUBE_ALL, Map.of("x", t)).toString()).contains("\"all\":\"msc:block/a\"");
        assertThatThrownBy(() -> Models.model(Models.Kind.ORIENTABLE, Map.of("front", t, "top", t)))
                .hasMessageContaining("missing side");
        assertThatThrownBy(() -> Models.model(Models.Kind.CUBE_ALL, Map.of("all", t, "top", t)))
                .hasMessageContaining("no texture slot \"top\"");
        assertThat(Models.Kind.parse("Cube_Column")).isEqualTo(Models.Kind.CUBE_COLUMN);
        assertThatThrownBy(() -> Models.Kind.parse("slab")).hasMessageContaining("cube_all");
    }

    @Test
    void resourceIdsAreValidated() {
        assertThat(ResourceId.parse("item/ruby.png")).isEqualTo(new ResourceId("msc", "item/ruby"));
        assertThat(ResourceId.parse("minecraft:item/diamond").name()).isEqualTo("diamond");
        assertThatThrownBy(() -> ResourceId.parse("Item/Ruby")).hasMessageContaining("lowercase");
        assertThatThrownBy(() -> ResourceId.parse("../x")).isInstanceOf(TextureException.class);
    }
}
