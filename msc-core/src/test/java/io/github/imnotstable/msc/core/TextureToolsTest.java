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
    void createWritesPngModelAndItemDefinition() throws Exception {
        String result = h.ok("texture_create", "name", "item/ruby",
                "palette", List.of("r=#d4263a d=#701", "g=#00ff0080"), "rows", ruby());
        assertThat(result).isEqualTo("""
                wrote msc:item/ruby 16x16; use it by setting an item's item_model component to msc:ruby
                next: pack_build""");
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
        h.ok("texture_create", "name", "myplugin:block/ruby_ore", "model", "block",
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
        assertThat(h.error("pack_build", "apply", false)).contains("the pack is empty");
        assertThat(h.error("texture_create", "name", "item/x", "palette", List.of("a=#000000"), "rows", List.of("ab")))
                .contains("symbol 'b' in row 1 is not in the palette");
        assertThat(h.error("texture_create", "name", "item/x", "palette", List.of("a=red"), "rows", List.of("a")))
                .contains("bad color \"red\"");
        assertThat(h.error("texture_create", "name", "item/x", "palette", List.of("a=#000"), "rows", List.of("aa", "a")))
                .contains("row 2 has 1 pixels, expected 2");
        assertThat(h.error("texture_create", "name", "Item/Ruby", "palette", List.of("a=#000"), "rows", List.of("a")))
                .contains("bad texture name");
        assertThat(h.ok("texture_create", "name", "item/odd", "palette", List.of("a=#000"), "rows", List.of("aaa", "aaa", "aaa")))
                .contains("warning: width 3 is not a power of two");
    }

    @Test
    void generateWritesAShadedTextureThatTheOtherToolsRead() throws Exception {
        String gem = h.ok("texture_generate", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
        assertThat(gem).startsWith("wrote msc:item/ruby: 16x16, ").contains("main hue 0 (red)")
                .contains("; item_model msc:ruby\n16x16\npalette: .=transparent").endsWith("pack_build");
        assertThat(h.ok("texture_view", "name", "item/ruby")).startsWith("16x16\npalette:");
        String ore = h.ok("texture_generate", "name", "block/ruby_ore", "kind", "ore", "color", "#7f7f7f",
                "accent", "#d4263a", "size", 32, "seed", 7, "contrast", 0.5);
        assertThat(ore).startsWith("wrote msc:block/ruby_ore: 32x32, ").contains("item_model msc:ruby_ore");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/block/ruby_ore.json")).content().contains("cube_all");
        assertThat(h.error("texture_generate", "name", "item/x", "kind", "star", "color", "#fff"))
                .isEqualTo("error: kind must be noise, ore, planks, bricks, gem, ingot or orb");
        assertThat(h.error("texture_generate", "name", "item/x", "kind", "gem", "color", "#fff", "contrast", 3))
                .contains("contrast must be 0.2 to 1.5");
    }

    @Test
    void recolorKeepsTheShadingOnNewColors() {
        h.ok("texture_generate", "name", "item/ruby", "kind", "gem", "color", "#d4263a");
        assertThat(h.ok("texture_recolor", "from", "item/ruby", "name", "item/sapphire", "color", "#2050e0"))
                .startsWith("wrote msc:item/sapphire: 16x16, ").contains("(blue)").endsWith("next: pack_build");
        assertThat(h.error("texture_recolor", "from", "item/nope", "name", "item/x", "color", "#fff"))
                .isEqualTo("error: no texture msc:item/nope in the pack");
    }

    @Test
    void animateStacksFramesAndWritesTheTiming() throws Exception {
        h.ok("texture_generate", "name", "block/lava", "kind", "noise", "color", "#e05010");
        assertThat(h.ok("texture_animate", "name", "block/lava", "mode", "scroll", "frames", 4, "frametime", 3))
                .startsWith("animated msc:block/lava: 4 frames, 3 ticks each");
        Path png = workspace.resolve(".msc/pack/assets/msc/textures/block/lava.png");
        assertThat(ImageIO.read(png.toFile()).getHeight()).isEqualTo(64);
        assertThat(png.resolveSibling("lava.png.mcmeta")).content().contains("\"frametime\":3");
        assertThat(h.error("texture_animate", "name", "block/lava", "mode", "scroll", "frames", 2, "frametime", 1))
                .contains("scroll needs a square texture");
        h.ok("texture_generate", "name", "item/orb", "kind", "orb", "color", "#40c0ff");
        assertThat(h.ok("texture_animate", "name", "item/orb", "mode", "pulse", "frames", 8, "frametime", 2))
                .contains("8 frames, 2 ticks each");
        assertThat(h.error("texture_animate", "name", "block/lava", "mode", "spin", "frames", 2, "frametime", 1))
                .isEqualTo("error: mode must be scroll or pulse");
        h.ok("texture_generate", "name", "block/lava", "kind", "noise", "color", "#e05010");
        assertThat(png.resolveSibling("lava.png.mcmeta")).doesNotExist();
    }

    @Test
    void modelCreateFillsTheSlotsOfAVanillaParent() {
        assertThat(h.ok("model_create", "name", "block/ruby_log", "kind", "cube_column",
                "textures", List.of("end=block/ruby_log_top", "side=block/ruby_log")))
                .isEqualTo("wrote model msc:block/ruby_log; item_model msc:ruby_log\nnext: pack_build");
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
        assertThat(h.error("pack_check")).contains("the pack is empty");
        h.ok("model_create", "name", "block/ghost", "kind", "cube_all", "textures", List.of("block/ghost"));
        assertThat(h.ok("pack_check")).startsWith("pack check: 2 files, 1 errors, ")
                .contains("texture all = msc:block/ghost is not in the pack").doesNotContain("pack.mcmeta");
        assertThat(h.ok("pack_build", "apply", false)).endsWith("\npack check: 3 files, 1 errors, 0 warnings, 0 notes");
        assertThat(h.error("pack_check", "limit", "x")).contains("argument limit must be an integer");
    }

    @Test
    void packIsZippedServedAndSentToTheServer() throws Exception {
        h.ok("texture_create", "name", "item/ruby", "palette", List.of("r=#d4263a"), "rows", List.of("rr", "rr"));
        String result = h.ok("pack_build");
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
        assertThat(h.ok("pack_build", "apply", false)).contains("sha1: " + sha1);
    }
}
