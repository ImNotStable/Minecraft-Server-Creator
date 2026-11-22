package io.github.imnotstable.msc.core.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.imnotstable.msc.core.tool.ToolException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** world_create's data pack (ADR 0075); the files were also loaded by a Paper 26.3 server, all nine terrains. */
class DimensionsTest {
    private static JsonObject json(String name, String terrain, List<String> biomes, List<String> layers) {
        return JsonParser.parseString(Dimensions.json(new Dimensions.Spec(name, terrain, biomes, layers)))
                .getAsJsonObject();
    }

    @Test
    void noiseTerrainsUseTheGamesOwnSettings() {
        JsonObject normal = json("a", "normal", List.of(), List.of());
        assertThat(normal.get("type").getAsString()).isEqualTo("minecraft:overworld");
        JsonObject gen = normal.getAsJsonObject("generator");
        assertThat(gen.get("settings").getAsString()).isEqualTo("minecraft:overworld");
        assertThat(gen.getAsJsonObject("biome_source").get("preset").getAsString()).isEqualTo("minecraft:overworld");
        assertThat(json("a", "amplified", List.of(), List.of()).getAsJsonObject("generator").get("settings")
                .getAsString()).isEqualTo("minecraft:amplified");
        JsonObject nether = json("a", "nether", List.of(), List.of());
        assertThat(nether.get("type").getAsString()).isEqualTo("minecraft:the_nether");
        assertThat(nether.getAsJsonObject("generator").getAsJsonObject("biome_source").get("preset").getAsString())
                .isEqualTo("minecraft:nether");
        assertThat(json("a", "end", List.of(), List.of()).getAsJsonObject("generator").getAsJsonObject("biome_source")
                .get("type").getAsString()).isEqualTo("minecraft:the_end");
    }

    @Test
    void oneBiomeIsFixedAndSeveralShareTheClimate() {
        assertThat(json("a", "normal", List.of("badlands"), List.of()).getAsJsonObject("generator")
                .getAsJsonObject("biome_source").get("biome").getAsString()).isEqualTo("minecraft:badlands");
        JsonObject source = json("a", "normal", List.of("plains", "desert", "mod:glade"), List.of())
                .getAsJsonObject("generator").getAsJsonObject("biome_source");
        assertThat(source.get("type").getAsString()).isEqualTo("minecraft:multi_noise");
        assertThat(source.getAsJsonArray("biomes")).hasSize(3);
        assertThat(source.getAsJsonArray("biomes").get(2).getAsJsonObject().get("biome").getAsString())
                .isEqualTo("mod:glade");
    }

    @Test
    void flatTakesLayersBottomUp() {
        JsonObject settings = json("a", "flat", List.of(), List.of("bedrock", "stone:20", "grass_block"))
                .getAsJsonObject("generator").getAsJsonObject("settings");
        assertThat(settings.getAsJsonArray("layers")).hasSize(3);
        assertThat(settings.getAsJsonArray("layers").get(1).getAsJsonObject().get("height").getAsInt()).isEqualTo(20);
        assertThat(settings.get("biome").getAsString()).isEqualTo("minecraft:plains");
    }

    @Test
    void badInputIsRefused() {
        assertThatThrownBy(() -> new Dimensions.Spec("Big World", "normal", List.of(), List.of()))
                .isInstanceOf(ToolException.class).hasMessageContaining("a-z, 0-9 and _");
        assertThatThrownBy(() -> new Dimensions.Spec("a", "lava_lakes", List.of(), List.of()))
                .hasMessageContaining("unknown terrain");
        assertThatThrownBy(() -> new Dimensions.Spec("a", "normal", List.of(), List.of("stone")))
                .hasMessageContaining("terrain=flat");
        assertThatThrownBy(() -> new Dimensions.Spec("a", "normal", List.of("bad biome"), List.of()))
                .hasMessageContaining("not a resource id");
    }

    @Test
    void writeListAndRemove(@TempDir Path world) throws Exception {
        Path file = Dimensions.write(world, new Dimensions.Spec("ember", "normal", List.of("badlands"), List.of()));
        Dimensions.write(world, new Dimensions.Spec("sky", "floating_islands", List.of(), List.of()));
        assertThat(file).isEqualTo(world.resolve("datapacks/msc-worlds/data/msc/dimension/ember.json"));
        JsonObject meta = JsonParser.parseString(Files.readString(world.resolve("datapacks/msc-worlds/pack.mcmeta")))
                .getAsJsonObject().getAsJsonObject("pack");
        assertThat(meta.get("min_format").getAsInt()).isLessThanOrEqualTo(121); // Minecraft 26.3's data format
        assertThat(Dimensions.list(world)).containsExactly("msc:ember", "msc:sky");
        assertThat(Dimensions.remove(world, "ember")).isTrue();
        assertThat(Dimensions.remove(world, "ember")).isFalse();
        assertThat(Dimensions.list(world)).containsExactly("msc:sky");
    }
}
