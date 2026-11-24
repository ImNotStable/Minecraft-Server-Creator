package io.github.imnotstable.msc.core.building;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.imnotstable.msc.core.tool.ToolException;
import io.github.imnotstable.msc.protocol.world.BlockGrid;
import io.github.imnotstable.msc.protocol.world.Region;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Block entity helpers, entities, biomes and light (ADR 0082), drawn over stone floor at y 0. */
class ThingsStepsTest {
    /** Stone at y 0 and below; a closed stone room from 20,1,0 to 30,5,10. */
    private static BlockGrid world(Region r) {
        int[] blocks = new int[Math.toIntExact(r.volume())];
        for (int y = r.min().y(); y <= r.max().y(); y++) {
            for (int z = r.min().z(); z <= r.max().z(); z++) {
                for (int x = r.min().x(); x <= r.max().x(); x++) {
                    boolean room = x >= 20 && x <= 30 && z >= 0 && z <= 10 && y >= 1 && y <= 5
                            && (x == 20 || x == 30 || z == 0 || z == 10 || y == 5);
                    blocks[r.index(x, y, z)] = y <= 0 || room ? 1 : 0;
                }
            }
        }
        return new BlockGrid(r, List.of("minecraft:air", "minecraft:stone"), blocks);
    }

    private static Canvas draw(String steps) throws Exception {
        BuildScript.StructureFiles files = new BuildScript.StructureFiles() {
            @Override
            public BlockGrid load(String path, String w) {
                throw new AssertionError();
            }

            @Override
            public BlockGrid read(Region region) {
                return world(region);
            }
        };
        List<Step> parsed = new BuildScript("world", files).parse(steps);
        return Builder.drawJoined(new Step.Group(parsed).bounds(), parsed, ThingsStepsTest::world);
    }

    @Test
    void signsBannersHeadsBooksAndContainersCarryTheirData() throws Exception {
        Canvas c = draw("""
                sign 0,1,0 oak_sign[rotation=8] text="Welcome to|Ashford" color=brown glow=true
                banner 1,1,0 white_banner[rotation=0] patterns=stripe_top:red,cross:black
                head 2,1,0 player_head[rotation=4] skin=0123456789abcdef0123456789abcdef
                book 3,1,0 lectern[facing=south] title="Town Rules" pages="No fighting|Be kind"
                container 4,1,0 chest[facing=north] items=bread:16,iron_ingot:3,lantern
                container 5,1,0 barrel[facing=up] loot=chests/village/village_weaponsmith
                """);
        assertThat(c.planned(0, 1, 0)).startsWith("minecraft:oak_sign[rotation=8]{front_text:{messages:[\"Welcome to\","
                + "\"Ashford\",\"\",\"\"],color:\"brown\",has_glowing_text:1b}");
        assertThat(c.planned(1, 1, 0)).contains("{pattern:\"minecraft:stripe_top\",color:\"red\"}");
        assertThat(c.planned(2, 1, 0)).contains("properties:[{name:\"textures\",value:\"eyJ0ZXh0dXJlcyI6");
        assertThat(c.planned(3, 1, 0)).contains("title:\"Town Rules\"").contains("pages:[\"No fighting\",\"Be kind\"]");
        assertThat(c.planned(4, 1, 0)).contains("{Slot:0b,id:\"minecraft:bread\",count:16}")
                .contains("{Slot:2b,id:\"minecraft:lantern\",count:1}");
        assertThat(c.planned(5, 1, 0)).endsWith("{LootTable:\"minecraft:chests/village/village_weaponsmith\"}");
        assertThatThrownBy(() -> draw("sign 0,1,0 oak_sign text=hi")).isInstanceOf(ToolException.class)
                .hasMessageContaining("rotation");
        assertThatThrownBy(() -> draw("banner 0,1,0 white_banner[rotation=0] patterns=stripe_top:plaid"))
                .isInstanceOf(ToolException.class).hasMessageContaining("pattern:colour");
        // The game drops an unknown item with only a log line; the build must not say it went well.
        assertThatThrownBy(() -> draw("container 0,1,0 chest[facing=north] items=bread:2,nope_item:2"))
                .hasMessageContaining("unknown item minecraft:nope_item");
        assertThatThrownBy(() -> draw("frame 0,1,0 item=nope_thing facing=north"))
                .hasMessageContaining("unknown item minecraft:nope_thing");
        assertThat(Things.itemId("mymod:gadget")).isEqualTo("mymod:gadget");
    }

