package io.github.imnotstable.msc.stdlib;

import com.google.gson.JsonParser;
import io.github.imnotstable.msc.stdlib.datapack.Datapack;
import io.github.imnotstable.msc.stdlib.db.Store;
import io.github.imnotstable.msc.stdlib.loot.LootTable;
import io.github.imnotstable.msc.stdlib.npc.Dialogue;
import io.github.imnotstable.msc.stdlib.perm.Permissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Loot tables, permissions, data packs and dialogue (ADR 0084). */
class RulesAndContentTest {
    @TempDir
    Path dir;

    @Test
    void lootRollsWithinCountsWeightsAndConditions() {
        LootTable<String> t = LootTable.<String>builder()
                .pool(p -> p.rolls(2, 2).entry("bread", 3, 1, 3).entry("diamond", 1, 1, 1).nothing(1))
                .pool(p -> p.rolls(1, 1).when(ctx -> ctx.luck() > 0).entry("emerald", 1, 2, 2))
                .build();
        Random r = new Random(4);
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < 2000; i++) {
            for (LootTable.Drop<String> d : t.roll(r, LootTable.Context.none())) {
                seen.merge(d.item(), 1, Integer::sum);
                assertThat(d.count()).isBetween(1, 3);
            }
        }
        assertThat(seen).doesNotContainKey("emerald");
        assertThat(seen.get("bread")).isBetween(2 * seen.get("diamond"), 4 * seen.get("diamond"));
        assertThat(t.roll(r, LootTable.Context.of(UUID.randomUUID(), 1)))
                .contains(new LootTable.Drop<>("emerald", 2));
        var json = JsonParser.parseString(LootTable.vanillaJson(t, "minecraft:chest")).getAsJsonObject();
        assertThat(json.getAsJsonArray("pools")).hasSize(2);
        assertThat(json.toString()).contains("\"function\":\"minecraft:set_count\"").contains("minecraft:empty");
    }

    @Test
    void permissionsFollowWeightsWildcardsDenialsAndExpiry() {
        Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now[0];
            }
        };
        Permissions p = new Permissions(Store.memory(), Store.memory(), clock);
        p.group("default").node("spawn.use").node("shop.*").deny("shop.admin").save();
        p.group("vip").parent("default").weight(10).prefix("[VIP] ").node("kit.vip").node("shop.admin").save();
        p.group("mod").weight(50).prefix("[Mod] ").node("*").deny("server.stop").save();
        UUID a = UUID.randomUUID();
        assertThat(p.has(a, "spawn.use")).isTrue();
        assertThat(p.has(a, "shop.buy.wood")).isTrue();
        assertThat(p.has(a, "shop.admin")).isFalse();
        assertThat(p.has(a, "kit.vip")).isFalse();
        p.addGroup(a, "vip");
        assertThat(p.has(a, "shop.admin")).isTrue();
        assertThat(p.prefix(a)).isEqualTo("[VIP] ");
        p.deny(a, "kit.vip");
        assertThat(p.has(a, "kit.vip")).isFalse();
        p.grant(a, "fly", Duration.ofMinutes(5));
        assertThat(p.has(a, "fly")).isTrue();
        now[0] = now[0].plus(Duration.ofMinutes(6));
        assertThat(p.has(a, "fly")).isFalse();
        p.addGroup(a, "mod");
        assertThat(p.has(a, "anything.at.all")).isTrue();
        assertThat(p.has(a, "server.stop")).isFalse();
        assertThat(p.prefix(a)).isEqualTo("[Mod] ");
    }

    @Test
    void datapacksWriteRecipesLootTagsAndFunctions() throws Exception {
        Datapack pack = Datapack.of("ruby", "Rubies")
                .shaped("ruby_sword", "ruby:ruby_sword", 1, List.of(" R", " R", " S"),
                        Map.of('R', "minecraft:redstone_block", 'S', "#minecraft:planks"))
                .shapeless("dust", "minecraft:redstone", 9, List.of("minecraft:redstone_block"))
                .smelting("ingot", "minecraft:redstone_ore", "minecraft:redstone_block", 1.0, 200)
                .smithing("upgrade", "minecraft:netherite_upgrade_smithing_template", "minecraft:iron_sword",
                        "minecraft:redstone_block", "ruby:ruby_sword")
                .itemTag("gems", "minecraft:redstone_block", "minecraft:emerald")
                .function("hello", List.of("say hi"));
        Path root = pack.write(dir);
        String sword = Files.readString(root.resolve("data/ruby/recipe/ruby_sword.json"));
        assertThat(JsonParser.parseString(sword).getAsJsonObject().get("type").getAsString())
                .isEqualTo("minecraft:crafting_shaped");
        assertThat(sword).contains("\"S\":\"#minecraft:planks\"").contains("\"id\":\"ruby:ruby_sword\"");
        assertThat(Files.readString(root.resolve("data/ruby/recipe/dust.json"))).contains("\"count\":9");
        assertThat(Files.readString(root.resolve("data/ruby/tags/item/gems.json"))).contains("minecraft:emerald");
        assertThat(Files.readString(root.resolve("data/ruby/function/hello.mcfunction"))).isEqualTo("say hi\n");
        assertThat(Files.readString(root.resolve("pack.mcmeta"))).contains("\"min_format\":82");
        // Writing again replaces what the last run wrote.
        Datapack.of("ruby", "Rubies").function("bye", List.of("say bye")).write(dir);
        assertThat(root.resolve("data/ruby/recipe/ruby_sword.json")).doesNotExist();
        assertThatThrownBy(() -> Datapack.of("ruby", "x").shaped("bad", "a", 1, List.of("XX"), Map.of('Y', "b")))
                .hasMessageContaining("'X'");
    }

    @Test
    void dialoguePagesChoicesConditionsAndActions() {
        List<String> done = new ArrayList<>();
        UUID p = UUID.randomUUID();
        boolean[] paid = {false};
        Dialogue d = Dialogue.builder("start")
                .page("start", List.of("Hello {player}"), Dialogue.choice("Shop", "shop"),
                        Dialogue.choice("Secret", "shop", null, x -> paid[0]), Dialogue.choice("Bye", null))
                .page("shop", List.of("Buy a sword?"), Dialogue.action("Buy", "buy"))
                .onAction((who, action) -> done.add(action))
                .build();
        assertThat(Dialogue.fill(d.open(p).lines(), "Alex")).containsExactly("Hello Alex");
        assertThat(d.choices(p)).extracting(Dialogue.Choice::label).containsExactly("Shop", "Bye");
        assertThat(d.choose(p, 0)).get().extracting(Dialogue.Page::id).isEqualTo("shop");
        assertThat(d.choose(p, 0)).isEmpty();
        assertThat(done).containsExactly("buy");
        assertThatThrownBy(() -> Dialogue.builder("a").page("a", List.of(), Dialogue.choice("x", "nowhere")).build())
                .hasMessageContaining("nowhere");
    }
}
