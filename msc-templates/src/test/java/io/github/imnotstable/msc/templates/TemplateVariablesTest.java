package io.github.imnotstable.msc.templates;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateVariablesTest {
    @Test
    void derivesEverythingFromTheName() {
        for (String input : new String[] {"spawn lobby", "spawn-lobby", "SpawnLobby", "spawn_lobby"}) {
            TemplateVariables v = TemplateVariables.of(input);
            assertThat(v.name()).isEqualTo("SpawnLobby");
            assertThat(v.projectId()).isEqualTo("spawn-lobby");
            assertThat(v.packageName()).isEqualTo("com.example.spawnlobby");
            assertThat(v.mainClass()).isEqualTo("SpawnLobbyPlugin");
        }
    }

    @Test
    void handlesAcronymsDigitsAndPluginSuffix() {
        assertThat(TemplateVariables.of("PvP arena 2").projectId()).isEqualTo("pv-p-arena-2");
        assertThat(TemplateVariables.of("E2eDemo").name()).isEqualTo("E2eDemo");
        assertThat(TemplateVariables.of("E2eDemo").projectId()).isEqualTo("e2e-demo");
        assertThat(TemplateVariables.of("spawn2 lobby").projectId()).isEqualTo("spawn2-lobby");
        assertThat(TemplateVariables.of("HTTPServer").projectId()).isEqualTo("http-server");
        assertThat(TemplateVariables.of("MSC tools").name()).isEqualTo("MscTools");
        assertThat(TemplateVariables.of("MyPlugin").mainClass()).isEqualTo("MyPlugin");
    }

    @Test
    void takesAccentsOffAndRefusesLettersWithNoAsciiForm() {
        assertThat(TemplateVariables.of("Café Lobby").projectId()).isEqualTo("cafe-lobby");
        assertThat(TemplateVariables.of("Über Spiel").name()).isEqualTo("UberSpiel");
        assertThat(TemplateVariables.of("Ñandú").packageName()).isEqualTo("com.example.nandu");
        for (String name : new String[] {"Ølstue", "日本", "Straße"}) {
            assertThatThrownBy(() -> TemplateVariables.of(name)).as(name)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name).hasMessageContaining("ASCII");
        }
        assertThatThrownBy(() -> TemplateVariables.of("ｶﾌｪ"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'ｶ' has no ASCII form");
        assertThatThrownBy(() -> TemplateVariables.of("3d Parkour"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'3d Parkour'").hasMessageContaining("letter");
    }

    /** Letters and digits whose ASCII form is a compatibility one (#321); symbols are still dropped, not spelled. */
    @Test
    void givesLettersAndDigitsTheirCompatibilityForm() {
        Map<String, String> names = Map.of(
                "Ｌｏｂｂｙ", "Lobby", "ﬁsh", "Fish", "Mc²", "Mc2", "Ⅳ Lobby", "IvLobby", "Ǆungla", "DZungla",
                "Lobby™", "Lobby", "Café ﬁ", "CafeFi", "Ｃａｆé", "Cafe",
                "Cafe\u0301s", "Cafes");
        names.forEach((input, name) -> assertThat(TemplateVariables.of(input).name()).as(input).isEqualTo(name));
    }

    @Test
    void defaultsAreDevFriendly() {
        TemplateVariables v = TemplateVariables.of("xy");
        assertThat(v.serverIp()).isEqualTo("127.0.0.1");
        assertThat(v.serverPort()).isEqualTo(25565);
        assertThat(v.rconPort()).isEqualTo(25575);
        assertThat(v.rconPassword()).hasSize(24).isNotEqualTo(TemplateVariables.of("xy").rconPassword());
        assertThat(v.levelType()).isEqualTo("minecraft:flat");
        assertThat(v.acceptEula()).isFalse();
        assertThat(v.asMap()).containsEntry("eula", "false");
        assertThat(v.asMap().get("generatorSettings")).contains("\"layers\"", "minecraft:bedrock");
        assertThat(v.withLevelType("minecraft:normal").asMap()).containsEntry("generatorSettings", "{}");
        assertThat(v.withAcceptEula(true).asMap()).containsEntry("eula", "true");
        assertThat(v.asMap()).containsEntry("packagePath", "com/example/xy");
    }

    @Test
    void withersOverrideOneValue() {
        TemplateVariables v = TemplateVariables.of("xy").withPackageName("dev.me.xy").withRconPort(25580);
        assertThat(v.packageName()).isEqualTo("dev.me.xy");
        assertThat(v.rconPort()).isEqualTo(25580);
        assertThat(v.name()).isEqualTo("Xy");
    }

    @Test
    void rejectsBadValues() {
        assertThatThrownBy(() -> TemplateVariables.of("2fast")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TemplateVariables.of("  ")).isInstanceOf(IllegalArgumentException.class);
        TemplateVariables v = TemplateVariables.of("xy");
        assertThatThrownBy(() -> v.withPackageName("Com.Bad")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withDescription("say \"hi\"")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withAuthor("a\nb")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withRconPort(25565)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> v.withServerPort(70000)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNamesThatGiveAnUnusablePackageOrModId() {
        for (String name : new String[] {"Void", "Switch", "Default", "Class", "Int", "True", "Null"}) {
            assertThatThrownBy(() -> TemplateVariables.of(name)).as(name)
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(name).hasMessageContaining("keyword");
        }
        assertThatThrownBy(() -> TemplateVariables.of("xy").withPackageName("dev.new.xy"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("new");
        assertThatThrownBy(() -> TemplateVariables.of("X"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("X").hasMessageContaining("2-64");
        assertThatThrownBy(() -> TemplateVariables.of("xy").withProjectId("a" + "-b".repeat(32)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(TemplateVariables.of("Void Run").packageName()).isEqualTo("com.example.voidrun");
        assertThatThrownBy(() -> TemplateVariables.of("xy").withPackageName("java.lobby"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("java.lobby");
        assertThat(TemplateVariables.of("xy").withProjectId("a" + "b".repeat(63)).projectId()).hasSize(64);
    }
}
