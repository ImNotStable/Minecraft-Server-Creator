package {{packageName}};

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Config parsing. MockBukkit only provides the item registry for the kit. */
class GameConfigTest {
    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    private static GameConfig parse(String yaml) throws InvalidConfigurationException {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return GameConfig.from(c);
    }

    @Test
    void readsEverySetting() throws InvalidConfigurationException {
        GameConfig c = parse("""
                game-mode: survival
                difficulty: hard
                time: 18000
                kit: [stone_sword, 'minecraft:arrow:16']
                stages:
                  - {name: start, spawn: [0, -60, 0]}
                  - {name: boss, spawn: [0, -60, 50, 180, 0], goal: [0, 0, 0, 1, 1, 1]}
                """);
        assertThat(c.gameMode()).isEqualTo(GameMode.SURVIVAL);
        assertThat(c.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(c.time()).isEqualTo(18000);
        assertThat(c.kit().items()).containsExactly(
                new Kit.Entry(Material.STONE_SWORD, 1), new Kit.Entry(Material.ARROW, 16));
        assertThat(c.stageIndex("BOSS")).isEqualTo(1);
        assertThat(c.stageIndex("1")).isZero();
        assertThat(c.stageIndex("3")).isEqualTo(-1);
        assertThat(c.stages().get(0).goal()).isNull();
    }

    @Test
    void badValuesNameTheKey() {
        String stage = "stages: [{name: a, spawn: [0, 0, 0]}]\n";
        assertThatThrownBy(() -> parse("difficulty: brutal\n" + stage)).hasMessage("difficulty: unknown value brutal");
        assertThatThrownBy(() -> parse("kit: [laser]\n" + stage)).hasMessage("kit: unknown item laser");
        assertThatThrownBy(() -> parse("stages: []")).hasMessageContaining("at least one stage");
        assertThatThrownBy(() -> parse("stages: [{name: a}]")).hasMessage("stages[0] (a) needs a spawn");
        assertThatThrownBy(() -> parse("stages: [{name: a, spawn: [0,0,0]}, {name: A, spawn: [0,0,0]}]"))
                .hasMessage("stages[1]: duplicate stage name A");
    }
}
