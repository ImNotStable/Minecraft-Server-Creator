package io.github.imnotstable.msc.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.textures.Models;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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

    /** #458: texture_view obeys the cap rule: 6,000 characters by default, maxChars up to 24,000. */
    @Test
    void textureViewObeysTheCapRule() {
        List<String> palette = new ArrayList<>();
        String symbols = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < symbols.length(); i++) {
            palette.add(symbols.charAt(i) + "=#" + String.format("%02x%02x%02x", i * 4, 255 - i * 4, i));
        }
        List<String> rows = new ArrayList<>();
        for (int y = 0; y < 160; y++) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < 160; x++) {
                row.append(symbols.charAt((x * 7 + y * 3) % symbols.length()));
            }
            rows.add(row.toString());
        }
        h.ok("texture", "name", "block/big", "palette", palette, "rows", rows);
        assertThat(h.ok("texture_view", "name", "block/big")).hasSizeLessThanOrEqualTo(6000)
                .endsWith("Not all rows fit. Or raise maxChars (at most 24000).]");
        assertThat(h.ok("texture_view", "name", "block/big", "maxChars", 24000)).hasSizeLessThanOrEqualTo(24000)
                .hasSizeGreaterThan(20000).endsWith("Not all rows fit.]");
        assertThat(h.ok("texture_view", "name", "block/big", "maxChars", 400)).hasSizeLessThanOrEqualTo(400);
    }

    @Test
    void aPackDescriptionMayHaveTwoLines() throws Exception {
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        h.ok("pack", "apply", false, "description", "Ruby pack\r\nby the team");
        assertThat(workspace.resolve(".msc/pack/pack.mcmeta")).content().contains("Ruby pack\\nby the team");
        assertThat(h.error("pack", "apply", false, "description", "a\nb\nc"))
                .isEqualTo("error: argument description has 3 lines; at most 2 are shown");
        assertThat(h.error("pack", "apply", false, "description", "a\tb")).isEqualTo("error: argument description has a"
                + " control character (U+0009); only a line break may be in it");
    }

    /** #457: a texture name too long for the file system is refused before any folder is made. */
    @Test
    void aTextureNameTooLongForTheFileSystemLeavesNothingBehind() throws Exception {
        Path pack = workspace.resolve(".msc/pack");
        java.util.Map<String, String> before = TreeSnapshot.of(pack);
        for (String name : List.of("item/" + "a".repeat(250), "block/deep/" + "b".repeat(300))) {
            String error = h.error("texture", "name", name, "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
            assertThat(error).startsWith("error: texture ").contains("Use a shorter name.").doesNotContain("\n")
                    .doesNotContain("internal");
        }
        assertThat(h.error("model_create", "name", "item/" + "d".repeat(300), "kind", "item", "textures",
                List.of("item/ruby"))).startsWith("error: model ").contains("at most 255").doesNotContain("\n");
        assertThat(TreeSnapshot.of(pack)).isEqualTo(before);
        // The longest name that fits still works, its animation's .png.mcmeta included.
        assertThat(h.ok("texture", "name", "item/" + "c".repeat(244), "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "pulse"))
                .contains("animated ");
    }

    @Test
    void aPngPathWritesALooseFileOutsideThePack() throws Exception {
        String gem = h.ok("texture", "name", "textures/ruby.png", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        assertThat(gem).startsWith("wrote " + workspace.resolve("textures/ruby.png") + ": 16x16, ");
        assertThat(ImageIO.read(workspace.resolve("textures/ruby.png").toFile()).getWidth()).isEqualTo(16);
        assertThat(workspace.resolve(".msc/pack/assets")).doesNotExist();
        assertThat(h.ok("texture", "name", "out/Drawn.PNG", "palette", List.of("r=#d4263a"), "rows", List.of("r.", ".r")))
                .startsWith("wrote " + workspace.resolve("out/Drawn.PNG") + ": 2x2");
        assertThat(h.error("texture", "name", "a.png", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "pulse"))
                .contains("pack texture name");
    }

    @Test
    void createWritesPngModelAndItemDefinition() throws Exception {
        String result = h.ok("texture", "name", "item/ruby",
                "palette", List.of("r=#d4263a d=#701", "g=#00ff0080"), "rows", ruby());
        assertThat(result).isEqualTo("""
                wrote msc:item/ruby 16x16; item_model msc:ruby
                warning: only 3 colors: shade each material with 3-4 tones (dark outline, shadow, base, highlight), \
                as vanilla textures do
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
    void sameNamesInOtherFoldersGetTheirOwnModels() {
        List<String> palette = List.of("r=#d4263a");
        assertThat(h.ok("texture", "name", "item/red/gem", "palette", palette, "rows", List.of("r")))
                .contains("item_model msc:red/gem");
        assertThat(h.ok("texture", "name", "item/blue/gem", "palette", palette, "rows", List.of("r")))
                .contains("item_model msc:blue/gem");
        h.ok("texture", "name", "item/ruby", "palette", palette, "rows", List.of("r"));
        assertThat(h.error("texture", "name", "block/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "model", "block"))
                .isEqualTo("error: item msc:ruby already shows model msc:item/ruby; give this one another name");
    }

    @Test
    void viewOutputDrawsTheSameTextureAgainAndBadFilesAreReported() throws Exception {
        h.ok("texture", "name", "item/dot", "palette", List.of("r=#d4263a"), "rows", List.of("r.", ".r"));
        List<String> view = h.ok("texture_view", "name", "item/dot").lines().toList();
        h.ok("texture", "name", "item/dot2", "model", "none", "palette", List.of(view.get(1)), "rows", view.subList(2, 4));
        assertThat(h.ok("texture_view", "name", "item/dot2")).isEqualTo(String.join("\n", view));
        Path bad = workspace.resolve(".msc/pack/assets/msc/textures/item/bad.png");
        Files.writeString(bad, "not a png");
        assertThat(h.error("texture_view", "name", "item/bad")).isEqualTo("error: not a PNG image");
    }

    @Test
    void viewShowsATextureWithMoreColorsThanSymbols() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 73; i++) {
            image.setRGB(i % 16, i / 16, 0xFF000000 | i * 3 << 16 | i << 8);
        }
        Path png = workspace.resolve(".msc/pack/assets/minecraft/textures/block/shaded.png");
        Files.createDirectories(png.getParent());
        ImageIO.write(image, "png", png.toFile());
        assertThat(h.ok("texture_view", "name", "minecraft:block/shaded"))
                .startsWith("73 colors merged to 72 to show them; the file is unchanged\n16x16\n");
    }

    @Test
    void mistakesAreReportedPrecisely() {
        assertThat(h.error("pack", "apply", false)).contains("the pack is empty");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=#000000"), "rows", List.of("ab")))
                .contains("symbol 'b' in row 1 is not in the palette");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=#000000"), "rows", List.of("ab", "cb")))
                .contains("symbol 'b' in rows 1, 2; symbol 'c' in row 2 are not in the palette");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=red"), "rows", List.of("a")))
                .contains("bad color \"red\"");
        assertThat(h.error("texture", "name", "item/x", "palette", List.of("a=#000"), "rows", List.of("aa", "a")))
                .contains("row 2 has 1 pixels, expected 2");
        assertThat(h.error("texture", "name", "Item/Ruby", "palette", List.of("a=#000"), "rows", List.of("a")))
                .contains("bad resource name");
        assertThat(h.ok("texture", "name", "item/odd", "palette", List.of("a=#000"), "rows", List.of("aaa", "aaa", "aaa")))
                .contains("warning: width 3 is not a power of two");
    }

    @Test
    void flatDrawingsAndStockShapesAreFlagged() {
        // Two tones over a 16x16 square: too few to read as shaded.
        List<String> rows = new java.util.ArrayList<>();
        for (int y = 0; y < 16; y++) {
            rows.add(y % 2 == 0 ? "ab".repeat(8) : "ba".repeat(8));
        }
        assertThat(h.ok("texture", "name", "item/flat", "palette", List.of("a=#204060", "b=#305070"), "rows", rows))
                .contains("warning: only 2 colors: shade each material with 3-4 tones");
        // Five tones, one of them over half the pixels.
        List<String> big = new java.util.ArrayList<>();
        for (int y = 0; y < 16; y++) {
            big.add(y < 9 ? "a".repeat(16) : "bcde".repeat(4));
        }
        assertThat(h.ok("texture", "name", "item/big", "palette",
                List.of("a=#204060", "b=#305070", "c=#406080", "d=#507090", "e=#6080a0"), "rows", big))
                .contains("warning: #204060 fills 56%: break it up");
    }

    @Test
    void thereIsNoStockTextureToGenerate() {
        // From scratch (ADR 0106): no kind= shapes or patterns; every texture is drawn or a recolor of the pack's own.
        assertThat(h.error("texture", "name", "item/x", "kind", "gem", "color", "#fff")).contains("kind");
        assertThat(h.error("texture", "name", "item/x", "color", "#fff")).contains("give rows and palette to draw");
    }

    @Test
    void recolorKeepsTheShadingOnNewColors() {
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        assertThat(h.ok("texture", "copy", "item/ruby", "name", "item/sapphire", "color", "#2050e0"))
                .startsWith("wrote msc:item/sapphire: 16x16, ").contains("(azure)").endsWith("next: pack");
        assertThat(h.error("texture", "copy", "item/nope", "name", "item/x", "color", "#fff"))
                .isEqualTo("error: no texture msc:item/nope in the pack (vanilla textures are not in it)");
    }

    @Test
    void animateStacksFramesAndWritesTheTiming() throws Exception {
        h.ok("texture", "name", "block/lava", "palette", Drawings.palette("#e05010"), "rows", Drawings.rows());
        assertThat(h.ok("texture", "name", "block/lava", "animate", "scroll", "frames", 4, "frametime", 3))
                .isEqualTo("animated msc:block/lava: 4 frames, 3 ticks each\nnext: pack");
        Path png = workspace.resolve(".msc/pack/assets/msc/textures/block/lava.png");
        assertThat(ImageIO.read(png.toFile()).getHeight()).isEqualTo(64);
        assertThat(png.resolveSibling("lava.png.mcmeta")).content().contains("\"frametime\":3");
        // Animating again starts from frame 1; scroll's default is one full loop.
        assertThat(h.ok("texture", "name", "block/lava", "animate", "scroll 0,2")).contains("8 frames, 2 ticks each");
        assertThat(h.ok("texture_view", "name", "block/lava")).startsWith("frame 1 of 8\n16x16\n");
        // Draw and animate in one call; the pixels are left out of the result.
        assertThat(h.ok("texture", "name", "item/orb", "palette", Drawings.palette("#40c0ff"), "rows", Drawings.rows(), "animate", "pulse"))
                .startsWith("wrote msc:item/orb 16x16").contains("\nanimated msc:item/orb: 8 frames, 2 ticks each")
                .doesNotContain("palette:");
        assertThat(h.error("texture", "name", "block/lava", "animate", "spin"))
                .isEqualTo("error: animate must be scroll, \"scroll dx,dy\" or pulse");
        h.ok("texture", "name", "block/lava", "palette", Drawings.palette("#e05010"), "rows", Drawings.rows());
        assertThat(png.resolveSibling("lava.png.mcmeta")).doesNotExist();
    }

    @Test
    void scrollDefaultsToAFullLoopAndRefusesAStepThatMovesNothing() throws Exception {
        h.ok("texture", "name", "block/lava", "palette", Drawings.palette("#e05010"), "rows", Drawings.rows());
        // 1,2 on 16x16 is back at the start after 16 steps, not after 16 / 2 (#534).
        assertThat(h.ok("texture", "name", "block/lava", "animate", "scroll 1,2")).contains("16 frames");
        h.ok("texture", "name", "block/lava", "palette", Drawings.palette("#e05010"), "rows", Drawings.rows());
        assertThat(h.error("texture", "name", "block/lava", "animate", "scroll 16,0"))
                .isEqualTo("error: scroll 16,0 moves nothing on 16x16: give a step that is not a multiple of 16");
        assertThat(h.error("texture", "name", "block/lava", "animate", "scroll 0,0", "frames", 4))
                .startsWith("error: scroll 0,0 moves nothing");
        assertThat(workspace.resolve(".msc/pack/assets/msc/textures/block/lava.png.mcmeta")).doesNotExist();
    }

    @Test
    void textureTakesExactlyOneWay() {
        assertThat(h.error("texture", "name", "item/x")).contains("give rows and palette to draw, copy and color");
        assertThat(h.error("texture", "name", "item/x", "palette", Drawings.palette("#fff"), "rows", Drawings.rows(), "copy", "item/y"))
                .isEqualTo("error: give one of rows or copy, not rows and copy");
        assertThat(h.error("texture", "name", "item/x", "rows", List.of("a"))).contains("rows need a palette");
    }

    @Test
    void retexturingAVanillaTextureKeepsVanillasModels() {
        assertThat(h.ok("texture", "name", "minecraft:block/crimson_stem", "palette", Drawings.palette("#4a1530"), "rows", Drawings.rows()))
                .startsWith("wrote minecraft:block/crimson_stem").doesNotContain("item_model");
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/textures/block/crimson_stem.png")).exists();
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/models/block/crimson_stem.json")).doesNotExist();
        assertThat(workspace.resolve(".msc/pack/assets/minecraft/items/crimson_stem.json")).doesNotExist();
    }

    @Test
    void modelCreateFillsTheSlotsOfAVanillaParent() {
        assertThat(h.ok("model_create", "name", "block/ruby_log", "kind", "cube_column",
                "textures", List.of("end=block/ruby_log_top", "side=block/ruby_log")))
                .isEqualTo("wrote model msc:block/ruby_log; item_model msc:ruby_log\nnot in the pack yet: "
                        + "msc:block/ruby_log_top, msc:block/ruby_log; draw them with texture\nnext: pack");
        assertThat(workspace.resolve(".msc/pack/assets/msc/models/block/ruby_log.json")).content()
                .contains("\"parent\":\"minecraft:block/cube_column\"").contains("\"side\":\"msc:block/ruby_log\"");
        assertThat(workspace.resolve(".msc/pack/assets/msc/items/ruby_log.json")).exists();
        assertThat(h.error("model_create", "name", "block/x", "kind", "cube_all", "textures", List.of("a=block/a", "b=block/b")))
                .isEqualTo("error: cube_all has no texture slot \"a\"; slots are all");
        assertThat(h.error("model_create", "name", "block/x", "kind", "slab", "textures", List.of("block/a")))
                .contains("unknown model kind \"slab\"");
    }

    /**
     * #691: the slot rules hold for every model kind and every way of giving its slots. One bare name fills every
     * slot and comes alone; otherwise every slot of the kind is named once, and no other. A refusal writes nothing
     * and quotes only what the call wrote.
     */
    @Test
    void modelCreateSlotRulesHoldForEveryKindAndSlotCombination() throws Exception {
        int model = 0;
        for (Models.Kind kind : Models.Kind.values()) {
            List<String> slots = kind.slots();
            String id = kind.id();
            String all = String.join(", ", slots);
            Map<List<String>, Object> rows = new LinkedHashMap<>();
            Map<String, String> filled = new LinkedHashMap<>();
            slots.forEach(s -> filled.put(s, "msc:block/t"));
            rows.put(List.of("block/t"), filled);
            // Every non-empty set of named slots: only all of them, in any order, is a model.
            for (int mask = 1; mask < 1 << slots.size(); mask++) {
                List<String> named = new ArrayList<>();
                List<String> missing = new ArrayList<>();
                for (int i = 0; i < slots.size(); i++) {
                    ((mask & 1 << i) != 0 ? named : missing).add(slots.get(i));
                }
                List<String> entries = named.stream().map(s -> s + "=block/" + s).toList();
                if (missing.isEmpty()) {
                    Map<String, String> own = new LinkedHashMap<>();
                    slots.forEach(s -> own.put(s, "msc:block/" + s));
                    rows.put(entries, own);
                    rows.put(entries.reversed(), own);
                } else {
                    rows.put(entries, "error: " + id + " needs textures for " + all + "; missing "
                            + String.join(", ", missing));
                }
                List<String> withBare = new ArrayList<>(entries);
                withBare.add("block/t");
                String bare = "error: \"block/t\" names no slot: give either one bare texture, which fills every "
                        + "slot, or slot=texture for each of " + all;
                rows.put(withBare, bare);
                rows.put(withBare.reversed(), bare);
            }
            List<String> every = slots.stream().map(s -> s + "=block/" + s).toList();
            for (String slot : slots) {
                String unknown = "error: " + id + " has no texture slot \"" + slot + "x\"; slots are " + all;
                rows.put(List.of(slot + "x=block/t"), unknown);
                List<String> plus = new ArrayList<>(every);
                plus.add(slot + "x=block/t");
                rows.put(plus, unknown);
                List<String> twice = new ArrayList<>(every);
                twice.add(slot + "=block/t");
                rows.put(twice, "error: slot " + slot + " is given twice");
            }
            rows.put(List.of("block/t", "block/u"), "error: \"block/t\" names no slot: give either one bare texture, "
                    + "which fills every slot, or slot=texture for each of " + all);
            for (var row : rows.entrySet()) {
                String name = "block/m" + ++model;
                Path file = workspace.resolve(".msc/pack/assets/msc/models/" + name + ".json");
                var result = h.call("model_create", "name", name, "kind", id, "textures", row.getKey(), "item", false);
                var that = assertThat(result.text()).as("%s %s", id, row.getKey());
                if (row.getValue() instanceof String refusal) {
                    that.isEqualTo(refusal);
                    assertThat(file).as("%s %s", id, row.getKey()).doesNotExist();
                    // Only what the call wrote is quoted.
                    Matcher quoted = Pattern.compile("\"([^\"]*)\"").matcher(refusal);
                    while (quoted.find()) {
                        String q = quoted.group(1);
                        assertThat(row.getKey()).as("quoted %s", q).anyMatch(e -> e.contains(q));
                    }
                } else {
                    assertThat(result.ok()).as("%s %s: %s", id, row.getKey(), result.text()).isTrue();
                    JsonObject textures = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                            .getAsJsonObject("textures");
                    Map<String, String> written = new LinkedHashMap<>();
                    textures.entrySet().forEach(e -> written.put(e.getKey(), e.getValue().getAsString()));
                    assertThat(written).as("%s %s", id, row.getKey()).isEqualTo(row.getValue());
                }
            }
        }
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
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
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
        assertThat(h.ok("pack", "apply", false)).contains("sha1: " + sha1).contains("pack_format 97");
        assertThat(h.ok("pack", "apply", false, "file", "out/ruby-pack.zip"))
                .contains("saved: " + workspace.resolve("out/ruby-pack.zip"));
        assertThat(Files.readAllBytes(workspace.resolve("out/ruby-pack.zip"))).isEqualTo(response.body());
    }

    /** A texture call refused for any argument, the animation included, writes nothing into the pack (#356). */
    @Test
    void aRefusedTextureLeavesThePackAsItWas() throws Exception {
        Path pack = workspace.resolve(".msc/pack");
        List<List<Object>> refused = List.of(
                List.of("name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "wobble"),
                List.of("name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "pulse", "frames", 999),
                List.of("name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "scroll", "frametime", 0),
                List.of("name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "scroll x,y"),
                List.of("name", "item/ruby", "palette", List.of("r=#d4263a"), "rows", List.of("rr"), "animate", "pulse"),
                List.of("name", "item/ruby", "palette", List.of("r=#d4263a"), "rows", ruby(), "model", "cube"));
        for (List<Object> call : refused) {
            h.error("texture", call.toArray());
            assertThat(pack.resolve("assets")).as("pack after %s", call).doesNotExist();
        }
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        var before = TreeSnapshot.of(pack);
        for (List<Object> call : refused) {
            h.error("texture", call.toArray());
            assertThat(TreeSnapshot.of(pack)).as("pack after %s", call).isEqualTo(before);
        }
        assertThat(h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows(), "animate", "pulse"))
                .contains("wrote msc:item/ruby 16x16").contains("animated msc:item/ruby: 8 frames, 2 ticks each");
        assertThat(pack.resolve("assets/msc/textures/item/ruby.png.mcmeta")).isRegularFile();
    }

    /** A pack call whose apply fails saves no copy of the zip into the workspace (#356). */
    @Test
    void aFailedPackApplySavesNoFile() {
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        h.agent.failPackApply = true;
        assertThat(h.error("pack", "file", "out/ruby-pack.zip")).contains("pack apply failed");
        assertThat(workspace.resolve("out")).doesNotExist();
        h.agent.failPackApply = false;
        assertThat(h.ok("pack", "file", "out/ruby-pack.zip")).contains("applied: sent to 1 players")
                .contains("saved: " + workspace.resolve("out/ruby-pack.zip"));
        assertThat(workspace.resolve("out/ruby-pack.zip")).isRegularFile();
    }

    /**
     * A file= that cannot be written (a folder with files in it, a path through a file) is refused before the pack is
     * built or sent, so server.properties, the pack host and the players keep the same pack (#373).
     */
    @Test
    void anUnwritableFileIsRefusedBeforeThePackIsSent() throws Exception {
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#d4263a"), "rows", Drawings.rows());
        String url = h.ok("pack").lines().filter(l -> l.startsWith("url: ")).findFirst().orElseThrow().substring(5);
        Files.createDirectories(workspace.resolve("run"));
        Files.writeString(workspace.resolve("run/server.properties"), "resource-pack=" + url + "\n");
        Files.createDirectories(workspace.resolve("packs/old"));
        Files.writeString(workspace.resolve("notes.txt"), "a file");
        h.ok("texture", "name", "item/ruby", "palette", Drawings.palette("#2655d4"), "rows", Drawings.rows());
        int sent = h.agent.packs.size();
        assertThat(h.error("pack", "file", "packs")).isEqualTo("error: file " + workspace.resolve("packs")
                + " is a folder; give a file path");
        assertThat(h.error("pack", "file", "notes.txt/pack.zip")).contains("is a file, not a folder");
        assertThat(h.agent.packs).hasSize(sent);
        assertThat(workspace.resolve("run/server.properties")).hasContent("resource-pack=" + url);
        HttpResponse<Void> served = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(served.statusCode()).isEqualTo(200);
    }

    /** ADR 0081: pack_asset writes a glyph, reports a texture it lacks yet, and refuses a file the game would not load. */
    @Test
    void packAssetWritesChecksAndRefuses() throws Exception {
        assertThat(h.ok("pack_asset", "type", "glyph", "name", "myplugin:icons", "json", "{\"file\":\"myplugin:font/coin.png\"}"))
                .startsWith("wrote glyph \\uE000 in myplugin:icons: show it with {\"text\":\"\\uE000\",\"font\":\"myplugin:icons\"}")
                .contains("\nstill to add: bitmap file myplugin:font/coin.png is not in the pack").endsWith("\nnext: pack");
        Path font = workspace.resolve(".msc/pack/assets/myplugin/font/icons.json");
        assertThat(font).content().contains("\uE000");

        String before = java.nio.file.Files.readString(font);
        assertThat(h.error("pack_asset", "type", "font", "name", "myplugin:icons", "json", "{\"providers\":[{\"type\":\"bitmap\"}]}"))
                .isEqualTo("error: not written, the game would not load assets/myplugin/font/icons.json: bitmap provider "
                        + "has no file");
        assertThat(font).content().isEqualTo(before);

        assertThat(h.error("pack_asset", "type", "lang", "name", "en_us", "text", "x"))
                .isEqualTo("error: lang takes json, not text");
        assertThat(h.error("pack_asset", "type", "sound", "name", "boss.roar", "from", "nope.ogg"))
                .startsWith("error: from ").endsWith("nope.ogg is not a file");
        assertThat(h.ok("pack_asset", "type", "shader", "name", "minecraft:include/wave.glsl", "text",
                "float wave(float t) {\n  return sin(t);\n}\n")).startsWith("wrote assets/minecraft/shaders/include/wave.glsl");
        assertThat(h.ok("msc_status", "guide", "pack")).startsWith("PACK GUIDE").contains("type=glyph")
                .endsWith("next: msc_status guide=pack page=2]");
    }
}