    @Test
    void aNameWrittenAsQuotedJsonBecomesAComponent() {
        // Run 6's villagers showed {"text":"Archivist Vela","color":"gold"} over their heads.
        assertThat(Things.components("{VillagerData:{profession:\"minecraft:librarian\"},CustomName:'{\"text\":\"Archivist Vela\","
                + "\"color\":\"gold\"}'}")).isEqualTo("{VillagerData:{profession:\"minecraft:librarian\"},"
                + "CustomName:{\"text\":\"Archivist Vela\",\"color\":\"gold\"}}");
        assertThat(Things.components("{CustomName:\"Plain\"}")).isEqualTo("{CustomName:\"Plain\"}");
        // Sonnet 5.5's hamlet hologram showed [{"text":"SALTWICK"}] (ADR 0109); front_text is not a text value.
        assertThat(Things.components("{text:'[{\"text\":\"SALTWICK\",\"color\":\"gold\"}]',front_text:{}}"))
                .isEqualTo("{text:[{\"text\":\"SALTWICK\",\"color\":\"gold\"}],front_text:{}}");
        // A JSON string in quotes showed its quotes: "Old Fisher" with the quote marks (ADR 0109).
        assertThat(Things.components("{CustomName:'\"Old Fisher\"'}")).isEqualTo("{CustomName:\"Old Fisher\"}");
    }

    @Test
    void entitiesAreSummonedTaggedAfterTheBlocks() throws Exception {
        Canvas c = draw("""
                entity 0,1,0 villager data={VillagerData:{profession:"minecraft:farmer"}} still=true yaw=90
                frame 3,2,0 item=filled_map facing=south
                stand 5,1,0 head=iron_helmet mainhand=iron_sword base=false
                """);
        List<String> commands = c.commands();
        assertThat(commands).hasSize(3);
        assertThat(commands.get(0)).startsWith("execute in minecraft:overworld run summon minecraft:villager 0.5 1.0 0.5 "
                + "{Tags:[\"msc_build\",\"" + c.tag() + "\"],NoAI:1b").contains("Rotation:[90.0f,0.0f]")
                .contains("VillagerData:{profession:\"minecraft:farmer\"}");
        assertThat(commands.get(1)).contains("minecraft:item_frame 3.5 2.5 0.5").contains("Facing:3b")
                .contains("Item:{id:\"minecraft:filled_map\",count:1}");
        assertThat(commands.get(2)).contains("equipment:{head:{id:\"minecraft:iron_helmet\",count:1},mainhand:")
                .contains("NoBasePlate:1b");
        assertThat(c.used()).isNull();
    }

    @Test
    void biomesArePaintedInPiecesTheCommandTakes() throws Exception {
        Canvas c = draw("biome 0,0,0 63,31,31 cherry_grove only=plains");
        assertThat(c.commands()).hasSize(2).first().asString()
                .isEqualTo("execute in minecraft:overworld run fillbiome 0 0 0 31 31 31 minecraft:cherry_grove replace "
                        + "minecraft:plains");
        assertThat(Things.dimension("msc:frostveil")).isEqualTo("msc:frostveil");
        assertThat(Things.dimension("world_nether")).isEqualTo("minecraft:the_nether");
    }

    @Test
    void lightGoesOnlyWhereItIsDark() throws Exception {
        Canvas dark = draw("light 21,1,1 29,4,9 level=10");
        long lamps = dark.counts().getOrDefault("lantern[hanging=false]", 0L);
        assertThat(lamps).isBetween(1L, 12L);
        // Every floor spot in the room is now lit to 10 by some lantern.
        Canvas lit = draw("set 25,1,5 sea_lantern\nset 22,1,2 sea_lantern\nset 28,1,8 sea_lantern\nset 22,1,8 sea_lantern\n"
                + "set 28,1,2 sea_lantern\nlight 21,1,1 29,4,9 level=8");
        assertThat(lit.counts()).doesNotContainKey("lantern[hanging=false]");
        assertThat(Things.emits("minecraft:torch")).isEqualTo(14);
        assertThat(Things.emits("minecraft:campfire[lit=false,facing=north]")).isZero();
    }

    @Test
    void quotedOptionsKeepTheirSpaces() {
        assertThat(BuildScript.split("sign 0,1,0 oak_sign[rotation=0] text=\"two words|and more\"").options())
                .containsEntry("text", "two words|and more");
    }
}
