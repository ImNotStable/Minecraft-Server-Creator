package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureToolsTest {
    @TempDir
    Path workspace;
    private CoreHarness h;

    @BeforeEach
    void start() throws Exception {
        h = new CoreHarness(workspace).withAgent();
    }

    @AfterEach
    void stop() throws Exception {
        h.close();
    }

    private static List<String> ruby() {
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < 16; y++) {
            rows.add(y == 0 ? "r...........g..d" : "." + "r".repeat(14) + ".");
        }
        return rows;
    }

    @Test
    void aPngPathWritesALooseFileOutsideThePack() throws Exception {
        String gem = h.ok("texture", "name", "textures/ruby.png", "kind", "gem", "color", "#d4263a");
        assertThat(gem).startsWith("wrote " + workspace.resolve("textures/ruby.png") + ": 16x16, ").contains("palette:");
        assertThat(ImageIO.read(workspace.resolve("textures/ruby.png").toFile()).getWidth()).isEqualTo(16);
        assertThat(workspace.resolve(".msc/pack/assets")).doesNotExist();
        assertThat(h.ok("texture", "name", "out/Drawn.PNG", "palette", List.of("r=#d4263a"), "rows", List.of("r.", ".r")))
                .startsWith("wrote " + workspace.resolve("out/Drawn.PNG") + ": 2x2");
        assertThat(h.error("texture", "name", "a.png", "kind", "gem", "color", "#d4263a", "animate", "pulse"))
                .contains("pack texture name");
    }

    @Test
    void createWritesPngModelAndItemDefinition() throws Exception {
        String result = h.ok("texture", "name", "item/ruby",
                "palette", List.of("r=#d4263a d=#701", "g=#00ff0080"), "rows", ruby());
        assertThat(result).isEqualTo("""
                wrote msc:item/ruby 16x16; item_model msc:ruby
                next: pack""");
        Path assets = workspace.resolve(".msc/pack/assets/msc");
        BufferedImage image = ImageIO.read(assets.resolve("textures/item/ruby.png").toFile());
        assertThat(image.getRGB(0, 0)).isEqualTo(0xFFD4263A);
        assertThat(image.getRGB(1, 0) >>> 24).isZero();
        assertThat(image.getRGB(12, 0)).isEqualTo(0x8000FF00);
        assertThat(image.getRGB(15, 0)).isEqualTo(0xFF770011);
        assertThat(Files.readString(assets.resolve("models/item/ruby.json")))
                .contains("\"parent\":\"minecraft:item/generated\"").contains("\"layer0\":\"msc:item/ruby\"");
        assertThat(Files.readString(assets.resolve("items/ruby.json"))).contains("\"model\":\"msc:item/ruby\"");
    }

    @Test
    void viewReadsATextureBackAsText() {
        h.ok("texture", "name", "myplugin:block/ruby_ore", "model", "block",
                "palette", List.of("s=#7f7f7f r=#d4263a"), "rows", List.of("ss", "r."));
        assertThat(h.ok("texture_view", "name", "myplugin:block/ruby_ore")).isEqualTo("""
                2x2
                palette: .=transparent a=#7f7f7f b=#d4263a
                aa
                b.""");
        assertThat(h.error("texture_view", "name", "item/missing")).contains("no texture msc:item/missing");
    }

    @Test
    void mistakesAreReportedPrecisely() {
        assertThat(h.error("pack", "apply", false)).contains("the pack is empty");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=#000000"), "rows", List.of("ab")))
                .contains("symbol 'b' in row 1 is not in the palette");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=red"), "rows", List.of("a")))
                .contains("bad color \"red\"");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=#000"), "rows", List.of("aa", "a")))
                .contains("row 2 has 1 pixels, expected 2");
        assertThat(h.error("texture", "name", "Item/Ruby", "palette", List.of("a=#000"), "rows", List.of("a")))
                .contains("bad texture name");
        assertThat(h.ok("texture", "name", "item/odd", "palette", List.of("a=#000"), "rows", List.of("aaa", "aaa", "aaa")))
                .contains("warning: width 3 is not a power of two");
    }

    @Test
    void generateWritesAShadedTextureThatTheOtherToolsRead() throws Exception {
        String gem = h.ok("texture", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
        assertThat(gem).startsWith("wrote msc:item/ruby: 16x16, ").contains("main hue 0 (red)")
                .contains("; item_model msc:ruby\n16x16\npalette: .=transparent").endsWith("pack");
        assertThat(h.ok("texture_view", "name", "item/ruby")).startsWith("16x16\npalette:");
        String ore = h.ok("texture", "name", "block/ruby_ore", "kind", "ore", "color", "#7f7f7f",
                "accent", "#d4263a", "size", 32, "seed", 7, "contrast", 0.5);
        assertThat(ore).startsWith("wrote msc:block/ruby_ore: 32x32, ").contains("item_model msc:ruby_ore");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/block/ruby_ore.json")).content().contains("cube_all");
        assertThat(h.error("texture", "name", "item/x", "kind", "star", "color", "#fff"))
                .isEqualTo("error: kind must be noise, ore, planks, bricks, gem, ingot, orb, sword, pickaxe or food");
        assertThat(h.error("texture", "name", "item/x", "kind", "gem", "color", "#fff", "contrast", 3))
                .contains("contrast must be 0.2 to 1.5");
        assertThat(h.error("texture", "name", "item/x", "kind", "gem")).contains("kind and copy need a color");
    }

    @Test
    void recolorKeepsTheShadingOnNewColors() {
        h.ok("texture", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
        assertThat(h.ok("texture", "copy", "item/ruby", "name", "item/sapphire", "color", "#2050e0"))
                .startsWith("wrote msc:item/sapphire: 16x16, ").contains("(blue)").endsWith("next: pack");
        assertThat(h.error("texture", "copy", "item/nope", "name", "item/x", "color", "#fff"))
                .isEqualTo("error: no texture msc:item/nope in the pack (vanilla textures are not in it)");
    }

    @Test
    void animateStacksFramesAndWritesTheTiming() throws Exception {
        h.ok("texture", "name", "block/lava", "kind", "noise", "color", "#e05010");
        assertThat(h.ok("texture", "name", "block/lava", "animate", "scroll", "frames", 4, "frametime", 3))
                .isEqualTo("animated msc:block/lava: 4 frames, 3 ticks each\nnext: pack");
        Path png = workspace.resolve(".msc/pack/assets/msc/textures/block/lava.png");
        assertThat(ImageIO.read(png.toFile()).getHeight()).isEqualTo(64);
        assertThat(png.resolveSibling("lava.png.mcmeta")).content().contains("\"frametime\":3");
        // Animating again starts from frame 1; scroll's default is one full loop.
        assertThat(h.ok("texture", "name", "block/lava", "animate", "scroll 0,2")).contains("8 frames, 2 ticks each");
        assertThat(h.ok("texture_view", "name", "block/lava")).startsWith("frame 1 of 8\n16x16\n");
        // Generate and animate in one call; the pixels are left out of the result.
        assertThat(h.ok("texture", "name", "item/orb", "kind", "orb", "color", "#40c0ff", "animate", "pulse"))
                .startsWith("wrote msc:item/orb: 16x16").contains("\nanimated msc:item/orb: 8 frames, 2 ticks each")
                .doesNotContain("palette:");
        assertThat(h.error("texture", "name", "block/lava", "animate", "spin"))
                .isEqualTo("error: animate must be scroll, \"scroll dx,dy\" or pulse");
        h.ok("texture", "name", "block/lava", "kind", "noise", "color", "#e05010");
        assertThat(png.resolveSibling("lava.png.mcmeta")).doesNotExist();
    }

    @Test
    void textureTakesExactlyOneWay() {
        assertThat(h.error("texture", "name", "item/x")).contains("give rows and palette to draw, kind and color");
        assertThat(h.error("texture", "name", "item/x", "kind", "gem", "color", "#fff", "copy", "item/y"))
                .isEqualTo("error: give one of rows, kind or copy, not kind and copy");
        assertThat(h.error("texture", "name", "item/x", "rows", List.of("a"))).contains("rows need a palette");
    }

    @Test
    void toolShapesAreHandheldWithAWoodenHandle() throws Exception {
        assertThat(h.ok("texture", "name", "item/ruby_sword", "kind", "sword", "color", "#d4263a"))
                .contains("item_model msc:ruby_sword");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/item/ruby_sword.json")).content()
                .contains("minecraft:item/handheld");
        h.ok("texture", "name", "item/drumstick", "kind", "food", "color", "#c07a3a", "accent", "#ffffff");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/item/drumstick.json")).content()
                .contains("minecraft:item/generated");
    }

    @Test
    void retexturingAVanillaTextureKeepsVanillasModels() {
        assertThat(h.ok("texture", "name", "minecraft:block/crimson_stem", "kind", "noise", "color", "#4a1530"))
                .startsWith("wrote minecraft:block/crimson_stem").doesNotContain("item_model");
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/textures/block/crimson_stem.png")).exists();
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/models/block/crimson_stem.json")).doesNotExist();
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/items/crimson_stem.json")).doesNotExist();
    }

    @Test
    void modelCreateFillsTheSlotsOfAVanillaParent() {
        assertThat(h.ok("model_create", "name", "block/ruby_log", "kind", "cube_column",
                "textures", List.of("end=block/ruby_log_top", "side=block/ruby_log")))
                .isEqualTo("wrote model msc:block/ruby_log; item_model msc:ruby_log\nnext: pack");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/block/ruby_log.json")).content()
                .contains("\"parent\":\"minecraft:block/cube_column\"").contains("\"side\":\"msc:block/ruby_log\"");
        assertThat(workspace.resolve(".msc/pack/assets/msc/items/ruby_log.json")).exists();
        assertThat(h.error("model_create", "name", "block/x", "kind", "cube_all", "textures", List.of("a=block/a", "b=block/b")))
                .contains("cube_all needs textures for all; missing all");
        assertThat(h.error("model_create", "name", "block/x", "kind", "slab", "textures", List.of("block/a")))
                .contains("unknown model kind \"slab\"");
    }

    @Test
    void packCheckFindsMissingTexturesAndPackBuildPrintsTheSummary() {
        assertThat(h.error("pack", "check", true)).contains("the pack is empty");
        h.ok("model_create", "name", "block/ghost", "kind", "cube_all", "textures", List.of("block/ghost"));
        assertThat(h.ok("pack", "check", true)).startsWith("pack check: 2 files, 1 errors, ")
                .contains("texture all = msc:block/ghost is not in the pack").doesNotContain("pack.mcmeta");
        // A build lists the problems too, so no second call is needed.
        assertThat(h.ok("pack", "apply", false)).endsWith("\npack check: 3 files, 1 errors, 0 warnings, 0 notes\n"
                + "ERROR assets/msc/models/block/ghost.json: texture all = msc:block/ghost is not in the pack");
        assertThat(h.error("pack", "check", true, "limit", "x")).contains("argument limit must be an integer");
    }

    @Test
    void packIsZippedServedAndSentToTheServer() throws Exception {
        h.ok("texture", "name", "item/ruby", "palette", List.of("r=#d4263a"), "rows", List.of("rr", "rr"));
        String result = h.ok("pack");
        assertThat(result).contains("applied: sent to 1 players");
        String url = result.lines().filter(l -> l.startsWith("url: ")).findFirst().orElseThrow().substring(5);
        String sha1 = result.lines().filter(l -> l.startsWith("sha1: ")).findFirst().orElseThrow().substring(6);
        assertThat(h.agent.packs).hasSize(1);
        assertThat(h.agent.packs.get(0).url()).isEqualTo(url);
        assertThat(h.agent.packs.get(0).sha1()).isEqualTo(sha1);
        HttpResponse<byte[]> response = HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(response.body()))).isEqualTo(sha1);
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(response.body()))) {
            for (ZipEntry e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                names.add(e.getName());
            }
        }
        assertThat(names).containsExactlyInAnyOrder("pack.mcmeta", "assets/msc/textures/item/ruby.png",
                "assets/msc/models/item/ruby.json", "assets/msc/items/ruby.json");
        // 26.3 is resource pack format 97.1; packs at 65 or newer are matched by min_format and max_format.
        assertThat(Files.readString(workspace.resolve(".msc/pack/pack.mcmeta")))
                .contains("\"min_format\":97").contains("\"max_format\":9999");
        // Same content, same hash: the client will not download it again.
        assertThat(h.ok("pack", "apply", false)).contains("sha1: " + sha1);
    }
}
